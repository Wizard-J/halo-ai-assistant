<script lang="ts" setup>
import { inject, ref, Ref, onMounted } from "vue";
import type { Plugin } from "@halo-dev/console-shared";

const plugin = inject<Ref<Plugin | undefined>>("plugin");

interface ConfirmationInfo {
  id: string;
  title: string;
  summary: string;
  riskLevel: string;
  status: "pending" | "confirmed" | "cancelled";
  result?: string;
}

interface ChatMessage {
  role: "user" | "assistant";
  content: string;
  confirmation?: ConfirmationInfo;
}

const messages = ref<ChatMessage[]>([]);
const inputText = ref("");
const isStreaming = ref(false);
const history = ref<{ role: string; content: string }[]>([]);
let abortController: AbortController | null = null;
const sessionId = ref(localStorage.getItem("ai-assistant-session") || "console-" + Date.now());

onMounted(async () => {
  try {
    const resp = await fetch("/api/ai-assistant/me");
    const data = await resp.json();
    if (data.sessionId) {
      sessionId.value = data.sessionId;
      localStorage.setItem("ai-assistant-session", data.sessionId);
    }
  } catch (e) {
    // fallback: keep generated sessionId
  }
});

function parseConfirmation(content: string): ConfirmationInfo | undefined {
  const idMatch = content.match(/确认ID[：:]\s*`(confirm_[a-z0-9]+)`/);
  const titleMatch = content.match(/\*\*操作[：:]\*\*\s*(.+)/);
  const summaryMatch = content.match(/\*\*摘要[：:]\*\*\s*(.+)/);
  const riskMatch = content.match(/\*\*风险等级[：:]\*\*\s*(MEDIUM|HIGH)/);
  if (!idMatch) return undefined;
  return {
    id: idMatch[1],
    title: titleMatch ? titleMatch[1].trim() : "待确认操作",
    summary: summaryMatch ? summaryMatch[1].trim() : "",
    riskLevel: riskMatch ? riskMatch[1] : "HIGH",
    status: "pending",
  };
}

async function cancelConfirmation(msg: ChatMessage) {
  if (!msg.confirmation) return;
  try {
    const resp = await fetch("/api/ai-assistant/pending-actions/" + msg.confirmation.id + "/cancel", { method: "POST" });
    const data = await resp.json();
    msg.confirmation.status = "cancelled";
    msg.confirmation.result = data.success ? "操作已取消" : "取消失败";
  } catch (e: any) {
    msg.confirmation.status = "cancelled";
    msg.confirmation.result = "取消失败: " + e.message;
  }
}

async function confirmAction(msg: ChatMessage) {
  if (!msg.confirmation) return;
  try {
    const resp = await fetch("/api/ai-assistant/pending-actions/" + msg.confirmation.id + "/confirm", { method: "POST" });
    const data = await resp.json();
    msg.confirmation.status = "confirmed";
    msg.confirmation.result = data.message || (data.success ? "操作已执行" : "执行失败");
  } catch (e: any) {
    msg.confirmation.status = "confirmed";
    msg.confirmation.result = "执行失败: " + e.message;
  }
}

async function sendMessage() {
  const text = inputText.value.trim();
  if (!text || isStreaming.value) return;

  messages.value.push({ role: "user", content: text });
  history.value.push({ role: "user", content: text });
  inputText.value = "";
  isStreaming.value = true;
  abortController = new AbortController();

  try {
    const resp = await fetch("/api/ai-assistant/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        message: text,
        history: history.value.slice(0, -1),
        persona: "default",
        sessionId: sessionId.value,
      }),
      signal: abortController.signal,
    });

    if (!resp.ok) {
      const errorText = await resp.text();
      messages.value.push({ role: "assistant", content: "[请求失败] " + (errorText || "HTTP " + resp.status) });
      return;
    }

    const reader = resp.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let assistantMsg: ChatMessage = { role: "assistant", content: "" };

    messages.value.push(assistantMsg);

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: !done });
      assistantMsg.content = buffer;
      // 检测确认信息
      const conf = parseConfirmation(buffer);
      if (conf) {
        assistantMsg.confirmation = conf;
      }
    }

    if (buffer.trim()) {
      history.value.push({ role: "assistant", content: buffer });
    }
  } catch (error: any) {
    if (error.name !== "AbortError") {
      messages.value.push({ role: "assistant", content: "[请求失败] " + error.message });
    }
  } finally {
    isStreaming.value = false;
    abortController = null;
  }
}

function goToImmersive() {
  window.open("/api/ai-assistant/chat-page", "_blank");
}
</script>

<template>
  <div class="ai-assistant-tab">
    <div class="header">
      <div class="header-info">
        <h2>老巫师 AI 助手</h2>
        <p class="subtitle">
          在 Halo Console 中使用 AI 助手管理站点内容。高影响操作会先展示摘要并要求确认。
        </p>
      </div>
    </div>

    <div class="messages">
      <div v-if="messages.length === 0" class="welcome">
        <h2>你好，我是老巫师</h2>
        <p>我可以帮你管理文章、分类、标签和评论。</p>
      </div>

      <template v-for="(msg, i) in messages" :key="i">
        <!-- 普通消息 -->
        <div v-if="!msg.confirmation" class="message" :class="msg.role">
          {{ msg.content }}
        </div>

        <!-- 确认卡片 -->
        <div v-else class="confirmation-card">
          <div class="confirmation-header">
            <span class="confirmation-icon">⚠️</span>
            <span class="confirmation-title">待确认操作</span>
            <span class="confirmation-risk" :class="msg.confirmation.riskLevel.toLowerCase()">{{ msg.confirmation.riskLevel }}</span>
          </div>
          <div class="confirmation-body">
            <div class="confirmation-summary">{{ msg.confirmation.summary }}</div>
          </div>
          <div v-if="msg.confirmation.status === 'pending'" class="confirmation-actions">
            <button class="btn-cancel" @click="cancelConfirmation(msg)">取消</button>
            <button class="btn-confirm" @click="confirmAction(msg)">确认执行</button>
          </div>
          <div v-else class="confirmation-result">
            {{ msg.confirmation.result }}
          </div>
        </div>
      </template>
    </div>

    <div class="composer">
      <input
        v-model="inputText"
        placeholder="输入消息…"
        :disabled="isStreaming"
        @keydown.enter.prevent="sendMessage"
      />
      <button :disabled="isStreaming" @click="sendMessage">发送</button>
    </div>

    <div class="footer-link">
      <a href="#" @click.prevent="goToImmersive">
        <i class="ri-external-link-line"></i> 打开沉浸式模式
      </a>
    </div>
  </div>
</template>

<style scoped>
.ai-assistant-tab {
  display: flex;
  flex-direction: column;
  height: 600px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  overflow: hidden;
}
.header {
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
}
.header-info h2 {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 700;
}
.subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.welcome {
  text-align: center;
  padding: 40px 20px;
  color: var(--text-secondary);
}
.welcome h2 {
  margin: 0 0 8px;
  font-size: 18px;
  color: var(--text-color);
}
.welcome p {
  margin: 0;
  font-size: 14px;
}
.message {
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
  max-width: 85%;
}
.message.user {
  margin-left: auto;
  background: var(--primary-color);
  color: #fff;
}
.message.assistant {
  margin-right: auto;
  background: var(--surface-color);
  border: 1px solid var(--border-color);
}
.confirmation-card {
  border: 1px solid var(--danger-color, #e02424);
  border-radius: 8px;
  padding: 16px;
  margin: 4px 0;
  background: color-mix(in srgb, var(--danger-color, #e02424) 6%, transparent);
}
.confirmation-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  font-weight: 700;
  font-size: 14px;
}
.confirmation-icon {
  font-size: 18px;
}
.confirmation-title {
  flex: 1;
}
.confirmation-risk {
  font-size: 12px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 4px;
}
.confirmation-risk.high {
  color: var(--danger-color, #e02424);
  background: color-mix(in srgb, var(--danger-color, #e02424) 10%, transparent);
}
.confirmation-risk.medium {
  color: #d97706;
  background: color-mix(in srgb, #d97706 10%, transparent);
}
.confirmation-summary {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 12px;
}
.confirmation-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
.confirmation-actions button {
  padding: 6px 16px;
  border-radius: 8px;
  border: none;
  font-weight: 600;
  cursor: pointer;
}
.btn-cancel {
  border: 1px solid var(--border-color);
  background: var(--surface-color);
  color: var(--text-color);
}
.btn-confirm {
  background: var(--danger-color, #e02424);
  color: #fff;
}
.confirmation-result {
  font-size: 13px;
  color: var(--success-color, #16a34a);
  margin-top: 8px;
}
.composer {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--border-color);
  background: var(--surface-color);
  flex-shrink: 0;
}
.composer input {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  background: var(--bg-color);
}
.composer input:focus {
  border-color: var(--primary-color);
}
.composer button {
  padding: 8px 20px;
  border: none;
  border-radius: 8px;
  background: var(--primary-color);
  color: #fff;
  font-weight: 600;
  cursor: pointer;
}
.composer button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.footer-link {
  text-align: center;
  padding: 8px;
  border-top: 1px solid var(--border-color);
  flex-shrink: 0;
}
.footer-link a {
  font-size: 13px;
  color: var(--primary-color);
  text-decoration: none;
}
.footer-link a:hover {
  text-decoration: underline;
}
</style>
