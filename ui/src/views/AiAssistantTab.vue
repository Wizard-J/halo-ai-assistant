<script lang="ts" setup>
import { inject, ref, Ref, onMounted } from "vue";
import type { Plugin } from "@halo-dev/console-shared";

const plugin = inject<Ref<Plugin | undefined>>("plugin");

const messages = ref<string[]>([]);
const inputText = ref("");
const isStreaming = ref(false);
const history = ref<{ role: string; content: string }[]>([]);
let abortController: AbortController | null = null;
const sessionId = ref(localStorage.getItem("ai-assistant-session") || "console-" + Date.now());
const currentUserText = ref("");

// 页面加载时获取服务端 sessionId，保持跨页面一致性
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

async function sendMessage() {
  const text = inputText.value.trim();
  if (!text || isStreaming.value) return;

  currentUserText.value = text;
  messages.value.push("用户: " + text);
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
      messages.value.push("助手: [请求失败] " + (errorText || "HTTP " + resp.status));
      return;
    }

    const reader = resp.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let assistantMsg = "助手: ";

    messages.value.push(assistantMsg);

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: !done });
      messages.value[messages.value.length - 1] = "助手: " + buffer;
    }

    if (buffer.trim()) {
      history.value.push({ role: "assistant", content: buffer });
    }
  } catch (error: any) {
    if (error.name !== "AbortError") {
      messages.value.push("助手: [请求失败] " + error.message);
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

    <div class="messages" ref="messagesRef">
      <div v-if="messages.length === 0" class="welcome">
        <h2>你好，我是老巫师</h2>
        <p>我可以帮你管理文章、分类、标签和评论。</p>
      </div>
      <div v-for="(msg, i) in messages" :key="i" class="message" :class="{ user: msg.startsWith('用户'), assistant: msg.startsWith('助手') }">
        {{ msg }}
      </div>
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
