<script lang="ts" setup>
import { onMounted, ref } from "vue";

const api = "/apis/console.api.wizardj-ai-assistant.plugin.halo.run/v1alpha1/chatgpt";
const provider = ref("api");
const account = ref("");
const connected = ref(false);
const message = ref("正在读取接入状态…");
const models = ref<{ slug: string; display_name: string }[]>([]);
const busy = ref(false);

async function request(path: string, method = "GET") {
  const response = await fetch(api + path, { method, cache: "no-store" });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || "无法读取订阅状态");
  return result;
}

async function refresh() {
  try {
    const result = await request("/status");
    provider.value = result.provider;
    connected.value = result.connected;
    account.value = result.account || "";
    message.value = result.message;
  } catch (error: any) { message.value = error.message; }
}

async function check() {
  busy.value = true;
  models.value = [];
  try {
    const result = await request("/check", "POST");
    message.value = result.message;
    if (result.success) models.value = (await request("/models")).models;
  } catch (error: any) { message.value = error.message; }
  finally { busy.value = false; }
}

async function disconnect() {
  if (!window.confirm("断开此服务器的 ChatGPT 授权？后续订阅请求将停止。")) return;
  busy.value = true;
  try {
    const result = await request("/disconnect", "POST");
    connected.value = false;
    models.value = [];
    message.value = result.message;
  } catch (error: any) { message.value = error.message; }
  finally { busy.value = false; }
}

onMounted(refresh);
</script>

<template>
  <details class="chatgpt-connection">
    <summary>{{ provider === 'chatgpt' ? '正在使用 ChatGPT 订阅' : '正在使用 API Key' }} · 连接管理</summary>
    <p role="status">{{ account ? account + ' · ' : '' }}{{ message }}</p>
    <p>首次连接：在本机运行授权助手（Continue with ChatGPT），安全导入服务器后，在插件基本配置中选择“ChatGPT 订阅”。</p>
    <div class="connection-actions">
      <a :href="api + '/setup'" target="_blank" rel="noopener noreferrer">授权说明</a>
      <a href="https://chatgpt.com/settings/usage" target="_blank" rel="noopener noreferrer">管理额度</a>
      <button type="button" :disabled="busy" @click="refresh">刷新状态</button>
      <button type="button" :disabled="busy || !connected" @click="check">{{ busy ? '处理中…' : '检查连接（消耗少量订阅额度）' }}</button>
      <button type="button" :disabled="busy || !connected" @click="disconnect">断开授权</button>
    </div>
    <ul v-if="models.length">
      <li v-for="model in models" :key="model.slug">{{ model.display_name }}：<code>{{ model.slug }}</code></li>
    </ul>
    <p v-if="models.length">可将模型标识填入插件基本配置的“ChatGPT 订阅模型”；留空使用列表中第一个模型。</p>
    <p>第一阶段支持对话、文章读取和待确认草稿。授权或额度异常时会停止，不自动切换到收费 API。</p>
  </details>
</template>

<style scoped>
.chatgpt-connection { padding: 12px 20px; border-bottom: 1px solid #e5e7eb; font-size: 13px; color: #475569; }
summary { cursor: pointer; font-weight: 600; }
p { margin: 8px 0; overflow-wrap: anywhere; }
.connection-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
a { color: #2563eb; }
button { border: 1px solid #cbd5e1; border-radius: 6px; padding: 5px 9px; background: white; }
button:disabled { opacity: .5; cursor: wait; }
ul { max-height: 160px; overflow: auto; padding-left: 20px; overflow-wrap: anywhere; }
</style>
