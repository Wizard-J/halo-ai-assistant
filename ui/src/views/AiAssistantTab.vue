<script lang="ts" setup>
import { nextTick, onMounted, ref } from "vue";

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
const messagePane = ref<HTMLElement | null>(null);
let abortController: AbortController | null = null;
let streamBuffer = "";
const sessionId = ref(localStorage.getItem("ai-assistant-session") || "console-" + Date.now());
const assistantName = "老巫师";
const assistantAvatar = "/plugins/ai-assistant/assets/logo.png";

onMounted(async () => {
  try {
    const resp = await fetch("/api/ai-assistant/me");
    const data = await resp.json();
    if (data.sessionId) {
      sessionId.value = data.sessionId;
      localStorage.setItem("ai-assistant-session", data.sessionId);
    }
  } catch (e) {
    // Keep the generated session id when Console user info is unavailable.
  }
});

function scrollToBottom() {
  nextTick(() => {
    if (!messagePane.value) return;
    messagePane.value.scrollTop = messagePane.value.scrollHeight;
  });
}

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

function parseSseEvents(chunk: string, done = false) {
  streamBuffer += chunk;
  const events = streamBuffer.split(/\r?\n\r?\n/);
  streamBuffer = done ? "" : events.pop() || "";
  const dataParts: string[] = [];

  for (const event of events) {
    const dataLines = event
      .split(/\r?\n/)
      .filter(line => line.startsWith("data:"))
      .map(line => line.slice(5).replace(/^ /, ""));
    const data = dataLines.join("\n");
    if (data && data !== "[DONE]") dataParts.push(data);
  }

  if (done && streamBuffer.trim()) {
    const data = streamBuffer
      .split(/\r?\n/)
      .filter(line => line.startsWith("data:"))
      .map(line => line.slice(5).replace(/^ /, ""))
      .join("\n");
    if (data && data !== "[DONE]") dataParts.push(data);
    streamBuffer = "";
  }

  return dataParts.join("");
}

function normalizeAssistantContent(content: string) {
  const normalized = content
    .replace(/\r\n/g, "\n")
    .replace(/(^|[\n|])data:\s*/g, "$1")
    .replace(/([^\n])(\s*#{1,3}\s+)/g, "$1\n\n$2")
    .replace(/([^\n|])(\s+---+\s*)(?=\n|$)/g, "$1\n\n---\n")
    .replace(/([^\n])(\s*[-*]\s+(?:\*\*|[\u{1F300}-\u{1FAFF}]))/gu, "$1\n$2")
    .replace(/\n{3,}/g, "\n\n")
    .trimStart();

  return normalizeBrokenTableSeparators(normalized);
}

function normalizeBrokenTableSeparators(content: string) {
  const lines = content.split(/\n/);
  const out: string[] = [];

  const isSeparatorFragment = (line: string) => {
    const text = line.trim();
    return text.length > 0 && /^[|:\-\s]+$/.test(text);
  };
  const isPipeRow = (line: string) => {
    if (isSeparatorFragment(line)) return false;
    const cells = line.trim().replace(/^\||\|$/g, "").split("|");
    return cells.length >= 2 && cells.some(cell => cell.trim());
  };
  const columnCount = (line: string) => line.trim().replace(/^\||\|$/g, "").split("|").length;
  const isTableJunk = (line: string) => {
    const text = line.trim();
    if (!text) return false;
    if (isSeparatorFragment(text)) return true;
    if (!text.includes("|")) return false;
    return !isPipeRow(text);
  };
  const inferredHeaders = (cols: number) => {
    const defaults = ["序号", "文章标题", "状态", "最后更新时间"];
    return Array.from({ length: cols }, (_, index) => defaults[index] || ("列 " + (index + 1)));
  };
  const separatorFor = (cols: number) => "|" + Array.from({ length: cols }, () => "---").join("|") + "|";

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];

    if (isTableJunk(line)) {
      let cursor = i + 1;
      while (cursor < lines.length && lines[cursor].trim() && isTableJunk(lines[cursor])) {
        cursor += 1;
      }
      if (cursor < lines.length && isPipeRow(lines[cursor])) {
        const cols = Math.max(1, columnCount(lines[cursor]));
        out.push("|" + inferredHeaders(cols).join("|") + "|");
        out.push(separatorFor(cols));
        i = cursor - 1;
        continue;
      }
      continue;
    }

    if (!isPipeRow(line)) {
      out.push(line);
      continue;
    }

    let cursor = i + 1;
    const fragments: string[] = [];
    while (cursor < lines.length && lines[cursor].trim() && isSeparatorFragment(lines[cursor])) {
      fragments.push(lines[cursor]);
      cursor += 1;
    }

    if (fragments.length > 0 && cursor < lines.length && isPipeRow(lines[cursor])) {
      const cols = Math.max(1, columnCount(line));
      out.push(line);
      out.push(separatorFor(cols));
      i = cursor - 1;
      continue;
    }

    out.push(line);
  }

  return out.join("\n");
}

function escapeHtml(value: string) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function renderMarkdown(content: string) {
  const normalized = normalizeAssistantContent(content);
  const lines = normalized.split(/\n/);
  const html: string[] = [];
  let index = 0;
  let listOpen = false;

  const closeList = () => {
    if (listOpen) {
      html.push("</ul>");
      listOpen = false;
    }
  };

  const splitTableRow = (line: string) => line.trim().replace(/^\||\|$/g, "").split("|").map(cell => cell.trim());
  const isSeparatorFragment = (line: string) => {
    const text = line.trim();
    return text.length > 0 && /^[|:\-\s]+$/.test(text);
  };
  const isPipeRow = (line: string) => {
    if (isSeparatorFragment(line)) return false;
    const cells = line.trim().replace(/^\||\|$/g, "").split("|");
    return cells.length >= 2 && cells.some(cell => cell.trim());
  };
  const cleanHeader = (cell: string) => cell.replace(/^[^\p{L}\p{N}]+/u, "").trim() || cell.trim();
  const findNextTableLine = (from: number) => {
    let cursor = from;
    let skippedSeparator = false;
    while (cursor < lines.length && lines[cursor].trim() && isSeparatorFragment(lines[cursor])) {
      skippedSeparator = true;
      cursor += 1;
    }
    return { cursor, skippedSeparator };
  };
  const renderTable = (headers: string[], rows: string[][]) => {
    const columnCount = Math.max(headers.length, ...rows.map(row => row.length));
    const normalizedHeaders = Array.from({ length: columnCount }, (_, i) => cleanHeader(headers[i] || ""));
    const normalizedRows = rows.map(row => Array.from({ length: columnCount }, (_, i) => row[i] || ""));
    html.push("<div class=\"table-scroll\"><table><thead><tr>"
      + normalizedHeaders.map(cell => "<th>" + renderInline(cell) + "</th>").join("")
      + "</tr></thead><tbody>");
    normalizedRows.forEach(row => html.push("<tr>" + row.map(cell => "<td>" + renderInline(cell) + "</td>").join("") + "</tr>"));
    html.push("</tbody></table></div>");
  };

  while (index < lines.length) {
    const rawLine = lines[index];
    const line = rawLine.trimEnd();
    if (!line.trim()) {
      closeList();
      index += 1;
      continue;
    }

    if (isSeparatorFragment(line)) {
      closeList();
      index += 1;
      continue;
    }

    const next = findNextTableLine(index + 1);
    if (isPipeRow(line) && (next.skippedSeparator || isPipeRow(lines[next.cursor] || ""))) {
      closeList();
      const headers = splitTableRow(line);
      const rows: string[][] = [];
      index += 1;
      while (index < lines.length && lines[index].trim()) {
        const current = lines[index].trim();
        if (isSeparatorFragment(current)) {
          index += 1;
          continue;
        }
        if (!isPipeRow(current)) break;
        rows.push(splitTableRow(current));
        index += 1;
      }
      if (rows.length) {
        renderTable(headers, rows);
      } else {
        html.push("<p>" + renderInline(line) + "</p>");
      }
      continue;
    }

    const heading = line.match(/^(#{1,3})\s+(.+)$/);
    if (heading) {
      closeList();
      const level = Math.min(heading[1].length + 2, 4);
      html.push("<h" + level + ">" + renderInline(heading[2]) + "</h" + level + ">");
      index += 1;
      continue;
    }

    if (/^\s*[-*]\s+/.test(line)) {
      if (!listOpen) {
        html.push("<ul>");
        listOpen = true;
      }
      html.push("<li>" + renderInline(line.replace(/^\s*[-*]\s+/, "")) + "</li>");
      index += 1;
      continue;
    }

    closeList();
    html.push("<p>" + renderInline(line) + "</p>");
    index += 1;
  }

  closeList();
  return html.join("");
}

function renderInline(value: string) {
  return escapeHtml(value)
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    .replace(/`([^`]+)`/g, "<code>$1</code>");
}

function isLoginResponse(response: Response) {
  const contentType = response.headers.get("content-type") || "";
  const url = response.url || "";
  return response.status === 401
    || response.status === 403
    || (response.redirected && url.includes("/login"))
    || contentType.includes("text/html");
}

async function readError(response: Response) {
  if (isLoginResponse(response)) return "登录状态已过期，请重新登录后再试。";
  const text = await response.text();
  if (text.toLowerCase().includes("<html")) return "登录状态已过期，请重新登录后再试。";
  return text || "HTTP " + response.status;
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

function stopStreaming() {
  abortController?.abort();
}

async function sendMessage() {
  const text = inputText.value.trim();
  if (!text || isStreaming.value) return;

  const requestHistory = history.value.slice();
  const userMsg: ChatMessage = { role: "user", content: text };
  messages.value.push(userMsg);
  history.value.push({ role: "user", content: text });
  inputText.value = "";
  isStreaming.value = true;
  streamBuffer = "";
  abortController = new AbortController();
  scrollToBottom();

  const assistantMsg: ChatMessage = { role: "assistant", content: "" };

  try {
    const resp = await fetch("/api/ai-assistant/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        message: text,
        history: requestHistory,
        persona: "default",
        sessionId: sessionId.value,
      }),
      signal: abortController.signal,
    });

    if (!resp.ok || isLoginResponse(resp)) {
      messages.value.push({ role: "assistant", content: "[请求失败] " + await readError(resp) });
      return;
    }

    messages.value.push(assistantMsg);
    scrollToBottom();

    const reader = resp.body!.getReader();
    const decoder = new TextDecoder();

    while (true) {
      const { done, value } = await reader.read();
      const chunk = decoder.decode(value || new Uint8Array(), { stream: !done });
      const parsed = parseSseEvents(chunk, done);
      if (parsed) {
        assistantMsg.content = normalizeAssistantContent(assistantMsg.content + parsed);
        const conf = parseConfirmation(assistantMsg.content);
        if (conf) assistantMsg.confirmation = conf;
        scrollToBottom();
      }
      if (done) break;
    }

    if (!assistantMsg.content.trim()) {
      assistantMsg.content = "[错误] AI 服务没有返回内容，请稍后重试。";
    } else {
      history.value.push({ role: "assistant", content: assistantMsg.content });
    }
  } catch (error: any) {
    if (error.name !== "AbortError") {
      messages.value.push({ role: "assistant", content: "[请求失败] " + error.message });
    } else if (!assistantMsg.content) {
      messages.value.push({ role: "assistant", content: "已停止生成。" });
    }
  } finally {
    isStreaming.value = false;
    abortController = null;
    streamBuffer = "";
    scrollToBottom();
  }
}

function goToImmersive() {
  window.open("/api/ai-assistant/chat-page", "_blank");
}
</script>

<template>
  <section class="ai-assistant-tab">
    <header class="console-chat-header">
      <div class="header-title">
        <img class="avatar" :src="assistantAvatar" alt="" aria-hidden="true" />
        <div>
          <h2>{{ assistantName }}</h2>
          <p>Console 内容工作台</p>
        </div>
      </div>
      <button class="ghost-button" type="button" @click="goToImmersive">
        完整对话
      </button>
    </header>

    <div ref="messagePane" class="messages">
      <div v-if="messages.length === 0" class="welcome">
        <img class="welcome-mark" :src="assistantAvatar" alt="" aria-hidden="true" />
        <h3>今天要让老巫师处理什么？</h3>
        <p>可以查看文章、管理分类标签，或处理待审核评论。</p>
      </div>

      <article
        v-for="(msg, i) in messages"
        :key="i"
        class="message-row"
        :class="msg.role"
      >
        <div class="message-avatar" aria-hidden="true">
          <span v-if="msg.role === 'user'">你</span>
          <img v-else :src="assistantAvatar" alt="" />
        </div>

        <div
          v-if="!msg.confirmation"
          class="message-bubble"
          :class="{ markdown: msg.role === 'assistant' }"
        >
          <span v-if="msg.role === 'assistant' && !msg.content" class="typing">正在思考...</span>
          <span v-else-if="msg.role === 'user'">{{ msg.content }}</span>
          <span v-else v-html="renderMarkdown(msg.content)"></span>
        </div>

        <div v-else class="confirmation-card">
          <div class="confirmation-header">
            <span class="confirmation-icon" aria-hidden="true">!</span>
            <span class="confirmation-title">{{ msg.confirmation.title }}</span>
            <span class="confirmation-risk" :class="msg.confirmation.riskLevel.toLowerCase()">
              {{ msg.confirmation.riskLevel }}
            </span>
          </div>
          <div class="confirmation-summary">{{ msg.confirmation.summary }}</div>
          <div v-if="msg.confirmation.status === 'pending'" class="confirmation-actions">
            <button class="btn-cancel" type="button" @click="cancelConfirmation(msg)">取消</button>
            <button class="btn-confirm" type="button" @click="confirmAction(msg)">确认执行</button>
          </div>
          <div v-else class="confirmation-result">{{ msg.confirmation.result }}</div>
        </div>
      </article>
    </div>

    <form class="composer" @submit.prevent="sendMessage">
      <textarea
        v-model="inputText"
        rows="1"
        placeholder="输入消息..."
        :disabled="isStreaming"
        @keydown.enter.exact.prevent="sendMessage"
      />
      <button v-if="!isStreaming" class="send-button" type="submit" :disabled="!inputText.trim()">
        发送
      </button>
      <button v-else class="stop-button" type="button" @click="stopStreaming">
        停止
      </button>
    </form>
  </section>
</template>

<style scoped>
.ai-assistant-tab {
  display: grid;
  grid-template-rows: auto minmax(360px, 1fr) auto;
  min-height: 620px;
  max-height: calc(100vh - 220px);
  overflow: hidden;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
}

.console-chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border-bottom: 1px solid #eef0f3;
  background: #ffffff;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  object-fit: cover;
}

.header-title h2 {
  margin: 0;
  color: #111827;
  font-size: 16px;
  font-weight: 700;
  line-height: 1.35;
}

.header-title p {
  margin: 2px 0 0;
  color: #6b7280;
  font-size: 13px;
  line-height: 1.35;
}

.ghost-button,
.send-button,
.stop-button,
.confirmation-actions button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 34px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.ghost-button {
  padding: 0 12px;
  border: 1px solid #d9dee7;
  background: #ffffff;
  color: #374151;
}

.ghost-button:hover {
  background: #f9fafb;
}

.messages {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  overflow-y: auto;
  padding: 22px 24px;
  background: #fbfcfe;
}

.welcome {
  display: grid;
  justify-items: center;
  align-content: center;
  min-height: 260px;
  color: #6b7280;
  text-align: center;
}

.welcome-mark {
  width: 42px;
  height: 42px;
  margin-bottom: 12px;
  border-radius: 8px;
  object-fit: cover;
}

.welcome h3 {
  margin: 0 0 6px;
  color: #111827;
  font-size: 18px;
  font-weight: 700;
}

.welcome p {
  margin: 0;
  font-size: 13px;
}

.message-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
}

.message-row.user {
  flex-direction: row-reverse;
}

.message-avatar {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #eef2ff;
  color: #3730a3;
  font-size: 11px;
  font-weight: 700;
  overflow: hidden;
}

.message-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.message-row.user .message-avatar {
  background: #e0f2fe;
  color: #075985;
}

.message-bubble {
  min-width: 0;
  max-width: min(880px, 78%);
  padding: 10px 13px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
  color: #111827;
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.message-row.user .message-bubble {
  border-color: #2563eb;
  background: #2563eb;
  color: #ffffff;
}

.message-bubble.markdown :deep(p) {
  margin: 0 0 8px;
}

.message-bubble.markdown :deep(p:last-child) {
  margin-bottom: 0;
}

.message-bubble.markdown :deep(h3),
.message-bubble.markdown :deep(h4) {
  margin: 14px 0 8px;
  color: #111827;
  font-size: 14px;
  font-weight: 700;
}

.message-bubble.markdown :deep(h3:first-child),
.message-bubble.markdown :deep(h4:first-child) {
  margin-top: 0;
}

.message-bubble.markdown :deep(table) {
  min-width: 560px;
  width: 100%;
  table-layout: auto;
  border-collapse: collapse;
  font-size: 12px;
}

.message-bubble.markdown :deep(.table-scroll) {
  max-width: 100%;
  margin: 8px 0 10px;
  overflow-x: auto;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
}

.message-bubble.markdown :deep(th),
.message-bubble.markdown :deep(td) {
  padding: 7px 9px;
  border-bottom: 1px solid #e5e7eb;
  text-align: left;
  vertical-align: top;
  white-space: normal;
}

.message-bubble.markdown :deep(th) {
  background: #f8fafc;
  color: #475569;
  font-weight: 700;
}

.message-bubble.markdown :deep(tr:last-child td) {
  border-bottom: 0;
}

.message-bubble.markdown :deep(ul) {
  margin: 6px 0 10px;
  padding-left: 18px;
}

.message-bubble.markdown :deep(li) {
  margin: 4px 0;
}

.message-bubble.markdown :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: #f3f4f6;
  color: #374151;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}

.typing {
  color: #6b7280;
}

.confirmation-card {
  width: min(720px, 78%);
  padding: 14px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  background: #fff7f7;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.confirmation-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  color: #111827;
  font-size: 14px;
  font-weight: 700;
}

.confirmation-icon {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #dc2626;
  color: #ffffff;
  font-size: 12px;
}

.confirmation-title {
  flex: 1;
}

.confirmation-risk {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
}

.confirmation-risk.high {
  background: #fee2e2;
  color: #b91c1c;
}

.confirmation-risk.medium {
  background: #fef3c7;
  color: #92400e;
}

.confirmation-summary {
  margin-bottom: 12px;
  color: #4b5563;
  font-size: 13px;
  line-height: 1.55;
}

.confirmation-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.btn-cancel {
  padding: 0 13px;
  border: 1px solid #d1d5db;
  background: #ffffff;
  color: #374151;
}

.btn-confirm {
  padding: 0 13px;
  border: 1px solid #dc2626;
  background: #dc2626;
  color: #ffffff;
}

.confirmation-result {
  color: #15803d;
  font-size: 13px;
  font-weight: 600;
}

.composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid #eef0f3;
  background: #ffffff;
}

.composer textarea {
  width: 100%;
  min-height: 38px;
  max-height: 96px;
  resize: vertical;
  padding: 9px 12px;
  border: 1px solid #d9dee7;
  border-radius: 6px;
  background: #ffffff;
  color: #111827;
  font: inherit;
  font-size: 14px;
  line-height: 1.45;
  outline: none;
}

.composer textarea:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.send-button {
  min-width: 82px;
  padding: 0 16px;
  border: 1px solid #2563eb;
  background: #2563eb;
  color: #ffffff;
}

.send-button:disabled {
  border-color: #cbd5e1;
  background: #cbd5e1;
  cursor: not-allowed;
}

.stop-button {
  min-width: 72px;
  padding: 0 16px;
  border: 1px solid #d1d5db;
  background: #ffffff;
  color: #374151;
}

@media (max-width: 900px) {
  .ai-assistant-tab {
    min-height: 560px;
    max-height: none;
  }

  .messages {
    padding: 16px;
  }

  .message-bubble,
  .confirmation-card {
    max-width: 82%;
    width: auto;
  }
}
</style>
