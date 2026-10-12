# ChatGPT 订阅接入

面向自行部署的开源 Halo AI Assistant，使用官方 Sign in with ChatGPT 授权及公开 Responses API。
符合条件的 ChatGPT Pro/Plus 账户授权后，可使用套餐额度；具体可用模型、限额及接入资格由 OpenAI 决定。
它不会读取 ChatGPT 聊天记录、记忆或自定义 GPT，也不将普通 API Key 费用抵扣到订阅。

## 本阶段功能

- 默认仍为原有 API Key 模式，原 API 地址、密钥和模型配置保留。
- 订阅模式支持普通对话，以及 `listArticles`、`getArticle`、`searchSiteArticles`、`createArticle`。
- Persona 的工具范围与上述范围取交集；非博客管理角色不能创建文章。
- 订阅模式的 `createArticle` 在服务端固定为草稿，只接收标题和正文；不会采用模型传入的发布状态、作者、分类、标签或内部跳过确认参数。
- 草稿先进入现有 pending action。确认前不创建 Post/Snapshot；确认后沿用现有内部执行器创建未发布草稿。取消、过期和重复确认沿用现有保护。
- 新请求必须收到 `response.completed` 才能执行本轮工具。网络断流、`response.failed`、`response.incomplete` 或无效事件都会停止，不执行本轮工具。
- 订阅对话工具仍不提供发布、删除、修改、自动标签或联网搜索。
- 定时自动运维支持订阅模式：后台仅请求纯文本内容，不向模型暴露任何管理工具；启用的配置授权服务端按时间执行、创建禁用登录的作者，并按 `autoPublish` 保存草稿或发布，质量检查失败降为草稿。聊天角色不会收到管理确认卡。
- 订阅生成错误、额度不足或 SSE 未完成均中止文章写入；不回退收费 API。每次计划运行先持久化执行时间，结果写入状态 ConfigMap 的 `lastRunResult`；失败当天不自动重试。
- 订阅协议不发送不支持的 `max_tokens` / `max_output_tokens`，配置中的输出预算通过提示词控制篇幅，不是硬性 token 上限。
- 不支持自动回退 API；需要手动切换接入方式。

## 1. 在带浏览器的本机完成授权

不要在云服务器直接运行登录命令。官方 OAuth 使用 `127.0.0.1` 回调，需要浏览器与授权助手位于同一台机器。

在插件仓库目录运行：

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/requirements-chatgpt.txt
.venv/bin/python scripts/chatgpt-login.py
```

浏览器打开 **Continue with ChatGPT** 流程。选择你的账户和工作区，并允许应用使用 ChatGPT 套餐。
助手会验证 state、PKCE、ID token 签名、issuer、audience、expiry、nonce 和订阅使用 scope。
通过验证后生成：

```text
~/.config/halo-ai-assistant/chatgpt-local/
  host.json
  registration.json
  credentials.json
```

每次重新授权复用签发的 client ID 和本机 host ID。连接其他账户时使用独立目录：

```sh
.venv/bin/python scripts/chatgpt-login.py --directory ~/.config/halo-ai-assistant/chatgpt-another-account
```

授权文件权限为 `0600`，目录为 `0700`。不要把文件内容复制到聊天、日志、Git、Halo ConfigMap 或浏览器存储。

## 2. 供后续部署时使用的服务器授权目录

**本次代码实现不包含上传、安装或生产站变更。以下步骤需要在允许部署后再执行。**

服务器运行目录由环境变量 `HALO_AI_CHATGPT_DIR` 指定。未指定时优先使用 `HALO_WORK_DIR` 下的 `.config/halo-ai-assistant/chatgpt`（Halo 官方容器为持久工作目录）；无该变量时使用 Halo Java 进程的：

```text
${user.home}/.config/halo-ai-assistant/chatgpt/
```

容器部署建议把独立持久目录挂载到容器内，例如 `/var/lib/halo-ai-chatgpt`，并令
`HALO_AI_CHATGPT_DIR=/var/lib/halo-ai-chatgpt`。目录归运行 Halo 的用户所有，权限 `0700`。
将本机生成的 **credentials.json** 通过安全通道（例如 SSH 文件传输）放入该目录，权限 `0600`。
不要复制本机的 `host.json`；服务器会首次生成并持久化自己的 host ID。已有服务器 `host.json` 不应覆盖。

导入后由服务器独占该 session 的 refresh token 续期。不要让本机和服务器同时续期同一份凭据；
不要把同一授权目录共享给多个 Halo 实例。第一阶段支持单实例：进程内串行续期、原子替换凭据，尚未实现跨进程文件锁。
服务器需要访问 `auth.openai.com` 和 `api.openai.com`。

## 3. 插件设置和连接检查

1. 插件 → 基本配置 → **AI 接入方式** 选择 **ChatGPT 订阅**。
2. **ChatGPT 订阅模型** 留空：查询当前账户的模型列表并使用第一个可见模型；也可填写查询出的 `slug`。
3. Console 插件对话页展开 **连接管理**；完整对话页点击页首 **接入状态**。
4. 点击 **检查连接（消耗少量订阅额度）**。只有完成真实短文本请求才显示“订阅请求已完成”。
5. 查看可用模型标识。需要调整模型时，在基本配置中填写对应标识并保存。

“已导入订阅授权”只说明本机文件结构和权限满足要求，不代表 OpenAI 已接受请求。
没有有效授权、未授予订阅使用权限、模型不可用或额度不足时显示错误，并停止订阅请求。

管理额度链接指向 ChatGPT Settings → Usage。断开授权会尝试撤销 renewable session，随后移除服务器本地凭据。
如果远程撤销未确认，界面明确提示前往 ChatGPT 设置断开应用。重新导入授权前无法继续调用。

## 4. 最小验收流程

在测试 Halo 实例或获准部署后的生产实例中：

1. 检查连接，确认短文本请求收到完成事件；在 ChatGPT 设置中核实该应用的实际使用状态。
2. 对话“你好，请说明你能做什么”，确认中文流式显示及 Persona 行为。
3. “列出文章”及“读取文章 ID … 的正文”，核对真实文章标题和正文。
4. “根据刚才的文章生成一篇新的草稿”：必须出现确认卡片，确认前文章列表没有新增文章。
5. 取消一个草稿提案，确认没有写入；重新生成后确认，核对仅新增一篇未发布草稿，重复确认不再新增。
6. 尝试发布、删除或传入 `status=published`：订阅工具范围不允许发布/删除，创建路径始终为草稿。
7. 断开授权后再发消息，应明确失败；切回 API Key，验证原方式仍可使用。

本地自动测试使用模拟的 OpenAI HTTP/SSE 和 Halo 存储，不代表已通过真实 Pro 账户授权或线上 Halo 验收。

## 本地验证

```sh
cd ui
npm install
npm run build
cd ..
JAVA_HOME=/path/to/jdk21 ./gradlew test
JAVA_HOME=/path/to/jdk21 ./gradlew clean build
.venv/bin/python -m unittest discover -s scripts -p 'test_*.py'
```

## 官方依据

- [接入范围与身份模型](https://developers.openai.com/siwc/token-sharing-open-source)
- [本机注册和登录](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [自托管 VM 授权迁移](https://developers.openai.com/siwc/token-sharing-open-source/self-hosted-vms)
- [模型列表与请求完成要求](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [接口预览限制](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
- [续期、撤销和凭据保护](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)

## 专用出站代理

仅 ChatGPT 订阅支持可选 HTTP CONNECT 代理。在授权目录创建 `proxy.json`，内容为
`{"host":"halo-chatgpt-proxy","port":7890}`，文件权限必须为 `0600`。
它同时用于模型、对话、授权续期及撤销，不影响 API Key 方式；配置错误会停止订阅请求。
配置按请求读取，无需重启插件。代理应处于仅 Halo 可访问的专用容器网络，不公开端口。
此配置不支持代理认证；不要把密码嵌入 host，公网代理须通过受保护的本地出口接入。
节点订阅、代理凭据和授权文件不得提交到仓库。生产环境只能由一个实例持有续期权。
