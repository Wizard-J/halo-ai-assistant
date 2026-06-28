package io.codex.haloaiassistant.agent.confirmation;

/**
 * 风险等级枚举，用于标记 AI 工具操作的风险程度。
 * LOW 风险操作可直接执行，MEDIUM/HIGH 需要管理员确认。
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
