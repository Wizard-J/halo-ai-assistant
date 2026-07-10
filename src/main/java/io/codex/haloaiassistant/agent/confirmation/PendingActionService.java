package io.codex.haloaiassistant.agent.confirmation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 待确认操作管理服务。
 * 使用内存 ConcurrentHashMap 存储，TTL 10 分钟过期。
 * 高风险工具调用此服务创建待确认操作，管理员通过 API 确认/取消。
 * 确认后由服务端内部执行器使用已固化 payload 执行，不再经过模型。
 */
@Slf4j
@Service
public class PendingActionService {

    private final Map<String, PendingAction> store = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final long TTL_MINUTES = 10;

    /**
     * 创建一个待确认操作，返回 PendingActionResult 供 Tool 返回给 AI 显示。
     */
    public PendingActionResult create(String type, String title, String summary,
                                       RiskLevel riskLevel, JsonNode payload) {
        String id = "confirm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant now = Instant.now();

        PendingAction action = new PendingAction();
        action.setId(id);
        action.setType(type);
        action.setTitle(title);
        action.setSummary(summary);
        action.setRiskLevel(riskLevel);
        JsonNode storedPayload = payload == null ? null : payload.deepCopy();
        String requester = storedPayload == null ? ""
                : storedPayload.path("_requester").asText("");
        if (storedPayload instanceof ObjectNode objectPayload) {
            objectPayload.remove("_requester");
        }
        action.setRequester(requester);
        action.setPayload(storedPayload);
        action.setCreatedAt(now);
        action.setExpiresAt(now.plusSeconds(TTL_MINUTES * 60));

        store.put(id, action);
        log.info("创建待确认操作: id={}, type={}, title={}, risk={}", id, type, title, riskLevel);

        // 生成 items（提取 payload 中的关键信息用于展示）
        JsonNode items = buildItems(type, payload);

        return PendingActionResult.forConfirmation(id, title, summary, riskLevel.name().toLowerCase(), items);
    }

    /**
     * 获取待确认操作，如果不存在或已过期返回 null。
     */
    public PendingAction get(String id) {
        return get(id, null);
    }

    public PendingAction get(String id, String requester) {
        PendingAction action = store.get(id);
        if (action == null) {
            log.warn("待确认操作不存在: {}", id);
            return null;
        }
        if (action.isExpired()) {
            store.remove(id);
            log.warn("待确认操作已过期: {}", id);
            return null;
        }
        return canAccess(action, requester) ? action : null;
    }

    /**
     * 确认并执行操作。
     * 原子领取待确认项，并用存储的 payload 执行。
     * 返回包含执行结果的 JsonNode。
     */
    public JsonNode confirmAndExecute(String id) {
        return confirmAndExecute(id, null);
    }

    public JsonNode confirmAndExecute(String id, String requester) {
        PendingAction action = take(id, requester);
        if (action == null) return null;

        try {
            String result = PendingActionExecutor.execute(action);
            if (isExecutionFailure(result)) {
                ObjectNode error = objectMapper.createObjectNode();
                error.put("success", false);
                error.put("error", result);
                error.put("type", action.getType());
                return error;
            }
            ObjectNode success = objectMapper.createObjectNode();
            success.put("success", true);
            success.put("result", result);
            success.put("type", action.getType());
            return success;
        } catch (Exception e) {
            log.error("待确认操作执行失败: id={}, type={}", id, action.getType(), e);
            ObjectNode error = objectMapper.createObjectNode();
            error.put("success", false);
            error.put("error", "操作执行失败: " + e.getMessage());
            return error;
        }
    }

    private boolean isExecutionFailure(String result) {
        if (result == null || result.isBlank()) {
            return true;
        }
        String normalized = result.trim();
        return normalized.startsWith("[错误]")
                || normalized.startsWith("操作执行失败")
                || normalized.contains("失败:");
    }

    /**
     * 确认操作（仅获取，不执行）。
     */
    public PendingAction confirm(String id) {
        return take(id, null);
    }

    /**
     * 取消操作。从存储中移除。
     */
    public boolean cancel(String id) {
        return cancel(id, null);
    }

    public boolean cancel(String id, String requester) {
        PendingAction removed = take(id, requester);
        if (removed == null) {
            log.warn("取消操作失败，不存在: {}", id);
            return false;
        }
        log.info("已取消操作: id={}, type={}", id, removed.getType());
        return true;
    }

    /** 返回当前未过期的待确认项，仅用于 Console 管理界面。 */
    public List<PendingAction> listPending() {
        return listPending(null);
    }

    public List<PendingAction> listPending(String requester) {
        evictExpired();
        return store.values().stream()
                .filter(action -> canAccess(action, requester))
                .sorted(java.util.Comparator.comparing(PendingAction::getCreatedAt).reversed())
                .toList();
    }

    public boolean hasPendingType(String type) {
        evictExpired();
        return store.values().stream().anyMatch(action -> type.equals(action.getType()));
    }

    private PendingAction take(String id, String requester) {
        AtomicReference<PendingAction> claimed = new AtomicReference<>();
        store.computeIfPresent(id, (key, action) -> {
            if (action.isExpired()) return null;
            if (!canAccess(action, requester)) return action;
            claimed.set(action);
            return null;
        });
        return claimed.get();
    }

    private boolean canAccess(PendingAction action, String requester) {
        if (action == null) return false;
        String owner = action.getRequester();
        return requester == null || owner == null || owner.isBlank() || requester.equals(owner);
    }

    /**
     * 从 payload 中提取展示用的 items 列表。
     */
    private JsonNode buildItems(String type, JsonNode payload) {
        ArrayNode items = objectMapper.createArrayNode();
        if (payload == null) return items;

        if ("deleteArticle".equals(type)) {
            String id = payload.path("id").asText("");
            boolean permanent = payload.path("permanent").asBoolean(false);
            ObjectNode item = items.addObject();
            item.put("type", "post");
            item.put("id", id);
            item.put("action", permanent ? "永久删除" : "移入回收站");
        } else if ("updateArticle".equals(type)) {
            String id = payload.path("id").asText("");
            ObjectNode item = items.addObject();
            item.put("type", "post");
            item.put("id", id);
            if (payload.has("publish")) {
                item.put("action", payload.path("publish").asBoolean() ? "发布" : "取消发布");
            } else {
                item.put("action", "更新");
            }
        } else if ("deleteComment".equals(type)) {
            String id = payload.path("id").asText("");
            ObjectNode item = items.addObject();
            item.put("type", "comment");
            item.put("id", id);
            item.put("action", "删除");
        } else if ("approveComment".equals(type)) {
            String id = payload.path("id").asText("");
            ObjectNode item = items.addObject();
            item.put("type", "comment");
            item.put("id", id);
            item.put("action", "审核通过");
        } else if ("deleteCategory".equals(type)) {
            String id = payload.path("id").asText("");
            ObjectNode item = items.addObject();
            item.put("type", "category");
            item.put("id", id);
            item.put("action", "删除");
        } else if ("createCategory".equals(type)) {
            String name = payload.path("name").asText("");
            ObjectNode item = items.addObject();
            item.put("type", "category");
            item.put("name", name);
            item.put("action", "创建");
        } else if ("createTag".equals(type)) {
            String name = payload.path("name").asText("");
            ObjectNode item = items.addObject();
            item.put("type", "tag");
            item.put("name", name);
            item.put("action", "创建");
        } else if ("batchTagArticles".equals(type)) {
            String ids = payload.path("articleIds").asText("");
            ObjectNode item = items.addObject();
            item.put("type", "batch");
            item.put("articleCount", ids.isBlank() ? "全部" : String.valueOf(ids.split("[,，]").length));
            item.put("action", "批量更新标签/分类");
        } else if ("createArticle".equals(type)) {
            String title = payload.path("title").asText("");
            String status = payload.path("status").asText("draft");
            ObjectNode item = items.addObject();
            item.put("type", "post");
            item.put("title", title);
            item.put("action", "published".equals(status) ? "创建并发布" : "创建草稿");
        } else if ("autoTagArticles".equals(type)) {
            ObjectNode item = items.addObject();
            item.put("type", "batch");
            item.put("articleCount", payload.path("articleCount").asInt(0));
            item.put("groupCount", payload.path("groups").size());
            item.put("action", "按已生成提案批量更新标签");
        } else if ("autoOpsTest".equals(type) || "autoOpsScheduled".equals(type)) {
            ObjectNode item = items.addObject();
            item.put("type", "automation");
            item.put("action", "抓取 RSS 并调用 AI 生成待审核草稿提案");
        }

        return items;
    }

    /** 清理过期操作（可由定时任务调用） */
    public void evictExpired() {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, PendingAction> entry : store.entrySet()) {
            if (entry.getValue().isExpired()) {
                expired.add(entry.getKey());
            }
        }
        expired.forEach(store::remove);
        if (!expired.isEmpty()) {
            log.info("已清理 {} 条过期待确认操作", expired.size());
        }
    }
}
