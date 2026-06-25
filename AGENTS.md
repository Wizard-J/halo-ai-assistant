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
| **部署** | ⚠️ 不推荐自动部署，见下方「⚠️ 部署方式说明」 |

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
│       │   ├── PersonaController.java            # Persona REST API
│       │   └── PersonaUtils.java                 # Token 估算/压缩 纯函数工具
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
│   │   ├── 【条件执行】书虫漫步 → secondaryRssSources → 标签 "知识卡片, 好书快读"
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
| 书虫漫步 | bookworm-wanderer | 知识卡片, 好书快读 | secondaryRssSources | 全量, 最多5条 |
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

## ⚠️ 部署方式说明（重要）

> **不推荐 Codex 自动部署**。之前尝试 SCP + Docker cp 自动部署，失败率很高（容器重启超时、健康检查过长、路由注册未生效等）。
> **推荐方式**：在本地构建 JAR，通过 **1Panel 后台 → 插件 → 上传 JAR** 手动上传部署。
> 
> Halo 2 有插件文件监听器，替换 JAR 后自动热加载（约 15-30s），**无需重启容器**。

### 服务器信息

| 项目 | 值 |
|------|-----|
| **域名** | wizardj.cn |
| **Halo 版本** | halohub/halo-pro:2.22.8 |
| **Docker 容器** | `1Panel-halo-GOvD` |
| **插件目录** | `/root/.halo2/plugins/` |
| **JAR 命名** | `ai-assistant-2.24.jar` |
| **SSH** | `root@wizardj.cn`（密钥 `~/.ssh/id_ed25519`） |

### 构建（本地 macOS）

```bash
cd /Users/zhangjianmin/project/halo-ai-assistant && \
JAVA_HOME=/Users/zhangjianmin/.cache/codex-jdks/corretto-21/Contents/Home ./gradlew clean build -x test
```

产物：`build/libs/halo-ai-assistant-2.24.0.jar`

### 部署（手动）

1. 构建后得到 `halo-ai-assistant-2.24.0.jar`
2. 登录 1Panel → 插件 → 上传 JAR 文件
3. Halo 自动热加载，无需重启

### 部署（命令行，可选）

> 仅在用户明确要求“命令行发布到 Halo 实例”时使用。优先走 Halo Console API 上传/升级插件，**不要**用 SCP、`docker cp` 或 `./gradlew reloadPlugin` 操作生产站。

前置条件：

- 在 Halo 后台创建/准备一个有插件管理权限的 Personal Access Token。
- 本地环境变量 `HALO_PAT` 保存该 Token。
- 目标插件已经安装时使用 `upgrade`；首次安装才使用 `install`。

```bash
cd /Users/zhangjianmin/project/halo-ai-assistant

VERSION="$(sed -n 's/^version=//p' gradle.properties)"
JAR="build/libs/halo-ai-assistant-${VERSION}.jar"
HALO_BASE_URL="https://wizardj.cn"
PLUGIN_NAME="ai-assistant"

JAVA_HOME=/Users/zhangjianmin/.cache/codex-jdks/corretto-21/Contents/Home ./gradlew clean build
test -f "$JAR"

# 已安装插件：升级/覆盖 JAR
curl -fsS -X POST \
  -H "Authorization: Bearer ${HALO_PAT}" \
  -F "file=@${JAR}" \
  "${HALO_BASE_URL}/apis/api.console.halo.run/v1alpha1/plugins/${PLUGIN_NAME}/upgrade"

# 首次安装插件才使用下面这个端点：
# curl -fsS -X POST \
#   -H "Authorization: Bearer ${HALO_PAT}" \
#   -F "file=@${JAR}" \
#   "${HALO_BASE_URL}/apis/api.console.halo.run/v1alpha1/plugins/install"
```

部署后验证：

```bash
curl -fsS \
  -H "Authorization: Bearer ${HALO_PAT}" \
  "${HALO_BASE_URL}/apis/plugin.halo.run/v1alpha1/plugins/${PLUGIN_NAME}" \
  | jq '{name: .metadata.name, enabled: .spec.enabled, version: .status.version, phase: .status.phase}'
```

如果 API 返回 401/403，说明 `HALO_PAT` 权限不足或过期；如果升级端点返回 404，先确认 `PLUGIN_NAME=ai-assistant` 是否已安装，未安装时改用 `plugins/install` 端点。

### 验证日志
```bash
ssh root@wizardj.cn "docker logs --tail 50 1Panel-halo-GOvD 2>&1 | grep -iE 'PersonaDefinition|已注册|已初始|AI 智能'"
```

### 关键设计决策

- **工具调用静默**：AI 调用工具时不再流式输出思考过程（`Flux.empty()`），只输出最终结果，避免用户看到 UUID 清单等冗长内容
- **系统 Prompt 硬规则**：打标签必须用 `autoTagArticles`，禁止 `listArticles` + `batchTagArticles` 手动分组
- **深度限制 30**：允许多轮工具调用链
- **maxTokens 16384**：适配 `deepseek-v4-flash` 模型的大输出窗口
- **思考短语**：每个 Persona 可配置 `thinkingPhrases`（JSON 数组），前端 SSE 流式等待时随机轮播显示，如芒格的「摘下眼镜…」「擦拭眼镜…」或老巫师的「挥动法杖」「念动咒语…」

### 单元测试

- **19 个测试，全部通过**：PersonaUtilsTest (12) + AutoOpsServiceTest (7)
- `PersonaUtilsTest`：纯函数测试（token 估算、压缩判断、消息裁剪），不依赖 Halo API
- `AutoOpsServiceTest`：Reactor 异常处理、null publisher 兜底、RSS 行解析、去重逻辑
- 运行：`JAVA_HOME=… ./gradlew test`

> **注意**：PersonaService 的 Halo Extension API 依赖（ReactiveExtensionClient）为 `compileOnly`，测试无法直接构造 Conversation/PersonaDefinition，纯逻辑已抽到 `PersonaUtils` 独立测试，集成测试通过服务端部署验证。

### 历史记录管理

- **混合存储**：历史记录列表存 `localStorage`（保证即时 UI 刷新），同时同步到服务端 API
- **删除/重命名**：先调服务端 API，同时更新 `localStorage`，双写保证一致性
- **跨浏览器**：服务端 `/conversations/current` 加载最新对话→消息恢复到页面
- **新建对话**：仅在前端初始化，首次发消息时才在服务端创建 Conversation
- **消息计数**：`toConversationSummary()` 正确解析 `spec.messages` JSON 数组计算 `messageCount`



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




### 直接更新数据库中的 Persona 记录

Persona 数据存储在 Halo 的 `extensions` 表中（`data` 列是 JSON）。如果 JAR 热加载后 Persona 未更新，可直接修改数据库：

```bash
# 查看当前 Persona
ssh root@wizardj.cn "docker exec 1Panel-mysql-Kqls mysql -uroot -pmysql_6fmwPP halo_3nc2dh \
  -e \"SELECT JSON_EXTRACT(CONVERT(data USING utf8mb4), '\$.spec.iconUrl') as iconUrl FROM extensions WHERE name = '/registry/ai-assistant.plugin.halo.run/personadefinitions/default';\""

# 更新 iconUrl
ssh root@wizardj.cn "docker exec 1Panel-mysql-Kqls mysql -uroot -pmysql_6fmwPP halo_3nc2dh \
  -e \"UPDATE extensions SET data = JSON_SET(CONVERT(data USING utf8mb4), '\$.spec.iconUrl', 'https://wizardj.cn/upload/xxx.png') WHERE name = '/registry/ai-assistant.plugin.halo.run/personadefinitions/default';\""
```

> 注意：直接 `docker cp` 文件到 `/root/.halo2/attachments/upload/` 目录后，文件可通过 `/upload/文件名` 访问，但不会出现在 Halo 附件管理页面中。


### 上下文压缩

`PersonaService.compressConversation()` 在每次对话前自动检查消息总量。当 Token 数超过模型窗口的 70% 时触发压缩，保留最后 3 轮对话，加上系统提示说明早期内容已归档。估算逻辑区分中文字符（1.5 字符/token）和 ASCII 字符（3.5 字符/token）。

```java
private static final int DEFAULT_MODEL_WINDOW = 64000;  // deepseek-v4-flash
private static final double TRIGGER_RATIO = 0.70;       // 70% 触发
private static final int RESERVED_ROUNDS = 3;           // 保留最近 3 轮
```

### 上下文上传（AGENTS.md）

Persona 支持上传上下文文件（如 AGENTS.md、prompt 指南等），内容会附加到 system prompt 的 `【上下文信息】` 段。

- **API**: `POST /api/ai-assistant/persona/{id}/context`（multipart file）
- **UI**: 角色切换菜单中"上传上下文 (AGENTS.md)"按钮
- **存储**: PersonaDefinition.spec.contextContent 字段，服务端持久化
- **限制**: 文件不超过 512KB，支持 .md/.txt/.adoc

上传后的上下文在下次对话时自动生效（刷新页面后加载新 system prompt）。

## 编码规范

- **Reactor 线程**：阻塞操作用 `Schedulers.boundedElastic()`
- **异常处理**：`.onErrorResume()` 兜底，`rootMessage()` 提取根因
- **Lombok**：`@Slf4j`, `@Data`, `@RequiredArgsConstructor`
- **命名**：camelCase，工具类以 `Tool` 结尾，配置类以 `Setting` 结尾

## Git 提交记录

```
b27ff37 fix: 历史记录/删除/重命名全部改为 localStorage 操作，保证实时刷新
efc39ab fix: listConversations 改为全量拉取再内存过滤，避免 Halo 谓词过滤问题
907a520 fix: 历史记录改为从服务端获取，删除/重命名后刷新
6ec9dac feat: 历史记录支持删除和重命名对话
6fe8c48 fix: 流式返回结束时保存助手回复，刷新后对话记录完整
f00079b fix: 先 loadPersonas 恢复角色再加载服务端对话
42bc124 fix: 刷新页面自动恢复服务端最新对话（无需进历史记录）
3783b91 fix: Conversation.messages 改为 String 类型，修复 Halo 扩展验证错误
155b894 fix: 变量声明顺序修正、session 异步初始化
02a21c6 feat: 服务端 session（用 Halo 登录用户名关联对话）
155b894 feat: 后端 Persona 系统 + 对话持久化 + 上下文压缩
ce4e546 v2.24: testNow 改为纯异步（fireInBackground）
bfeba86 v2.23: 多Persona并行管道、新闻评分重排序、代码清理
bb5499a v1.2.2: fix RSS fetch (2MB buffer, 5s timeout), AGENTS/README docs
8c1d74e feat: initial Halo AI assistant plugin
```



## 上下文提炼与导出

### 设计原则

- **AGENTS.md 保留不动** - 已上传的内容不过大模型
- **只提炼增量对话** - 从 `refinedMessageCount` 开始只处理新增的消息
- **AI 合并到对应章节** - 分析新对话找出新洞察，插入 AGENTS.md 对应章节
- **返回完整合并文档** - 保存回服务端

### 导出流程（单按钮）

```
点击导出 → 自动提炼（有新对话则 AI 合并到 AGENTS.md）→ 返回合并后的 AGENTS.md
```

**不追加原始对话**到导出文件末尾。

### 相关 API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/ai-assistant/persona/{id}/context/download?sessionId=xxx` | GET | 先自动提炼，再返回合并后的 AGENTS.md |
| `/api/ai-assistant/persona/{id}/context/refine?sessionId=xxx` | POST | 仅触发提炼，不下载 |
| `/api/ai-assistant/persona/{id}/context` | POST | 上传 AGENTS.md（multipart） |

### 导出 vs 提炼

- **导出** = 自动提炼（如果🈶新对话）→ 只返回合并后的 AGENTS.md（不追加原始对话）
- **提炼** = 将新对话中的洞察由 AI 合并到 AGENTS.md 对应章节

### Conversation.refinedMessageCount

`Conversation.ConversationSpec` 中的 `refinedMessageCount` 字段记录已提炼的消息条数。从 0 递增，下次 refine 只处理该索引之后的新消息。
## 文件编辑注意事项（积累教训）

### Python 脚本编辑 Java 文件易出错

- 三引号字符串内的 `\\"` 和 `\n` 转义很容易出错
- `content.replace()` 是全局替换，可能误改不期望的位置
- 建议：逐个方法修改，每次修改后立即编译验证

### PersonaController 布局

`PersonaController.java` 中的方法分为三组：
1. **routes** - `endpoints()` 方法中的路由注册
2. **handlers** - 各 `handleXxx()` 方法（包括 handleDownloadContext、handleRefineContext、handleUpdatePersona）
3. **data conversion** - `toPersonaSummary()` / `toConversationSummary()`

新增 endpoint 时必须**同时**添加 route 和 handler。

### CompileJava 类型推断问题

Spring WebFlux 的 `flatMap` 链中 lambda 参数类型容易推断失败：
- `.flatMap(persona -> toPersonaSummary(persona))` → 编译器无法推断 persona 类型
- 修复：显式指定 `.flatMap((PersonaDefinition persona) -> toPersonaSummary(persona))`
- 或使用 `toPersonaSummary((PersonaDefinition) persona)`

## 扩展索引注册（重要！）

**问题**：Halo 2.22.x 中 `schemeManager.register(Class)` 的简写方式不会自动创建扩展索引，导致 `client.fetch()`/`client.update()` 操作报错："No indices found for type"。

**修复方案**：使用 `schemeManager.register(Class, Consumer<IndexSpecs>)` 显式注册 `metadata.name` 索引：

```java
schemeManager.register(PersonaDefinition.class, indexSpecs -> {
    indexSpecs.add(
        IndexSpecs.<PersonaDefinition, String>single("metadata.name", String.class)
            .indexFunc(p -> p.getMetadata().getName())
            .unique(true)
            .nullable(false)
            .build()
    );
});
```

**如果新增自定义扩展类**，必须检查：
1. 确保 `@GVK` 注解正确
2. 如果通过 `client.fetch()`/`get()`/`update()` 访问，必须显式注册 `metadata.name` 索引
3. 如果只通过 `client.list()`（带谓词过滤）访问，基本注册（`schemeManager.register(Class)`）可能够用

## 上下文导出功能

- **原始导出**：`GET /api/ai-assistant/persona/{id}/context/download?sessionId=xxx`
  - 返回 AGENTS.md + 对话历史的拼接内容（纯文本 markdown）
  - 不经过大模型处理，不消耗 API 额度
- **导出需包含**：原始 AGENTS.md 内容 + 完整对话历史
- **导出不应包含**：Skill 内容（SKILL.md）、系统提示词

## ⚠️ 重要：服务器操作守则

**未经用户明确要求，严禁擅自修改服务器内容。** 包括但不限于：

- 修改数据库（MySQL 中的 extensions 表等）
- 修改 Halo 主题配置（ConfigMap）
- 修改站点设置（标题、描述、头像等）
- 修改用户信息
- 执行任何 DDL/DML 语句

即使操作看起来"无害"（如改个头像、关个功能），也必须先**询问用户**并获得确认后再执行。



### 前端 localStorage 缓存注意事项

历史记录删除"幽灵复活"修复记录：

1. **Session 缓存移除**：删除了 `saveConversationPerSession()` / `loadSessionConversation()` 函数，页面刷新后完全依赖服务端 `GET /conversations/current` 恢复对话
2. **`renderHistoryList` 回退逻辑修复**：只在服务端明确返回空列表时（非 500 错误）才回退到 localStorage。服务端 500 时显示空列表不再回退
3. **单缓存源原则**：历史记录只存在一个 localStorage key（`halo-ai-conv-{persona}-v1`），删除逻辑清晰，不再有 session 缓存的残留问题

### Conversation 扩展索引注册

**问题**：`Conversation` 最初使用 `schemeManager.register(Conversation.class)`（简写方式），在 Halo 2.22.x 上不会自动创建索引，导致 `client.get()`/`delete()`/`listAll()` 全部报错 "No indices found"。

**修复**：添加 `registerConversationWithIndex()` 方法，使用 `schemeManager.register(Conversation.class, indexSpecs -> ...)` 显式注册 `metadata.name` 索引。

**重要**：如果修复后索引仍不生效（旧 scheme 缓存问题），需修改 Conversation 的 GVK version（如从 v1alpha1 → v1alpha2）强制 Halo 创建新 scheme。

#### Conversation 全链路错误兜底

下列方法均添加了 `onErrorResume` 容错，确保即使索引问题未完全解决也不会报 500：

| 方法 | 兜底行为 |
|------|----------|
| `listConversations()` / `handleListConversations` | 返回空列表 |
| `deleteConversation()` / `handleDeleteConversation` | 静默成功 |
| `appendMessages()` | 跳过保存，返回原对话 |
| `updateConversationTitle()` | 静默成功 |
| `getOrCreateConversation()` (chat 端点) | 使用 `createFallbackConversation()` 直接创建 |
| `saveConversationMessages()` (chat 端点) | 跳过保存 |

### 教训记录

1. **2026-06-25**：未经确认就修改了 theme-jyf 主题的 ConfigMap（试图关闭热力图），导致 `JSON_OBJECT()` 将原本应该是 JSON 字符串的字段写成了 JSON 对象，页面 500 崩溃。最终通过 Python 生成正确结构的 JSON 并用 `UNHEX()` 修复。
2. **教训**：Halo 的 extensions 表中 `data` 列存储的是序列化的 Halo Extension JSON，其中 ConfigMap 的 `data` 字段值必须是 JSON **字符串**（即双转义），而不是 JSON **对象**。用 `JSON_SET()` / `JSON_OBJECT()` 直接操作极易破坏数据结构。

---

## 2026-06-25 Conversation → ConversationRef 重构

### 问题背景
`Conversation` 自定义扩展（kind=`Conversation`, version=`v1alpha2`）在 Halo 2.22.x 上存在索引缓存问题。旧 scheme 被缓存后无法覆盖，`client.get()`/`listAll()` 报错 "No indices found"，即使显式注册 `metadata.name` 索引也无效。

### 解决方案
创建全新的扩展类型 `ConversationRef`（kind=`ConvRef`, group=`ai-assistant.plugin.halo.run`, version=`v1alpha1`），Halo 从未注册过该 kind，索引正常工作。

### 变更文件
- **新文件**：`ConversationRef.java` — 全新的 ConvRef 扩展，包含 `ConvRefSpec`（sessionId, personaId, title, messages, timestamps, compressed, summary, refinedMessageCount）
- **修改**：`PersonaService.java` — 所有 `Conversation` 引用改为 `ConversationRef`
  - `getOrCreateConversation()` 返回 `Mono<ConversationRef>`
  - `parseMessages()` 接受 `ConversationRef`（新增 `parseConvRefMessages()` 别名）
  - `deleteConversation()` 使用 `ConversationRef.class`
  - 移除 `addConversationRef()` / `removeConversationRef()` / `removeConversationNameFromPersona()` 死代码
  - `ConversationWithUnrefined` record 改用 `ConversationRef`
  - `refineContext()` 使用 `ConversationRef`
- **修改**：`PersonaController.java` — `toConversationSummary()` 接受 `ConversationRef`
- **修改**：`AiChatEndpoint.java` — 移除 `Conversation` 导入
- **保留**：`Conversation.java` 扩展类保留（GVK 不同不冲突，旧数据可读）

### 构建命令
```bash
JAVA_HOME=/Users/zhangjianmin/.cache/codex-jdks/corretto-21/Contents/Home ./gradlew clean build
```
产物：`build/libs/halo-ai-assistant-*.jar`

### 部署方式（手动上传到 1Panel）
1. 构建成功后，产物在 `build/libs/halo-ai-assistant-<version>.jar`
2. 登录 1Panel → 容器管理 → 1Panel-halo-GOvD → 插件管理
3. 上传 JAR 覆盖，约 15-30s 热加载生效
4. **勿自动部署** — 用户偏好手动上传

### 重要：全局会话隔离
- 会话粒度 = `sessionId`（固定为 `user-{username}`），同一用户跨浏览器/设备共享会话
- `sessionId` **不依赖** localStorage，纯服务端隔离
- 不同用户之间的 `sessionId` 不同，上下文天然隔离

---

## 复盘：Conversation 索引反复坏掉的原因

### 现象
- 对话历史记录为空（`{"conversations":[]}`）
- 删除不生效
- 服务器报 "No indices found for type: Conversation"

### 修复历史（3次失败 → 最终解决）

| 时间 | 尝试 | 结果 |
|------|------|------|
| 第一次 | `schemeManager.register(Conversation.class)` 自动注册 | ❌ Halo 2.22.x 简写方式不创建索引 |
| 第二次 | 加 `registerConversationWithIndex()` 显式注册 `metadata.name` 索引 | ❌ 旧 scheme 已被缓存，新索引注册被静默忽略 |
| 第三次 | 创建 `ConversationRef` 新 kind，半重构 | ❌ 编译不过（方法体未完成更新） |
| 第四次（本次） | 完成 ConvRef 全链路重构 | ✅ 全新 kind，新 scheme + 全新索引 |

### 根本原因

**Halo 2.22.x 的 `SchemeManager` 存在 scheme 缓存不可覆写的限制：**

1. 插件第一次启动时，用 `@GVK` 注解自动注册了 `Conversation`（version=`v1alpha2`），Halo 创建了 scheme 但没有索引
2. 后续插件更新时，`schemeManager.register(Conversation.class, indexSpecs -> ...)` 不会再重新创建 scheme——缓存中的旧 scheme 没有索引，新注册的索引定义被忽略
3. 因此 `client.fetch()` / `client.get()` / `client.delete()` 全部报 "No indices found"
4. `client.listAll()` 带谓词过滤的可工作，但 `client.get()` 依赖 `metadata.name` 索引

**为什么修改 GVK version（v1alpha1 → v1alpha2）也不行？** 因为在 Halo 的 scheme 缓存中，group + kind 是主键，不是 version。修改 version 不会触发 scheme 重建。

**最终解决方案只能是创建全新的扩展 kind（ConvRef）**，Halo 从未见过这个 kind，会创建全新的 scheme + 索引。

### 教训
1. **不要在已有 scheme 上修索引**——Halo 2.22.x 不支持
2. **新增扩展 kind 时，必须一步到位注册好索引**，否则后续无法补充
3. **部署后确认日志**：检查 `Start to initialize indices for type: ConvRef` 和 `Total indexed count` 是否正常
4. **不要在已有数据的 kind 上改索引策略**，要创建新 kind 并迁移或重建

---

## 🔬 全量 Bug 复盘与质量保障

### Bug 清单（2026-06-24 ~ 06-25）

| # | 现象 | 根因 | 分类 |
|---|------|------|------|
| 1 | `No indices found for type: Conversation` | Halo 2.22.x scheme 缓存不可覆写，已有 kind 补索引无效 | **基础设施** |
| 2 | `No indices found for type: PersonaDefinition` | 同上，GVK version 混乱（v1alpha1 ↔ v1alpha2）导致资源不可访问 | **基础设施** |
| 3 | 新对话消息不持久化 | `createConversation().thenReturn(ref)` 返回无 version 的 ref，后续 update 静默失败 | **数据一致性** |
| 4 | 新建对话的消息追加到旧对话 | `getOrCreateConversation()` 总是返回已有对话，不理解前端的"新建对话"概念 | **架构设计** |
| 5 | 新建对话不在历史记录中 | 同 #4 + 前端 `saveConversation` 只写 localStorage | **架构设计** |
| 6 | AI 回复不保存到服务端 | 两次 `appendMessages(conv, …)` 异步竞争同一个 ref 的乐观锁 | **并发安全** |
| 7 | 点击历史记录不回显消息 | 历史列表 API 不返回 messages，loadConversation 期望 messages 在对象内 | **接口契约** |
| 8 | thinkingPhrases 一直显示"正在连接 AI 服务…" | loadPersonas() 漏掉了 thinkingPhrases 字段传播 | **前端状态同步** |
| 9 | 切换 Persona 后刷新回退到老巫师 | localStorage 和 activePersona 初始值不匹配 | **前端状态同步** |

### 根因分类

#### A. Halo 基础设施陷阱

- **scheme 缓存不可覆写**：某个 GVK 一旦注册，修改索引定义或 version 都不会重建 scheme
- **对策**：新 kind 一步到位注册索引（`schemeManager.register(Class, Consumer<IndexSpecs>)`），不存在后续补索引的情况
- **出问题时**：只能创建**全新的 kind**（如 ConvRef），永远不要在已注册的 kind 上修修补补

#### B. 数据一致性

- `client.create(ref)` 必须返回服务器返回的对象（含 version），`.thenReturn(ref)` 是 bug
  - **规则**：永远用 `.map(created -> created)`，永不 `.thenReturn(ref)`
- `client.update(ref)` 依赖 version 做乐观锁，无 version 时**静默失败**（不抛异常）
  - **这对 Halo 扩展 API 的反直觉行为，是最容易埋坑的地方**

#### C. 并发安全

- **两个异步调用不能在同一个 ref 对象上竞争更新**
  - 同一个 `conv` 的 `appendMessages` 只能调用一次
  - 如需多次追加，要么等第一次完成后再追加，要么合并为一次调用
  - **规则**：Mono 链中同一对象最多有**一个** `.subscribe()` 做持久化

#### D. 接口契约

- 前端用到的 API 返回结构必须包含足够的字段来满足前端逻辑
- 修改 API 返回字段时**必须同步检查前端调用侧**
- 关键 API 契约：
  - `/conversations` → `[{id, title, updatedAt, messageCount}]`
  - `/conversations/current` → `{id, title, messages, updatedAt}`
  - `/conversations/{convId}` → `{id, title, messages, updatedAt}`
  - `/personas` → `[{id, displayName, iconUrl, brandColor, greeting, thinkingPhrases}]`

#### E. 前后端状态同步

- `activePersona` 的完整字段 MUST 都从 API 传播
- 初始值不能有遗漏字段（否则竞态）
- localStorage 不能作为唯一数据源（跨设备问题）

---

### 改动前检查清单

每次修改代码前，问自己：

```
□ 这个改动会不会影响 ConversationRef 的创建/更新/删除流程？
□ 是否引入了新的 client.create()/.update()/.delete() 调用？
□ 有没有 .thenReturn(ref) 或 .subscribe() 的异步持久化？
□ 是否在同一个对象上做了多次异步更新？
□ 修改 API 返回字段后，前端对应的 fetch 调用是否有同步更新？
□ 新增字段是否在前后端都完整传播？
□ 新增 kind 有没有一步到位注册索引？
```

### 测试保护

现有 PersonaServiceTest 覆盖：
- createConversation → 返回 server-created ref（含 version）
- appendMessages → 消息合并、title 更新、null-safe
- compressConversation → 短对话不压缩、长对话触发滑动窗口
- listConversations → session+persona 过滤
- deleteConversation → 正常删除、不存在静默处理

**新增测试的时机**：修改了以上任何方法，必须先跑 `./gradlew test`。

---

## 开发工作流

### 提交纪律

每次功能修改完成后，必须：
1. `git add -A && git commit -m "说明本次改动"`
2. `git push`
3. 确保当前 commit 是可回滚的稳定版本

### 回滚策略

```bash
# 查看最近提交
git log --oneline -10

# 回滚到上一个稳定版本（保留工作区修改）
git revert HEAD --no-edit

# 或者硬回滚（丢弃工作区修改）
git reset --hard HEAD~1
```

### 改动前必做

1. ✓ 确保当前工作区干净（`git status` 无未提交修改）
2. ✓ 记录当前 HEAD：`git rev-parse HEAD`
3. 改动完成后提交并推送
4. 如改坏，`git revert HEAD` 回滚到改动前

---

## 插件发布注意事项

### 命令行发布流程（GitHub Release）

> **重要区别**：`./gradlew reloadPlugin` 是 `run.halo.plugin.devtools` 提供的本地开发热重载任务，只用于本机 `haloServer`/开发容器，不是生产站部署或插件市场发布命令。本仓库目前没有 Gradle `publish`/`release` 任务。

#### 正确的命令行发布命令

发布公开下载版本时，先构建 JAR，再用 GitHub CLI 创建 Release 并上传 JAR 制品：

```bash
cd /Users/zhangjianmin/project/halo-ai-assistant

VERSION="$(sed -n 's/^version=//p' gradle.properties)"
JAR="build/libs/halo-ai-assistant-${VERSION}.jar"

JAVA_HOME=/Users/zhangjianmin/.cache/codex-jdks/corretto-21/Contents/Home ./gradlew clean build
test -f "$JAR"

git status --short
git push

gh release create "v${VERSION}" "$JAR" \
  --target "$(git rev-parse HEAD)" \
  --title "v${VERSION}" \
  --generate-notes
```

如果同版本 Release 已存在、只需要替换制品：

```bash
VERSION="$(sed -n 's/^version=//p' gradle.properties)"
JAR="build/libs/halo-ai-assistant-${VERSION}.jar"
gh release upload "v${VERSION}" "$JAR" --clobber
```

#### Halo 应用市场发布

官方流程不是本地 Gradle 命令：

1. 首次上架：到 Halo 官网开发者后台创建应用、创建版本、上传 JAR、提交审核。
2. 后续版本：可在应用管理页面手动发布版本并上传 JAR。
3. 如要 GitHub Release 后自动同步到应用市场，需要配置 CI/CD：设置应用市场 `app-id`，创建 Halo 官网个人令牌并保存为 GitHub Secret `HALO_PAT`。本仓库当前还没有这套工作流。

官方文档：<https://docs.halo.run/developer-guide/app-store/publish-app>

#### 生产站部署仍然手动

WizardJ 生产站默认仍按前文“部署方式说明”：本地构建 JAR 后，通过 1Panel/Halo 插件管理上传。若用户明确要求命令行部署，使用 Halo Console API 的 `plugins/{name}/upgrade` 上传 JAR；不要用命令行 SCP、`docker cp` 或 `reloadPlugin` 去操作生产站。

### 硬编码的环境依赖（必须修改）

以下内容与 WizardJ 的服务器绑定，其他用户部署时会失效：

| 文件 | 内容 | 当前状态 |
|------|------|----------|
| ~~`PersonaService.java`~~ | ~~`iconUrl: "https://wizardj.cn/upload/munger.jpeg"`~~ | ✅ 已改为 `ri-team-fill`（RemixIcon 类名），无需外部图片 |
| ~~`PersonaService.java`~~ | ~~`iconUrl: "https://wizardj.cn/upload/sage-avatar.png"`~~ | ✅ 已改为 `ri-sparkling-2-fill`（RemixIcon 类名），无需外部图片 |
| `extensions/settings.yaml` | `pageIcon` 默认值 `https://wizardj.cn/upload/wizard-avatar.png` | ✅ 已改为 `ri-sparkling-2-fill`，新用户开箱即用 |
| `chat-page.html` | `document.getElementById('personaManagerClose')` 因脚本在 HTML 前执行返回 null | ✅ 已包裹 `DOMContentLoaded` 并加 null 保护 |

### 发布前检查清单

- [x] `plugin.yaml` 的 `name`、`website`、`displayName`、`description` 已填写
- [x] 硬编码 URL 已全部替换为 RemixIcon 类名
- [x] Logo 图片已准备（src/main/resources/logo.png，源自 wizard-avatar.png）
- [x] homepage 已填写为 https://github.com/Wizard-J/halo-ai-assistant
- [ ] `plugin.yaml#requires` 版本范围与目标 Halo 版本匹配
- [ ] `build.gradle#version` 符合插件市场版本规范
- [x] Logo 已生成（256x256 PNG）
- [ ] 功能截图已准备（design/screenshot-*.png），需手动上传到插件市场
- [x] 头像已全部改用 RemixIcon 类名，无需 CDN 图片资源

---

## 历史里程碑（可回滚点）

| Commit | 说明 |
|--------|------|
| `648bcdd` | 移动端布局修复 + 芒格开场白 + 切换角色欢迎页 + 插件作者信息 |
| `HEAD` | 移除 settings.yaml 中硬编码的 wizardj.cn URL + 修复 personaManagerClose null addEventListener |
