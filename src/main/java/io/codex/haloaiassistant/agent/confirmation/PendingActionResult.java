package io.codex.haloaiassistant.agent.confirmation;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 高风险工具执行的返回结果，包含确认信息和操作摘要。
 * 当 {@link #requiresConfirmation} 为 true 时，前端应展示确认卡片。
 */
public class PendingActionResult {

    private boolean requiresConfirmation;
    private String confirmationId;
    private String title;
    private String summary;
    private String riskLevel;
    private JsonNode items;

    public PendingActionResult() {}

    public PendingActionResult(String confirmationId, String title, String summary,
                                String riskLevel, JsonNode items) {
        this.requiresConfirmation = true;
        this.confirmationId = confirmationId;
        this.title = title;
        this.summary = summary;
        this.riskLevel = riskLevel;
        this.items = items;
    }

    /** 创建一个确认结果。 */
    public static PendingActionResult forConfirmation(String id, String title,
                                                       String summary, String riskLevel,
                                                       JsonNode items) {
        return new PendingActionResult(id, title, summary, riskLevel, items);
    }

    // Getters
    public boolean isRequiresConfirmation() { return requiresConfirmation; }
    public String getConfirmationId() { return confirmationId; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public String getRiskLevel() { return riskLevel; }
    public JsonNode getItems() { return items; }

    // Setters for Jackson
    public void setRequiresConfirmation(boolean v) { this.requiresConfirmation = v; }
    public void setConfirmationId(String v) { this.confirmationId = v; }
    public void setTitle(String v) { this.title = v; }
    public void setSummary(String v) { this.summary = v; }
    public void setRiskLevel(String v) { this.riskLevel = v; }
    public void setItems(JsonNode v) { this.items = v; }
}
