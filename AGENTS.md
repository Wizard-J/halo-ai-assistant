# Halo AI Assistant

> Halo 2 博客 AI 助手插件 — 智能对话 + 三人物自动运维 + 每日推送。
> 技术栈：Java 17+ / Spring WebFlux / Reactor / Gradle 8.10 / Lombok

---

## 环境

| 项目 | 说明 |
|------|------|
| **JDK** | `/Users/zhangjianmin/.cache/codex-jdks/corretto-21/Contents/Home` (JDK 17.0.9 JBR) |
| **Gradle** | `./gradlew` (wrapper), 配置 `build.gradle` |
| **构建** | `JAVA_HOME=/Applications/IntelliJ\ IDEA\ CE.app/Contents/jbr/Contents/Home ./gradlew clean build` |
| **产物** | `build/libs/halo-ai-assistant-<version>.jar` |
| **Halo 版本** | 2.22.5+ |
| **部署** | 1Panel → Halo → 插件 → 上传 jar |

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
│   │   ├── extensions/settings.yaml             # 5 个配置组: basic/push/autoOps/secondaryOps/tertiaryOps
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
│       │   ├── AgentService.java                # AI 对话引擎（SSE + function calling 循环，最多 5 轮）
│       │   └── tools/
│       │       ├── ArticleTool.java             # 文章 CRUD（含 tags 参数）
│       │       ├── CategoryTool.java
│       │       ├── TagTool.java
│       │       └── CommentTool.java
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
| `secondaryOps` | - | 第二人物 UI 表单（实际映射到 AutoOpsSetting 同名字段） |
| `tertiaryOps` | - | 技术干货 UI 表单（实际映射到 AutoOpsSetting 同名字段） |

> **注意**：`secondaryOps` 和 `tertiaryOps` 是独立的 Settings 组，但 `AutoOpsService` 只从 `autoOps` 组读取。如果三个组拆开，`secondaryEnabled`/`tertiaryEnabled` 等字段将永远为 null。当前 AutoOpsSetting 所有字段归属于 `autoOps` 组。

---

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
