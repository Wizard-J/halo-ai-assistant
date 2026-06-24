# Halo AI Assistant

> Halo 2 博客 AI 助手插件 — 智能对话 + 三人物自动运维 + 每日推送。
> 技术栈：Java 17+ / Spring WebFlux / Reactor / Gradle 8.10 / Lombok

---

## 环境

| 项目 | 说明 |
|------|------|
| **JDK** | `/Users/zhangjianmin/.cache/codex-jdks/corretto-21/Contents/Home` (JDK 17.0.9 JBR) |
| **Gradle** | `./gradlew` (wrapper), 配置 `build.gradle` |
| **构建** | `JAVA_HOME=/Users/zhangjianmin/.cache/codex-jdks/corretto-21/Contents/Home ./gradlew clean build` |
| **产物** | `build/libs/halo-ai-assistant-<version>.jar` |
| **Halo 版本** | 2.22.5+ |
| **部署** | SSH 一键自动化（见下方「自动部署」） |

> **注意**：Codex CLI 内置 JDK 21 路径：`/Users/zhangjianmin/.cache/codex-jdks/corretto-21/Contents/Home`。构建时必须通过 `JAVA_HOME` 指定该路径，否则默认 JDK 为 Java 8 会导致构建失败。

---

## 目录结构

```
halo-ai-assistant/
├── AGENTS.md
├── README.md
├── build.gradle                 # 版本号 version = "x.yy", sourceCompatibility = JavaVersion.VERSION_21
├── gradle.properties            # version 重复声明
├── settings.gradle
│
├── src/main/
│   ├── resources/
│   │   ├── plugin.yaml                          # 插件元数据 name=ai-assistant
│   │   ├── extensions/settings.yaml             # 3 个配置组: basic/push/autoOps（三人物全部字段合并在 autoOps 组）
│   │   └── chat-page.html                       # 聊天页面（SSE 流式）
│   │
│   └── java/io/codex/haloaiassistant/
│       ├── HaloAiAssistantPlugin.java           # start()/stop() 生命周期
│       ├── config/
│       │   ├── PluginConfiguration.java         # Spring Bean：注册 RouterFunction + Tools
│       │   ├── AiAssistantSetting.java          # basic 组：API Key / 模型 / 页面标题/图标/问候语
│       │   └── AutoOpsSetting.java              # autoOps 组：三个人物的全部字段（含 secondaryEnabled/tertiaryEnabled）
│       ├── agent/
│       │   ├── Tool.java / ToolRegistry.java    # function calling 工具接口
│       │   ├── AgentService.java                # AI 对话引擎（SSE + function calling 循环，最多 30 轮；工具调用期间静默）
│       │   └── tools/
│       │       ├── ArticleTool.java             # 文章工具：CRUD + batchTagArticles + autoTagArticles
│       │       ├── CategoryTool.java
│       │       ├── TagTool.java
│       │       └── CommentTool.java
│       ├── persona/
│       │   ├── PersonaDefinition.java            # Persona 自定义扩展
│       │   ├── Conversation.java                 # 对话历史持久化
│       │   ├── PersonaService.java               # Persona CRUD + 上下文压缩
│       │   └── PersonaController.java            # Persona REST API
│       ├── endpoint/
│       │   └── AiChatEndpoint.java              # REST 路由：/chat, /tools, /chat-page, /auto-ops/test, /daily-push
│       └── autoops/
│           └── AutoOpsService.java              # @Scheduled 定时 + testNow 手动触发，三个人物管线
```

---

## 核心架构

```
┌─ 聊天页面 /api/ai-assistant/chat-page
│   ├── 标题/图标/欢迎语 可配置（AiAssistantSetting.pageTitle/pageIcon/greeting）
│   └── POST /api/ai-assistant/chat → AgentService.chat() → SSE 流式
│
├─ 自动运维（三人物管线）
│   ├── @Scheduled(fixedDelay=60s) → tick() → runIfDue()
│   │   ├── 人物一：巫师前沿站  → primaryRssSources + rssSources → 6:4 → 标签 "AI前沿"
│   │   ├── 【条件执行】书虫漫步 → secondaryRssSources → 标签 "人物传记, 每日好书"
│   │   └── 【条件执行】技术猎手 → tertiaryRssSources → 标签 "技术干货, 优质译文"
│   │
│   └── POST /api/ai-assistant/auto-ops/test → testNow()
│       ├── 纯异步：fireInBackground() 立即返回，不阻塞 HTTP
│       └── 仅对 enabled 的 Persona 启动后台任务
│
└─ 每日推送 GET /api/ai-assistant/daily-push → 推送今日/昨日文章摘要
```

### 三人物管线详细

| Persona | 作者 | 标签 | RSS 源（配置字段） | 评分 |
|---------|------|------|-------------------|------|
| 巫师前沿站 | wizard-frontier | AI前沿 | primaryRssSources / rssSources | 6:4 primary:secondary |
| 书虫漫步 | bookworm-wanderer | 人物传记, 每日好书 | secondaryRssSources | 全量, 最多5条 |
| 技术猎手 | tech-hunter | 技术干货, 优质译文 | tertiaryRssSources | 全量, 最多5条 |

各自独立的 processed 记录（90 天过期），互不干扰。

---

## 关键实现细节

### testNow() 异步机制（v2.24）
```java
// 后台启动，立即返回
fireInBackground("巫师前沿站", () -> runPrimaryPipeline(...));
if (secondaryEnabled) fireInBackground("书虫漫步", () -> runSecondaryPipeline(...));
if (tertiaryEnabled) fireInBackground("技术猎手", () -> runTertiaryPipeline(...));
// 返回: {success: true, message: "已启动: 巫师前沿站, 技术猎手", personas: [...]}
```
- `fireInBackground` 使用 `Mono.fromCallable.subscribeOn(boundedElastic).subscribe()`
- 结果通过 `log.info/log.error` 输出到服务器日志
- 不再阻塞 HTTP 响应，消除 `InterruptedException`

### RSS 抓取
- WebClient maxInMemorySize = 2MB, timeout = 5s
- 支持 RSS（`<item>`）和 Atom（`<entry>`）两种格式
- 每个源独立 `try-catch`，一个源失败不影响其他源
- 去重：同 URL 只保留一条（优先 primary）

### AI 生成
- `agentService.generateText(basic, systemPrompt, prompt, maxTokens).block()`
- Prompt 按 [主要]/[次要] 标注优先级
- 要求返回 JSON：`{"title":"...","content":"Markdown 正文"}`
- 解析失败时 fallback 为全文输出

### 配置组映射

| 配置组 | Java 类 | 用途 |
|--------|---------|------|
| `basic` | AiAssistantSetting | API Key, 模型, 页面标题/图标/欢迎语 |
| `push` | - | 推送密钥/渠道/Token/时段 |
| `autoOps` | AutoOpsSetting | 三人物全部字段（enabled/autoPublish/作者/RSS/标签/tokens） |



> **注意**：`secondaryOps` 和 `tertiaryOps` 是独立的 Settings 组，但 `AutoOpsService` 只从 `autoOps` 组读取。如果三个组拆开，`secondaryEnabled`/`tertiaryEnabled` 等字段将永远为 null。当前 AutoOpsSetting 所有字段归属于 `autoOps` 组。

---

## 🚀 自动部署

### 服务器信息
| 项目 | 值 |
|------|-----|
| **域名** | wizardj.cn |
| **Docker 容器** | `1Panel-halo-GOvD` |
| **插件目录** | `/root/.halo2/plugins/` |
| **SSH** | `root@wizardj.cn`（密钥 `~/.ssh/id_ed25519`） |

### 一键构建 + 部署
```bash
cd /Users/zhangjianmin/project/halo-ai-assistant &&   JAVA_HOME=/Users/zhangjianmin/.cache/codex-jdks/corretto-21/Contents/Home ./gradlew clean build &&   scp build/libs/halo-ai-assistant-2.24.0.jar root@wizardj.cn:/tmp/ai-assistant-latest.jar &&   ssh root@wizardj.cn \
    "docker exec 1Panel-halo-GOvD rm -f /root/.halo2/plugins/ai-assistant-2.24.0.jar && \
     docker cp /tmp/ai-assistant-latest.jar 1Panel-halo-GOvD:/root/.halo2/plugins/ai-assistant-2.24.0.jar && \
     docker restart 1Panel-halo-GOvD"
```

### 验证部署（查看服务器日志）
```bash
ssh root@wizardj.cn "docker logs --tail 50 1Panel-halo-GOvD 2>&1 | grep -iE '已注册工具|autoTag|已启动插件|Persona|error'"
```

### 关键设计决策

- **工具调用静默**：AI 调用工具时不再流式输出思考过程（`Flux.empty()`），只输出最终结果，避免用户看到 UUID 清单等冗长内容
- **系统 Prompt 硬规则**：打标签必须用 `autoTagArticles`，禁止 `listArticles` + `batchTagArticles` 手动分组
- **深度限制 30**：允许多轮工具调用链
- **maxTokens 16384**：适配 `deepseek-v4-flash` 模型的大输出窗口



### 页面标题/欢迎语替换逻辑

`AiChatEndpoint.handleChatPage()` 中对 HTML 字符串做三步替换：

```java
.replace("<title>AI 智能助手</title>", "<title>" + escapeHtml(title) + "</title>")
.replace("你好，我是 Halo AI 智能助手", escapeHtml(greeting))
.replace("AI 智能助手", escapeHtml(title));
```

1. **必须先用精确的完整匹配替换 greeting**（`你好，我是 Halo AI 智能助手`），再替换剩余的所有 `AI 智能助手`。
2. 如果顺序颠倒（先替换 `AI 智能助手`），会把 `你好，我是 Halo AI 智能助手` 破坏为 `你好，我是 Halo 老巫师`，导致第二步匹配失败。

### Persona 刷新和页面加载

- `chat-page.html` 中 `activePersona` 的 JS 初始默认值为 `displayName: 'AI 智能助手'`。
- 页面加载后 `loadPersonas()` 从 API 获取 Persona 列表，但**即使 savedId 匹配也不会更新 `activePersona` 的字段**。
- 修复：在 `loadPersonas()` 中缓存 Persona 列表后，立即用 API 数据刷新 `activePersona`（displayName、iconUrl、brandColor、greeting），然后调用 `updatePersonaUI()`。

### 头像图标兜底

- `getPersonaIconHtml()` 和 `renderPersonaAvatar()` 对 `iconUrl === null` 应返回 `ri-sparkling-2-fill` RemixIcon，而不是空白。
- Persona 菜单容器 `#personaList` 应显式设置 `display: flex; flex-direction: column` 确保垂直排列。

### 芒格头像替换

芒格 Persona 的 iconUrl 目前为 `ri-team-fill`。若用户上传真实图片，只需：
1. 将图片上传到服务器并通过 HTTP 可访问
2. 在 `PersonaService.initBuiltinPersonas()` 中或通过 API 更新 Munger Persona 的 `iconUrl` 为图片 URL


## 编码规范

- **Reactor 线程**：阻塞操作用 `Schedulers.boundedElastic()`
- **异常处理**：`.onErrorResume()` 兜底，`rootMessage()` 提取根因
- **Lombok**：`@Slf4j`, `@Data`, `@RequiredArgsConstructor`
- **命名**：camelCase，工具类以 `Tool` 结尾，配置类以 `Setting` 结尾

## Git 提交记录

```
ce4e546 v2.24: testNow 改为纯异步（fireInBackground）
bfeba86 v2.23: 多Persona并行管道、新闻评分重排序、代码清理
bb5499a v1.2.2: fix RSS fetch (2MB buffer, 5s timeout), AGENTS/README docs
8c1d74e feat: initial Halo AI assistant plugin
```
