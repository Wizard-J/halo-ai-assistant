package io.codex.haloaiassistant.persona;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 纯函数单元测试 — PersonaUtils 导出/压缩/Token 估算逻辑。
 */
class PersonaUtilsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ========== buildExportContent ==========

    @Test
    @DisplayName("buildExportContent → 有 AGENTS.md + 有对话 → 两者都包含，且原文完整")
    void exportWithContextAndHistory() {
        String context = "# AGENTS.md\n\n## 用户画像\n33岁男性，上海工作，贵州人，目前失业待业\n## 核心议题\n失业期心理震荡\n- 面试失败自我怀疑\n- 隐瞒家人真实情况";
        String history = "**用户**: 我最近面试又失败了\n**芒格**: 大脑在缺乏信息时会自动用最坏答案填空";

        String result = PersonaUtils.buildExportContent(context, history);

        // AGENTS.md 原文完整保留
        assertTrue(result.contains("33岁男性"), "应包含 AGENTS.md 原文-用户画像");
        assertTrue(result.contains("贵州人"), "应包含 AGENTS.md 原文-地区");
        assertTrue(result.contains("失业期心理震荡"), "应包含 AGENTS.md 原文-议题");
        assertTrue(result.contains("隐瞒家人"), "应包含 AGENTS.md 原文-细节");
        // 对话记录
        assertTrue(result.contains("**用户**"), "应包含对话记录");
        assertTrue(result.contains("面试又失败"), "应包含对话内容");
        assertTrue(result.contains("用最坏答案填空"), "应包含芒格回复");
    }

    @Test
    @DisplayName("buildExportContent → 有 AGENTS.md 无对话 → 只含 AGENTS.md")
    void exportWithContextOnly() {
        String context = "# AGENTS.md\n\n## 核心议题\n失业期心理震荡\n";
        String result = PersonaUtils.buildExportContent(context, "");

        assertTrue(result.contains("失业期心理震荡"));
        assertFalse(result.contains("对话导出"));
    }

    @Test
    @DisplayName("buildExportContent → 无 AGENTS.md 有对话 → 对话 + 标题")
    void exportWithHistoryOnly() {
        String history = "**用户**: 帮我分析\n**芒格**: 反过来想";
        String result = PersonaUtils.buildExportContent("", history);

        assertTrue(result.contains("**用户**"));
        assertTrue(result.contains("帮我分析"));
    }

    @Test
    @DisplayName("buildExportContent → 两者都空 → 提示信息")
    void exportBothEmpty() {
        String result = PersonaUtils.buildExportContent("", "");
        assertTrue(result.contains("无内容") || result.contains("尚未上传"));
    }

    @Test
    @DisplayName("buildExportContent → 大型 AGENTS.md 不被截断")
    void exportLargeContext() {
        String context = "# 大型文档\n\n" + "详细信息行\n".repeat(500);
        String result = PersonaUtils.buildExportContent(context, "");

        assertEquals(result.length(), context.length(), "大型文档应完整输出不被截断");
    }

    // ========== buildRefinePrompt ==========

    @Test
    @DisplayName("buildRefinePrompt → 有上下文和对话 → 两者都出现在 prompt 中")
    void refinePromptWithBoth() {
        String context = "## 用户画像\n33岁\n## 核心议题\n失业\n";
        String history = "user: 帮我\nassistant: 好的\n";
        String prompt = PersonaUtils.buildRefinePrompt(context, history);

        assertTrue(prompt.contains("33岁"), "prompt 应包含 AGENTS.md");
        assertTrue(prompt.contains("帮我"), "prompt 应包含对话");
        assertTrue(prompt.contains("=== 现有 AGENTS.md ==="), "应有 AGENTS.md 标记");
        assertTrue(prompt.contains("=== 新增对话 ==="), "应有对话标记");
    }

    @Test
    @DisplayName("buildRefinePrompt → 仅上下文 → 无对话标记")
    void refinePromptContextOnly() {
        String prompt = PersonaUtils.buildRefinePrompt("## 测试\n内容", "");
        assertTrue(prompt.contains("=== 现有 AGENTS.md ==="));
        assertFalse(prompt.contains("新增对话"));
    }

    @Test
    @DisplayName("buildRefinePrompt → 仅对话 → 应包含新增对话标记和保留要求")
    void refinePromptHistoryOnly() {
        String prompt = PersonaUtils.buildRefinePrompt("", "user: hi");
        assertTrue(prompt.contains("新增对话"), "应包含新增对话标记");
        assertTrue(prompt.contains("不得删除"), "应包含保留要求");
        assertTrue(prompt.length() > 50, "prompt 应有足够长度");
    }

    // ========== estimateTokens ==========

    @Test
    @DisplayName("estimateTokens → 空字符串/null 返回 0")
    void estimateTokensEmpty() {
        assertEquals(0, PersonaUtils.estimateTokens(""));
        assertEquals(0, PersonaUtils.estimateTokens(null));
    }

    @Test
    @DisplayName("estimateTokens → 中英混合")
    void estimateTokensMixed() {
        int tokens = PersonaUtils.estimateTokens("你好世界 Hello");
        assertTrue(tokens >= 3 && tokens <= 8);
    }

    // ========== compressMessages ==========

    @Test
    @DisplayName("compressMessages → 短消息不裁剪")
    void compressMessagesShort() {
        ArrayNode msgs = createMessages(2);
        ArrayNode result = PersonaUtils.compressMessages(msgs, 3, null);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("compressMessages → 保留最后 N 轮 + 归档提示")
    void compressMessagesLong() {
        ArrayNode msgs = createMessages(20); // 10 rounds
        ArrayNode result = PersonaUtils.compressMessages(msgs, 3, "【归档提示】对话已归档");
        assertEquals(7, result.size()); // 3 rounds × 2 + 1 archive note
        assertEquals("system", result.get(0).get("role").asText());
    }

    // ========== helpers ==========

    private ArrayNode createMessages(int count) {
        ArrayNode msgs = mapper.createArrayNode();
        for (int i = 0; i < count; i++) {
            ObjectNode msg = msgs.addObject();
            msg.put("role", i % 2 == 0 ? "user" : "assistant");
            msg.put("content", "消息 " + i);
        }
        return msgs;
    }
}
