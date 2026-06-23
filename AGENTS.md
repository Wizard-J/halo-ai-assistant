# Halo AI Assistant

> 一款面向 [Halo 2](https://www.halo.run/) 的 AI 博客助手插件，基于 Java 21 + Gradle 构建。

---

## 项目概览

| 维度 | 说明 |
|------|------|
| **技术栈** | Java 21, Spring (Halo Plugin API), Gradle 8.10, WebFlux (Reactor), Lombok |
| **构建命令** | `./gradlew clean build` — 产物位于 `build/libs/halo-ai-assistant-<version>.jar` |
| **最低依赖** | Halo `2.20.0+`（开发验证版本 `2.22.x`） |
| **插件元数据** | `src/main/resources/plugin.yaml` — 名称 `ai-assistant`，显示名 "AI 智能助手" |
| **版本** | `1.2.0` |
| **许可证** | GPL-3.0 |

---

## 目录结构

```
halo-ai-assistant/
├── AGENTS.md                         # 本文件 — 编码规范与项目上下文
├── README.md                         # 用户文档（功能、安装、配置说明）
├── build.gradle                      # Gradle 构建配置（Halo 2.22.5, Lombok, DevTools）
├── settings.gradle                   # 项目名 halo-ai-assistant
├── gradle.properties                 # 版本号 version=1.2.0
├── gradlew / gradlew.bat             # Gradle Wrapper
├── build.sh                          # 构建辅助脚本（自动检测 JDK）
├── cron-push.sh                      # 每日推送 Cron 辅助脚本
├── .gitignore                        # Git 忽略规则
│
├── src/main/
│   ├── java/io/codex/haloaiassistant/
│   │   ├── HaloAiAssistantPlugin.java           # 插件生命周期（start/stop）
│   │   ├── config/
│   │   │   ├── PluginConfiguration.java         # Spring 配置：注册 Tools + 路由 Bean
│   │   │   ├── AiAssistantSetting.java          # AI 对话 + 推送 + 页面显示配置 POJO
│   │   │   └── AutoOpsSetting.java              # 三人物自动运维配置
│   │   ├── agent/
│   │   │   ├── Tool.java                        # AI 工具接口（function calling）
│   │   │   ├── ToolRegistry.java                # 工具注册中心
│   │   │   ├── AgentService.java                # AI 对话引擎（SSE 流式 + function calling 循环）
│   │   │   └── tools/
│   │   │       ├── ArticleTool.java             # 文章 CRUD（支持 tags）
│   │   │       ├── CategoryTool.java            # 分类管理
│   │   │       ├── TagTool.java                 # 标签管理
│   │   │       └── CommentTool.java             # 评论管理
│   │   ├── endpoint/
│   │   │   └── AiChatEndpoint.java              # REST 端点：chat / tools / chat-page / auto-ops/test / daily-push
│   │   └── autoops/
│   │       └── AutoOpsService.java              # 三个人物管线：巫师前沿站 + 书虫漫步 + 技术猎手
│   └── resources/
│       ├── plugin.yaml                          # Halo 插件元数据（entry:/api/ai-assistant/chat-page）
│       ├── extensions/settings.yaml             # 插件设置表单（basic/push/autoOps/secondaryOps/tertiaryOps）
│       └── chat-page.html                       # 聊天页面（SSE 流式渲染）
│
├── design/                            # UI 设计截图
├── workplace/                         # 工作区
└── build/                             # 构建产物
```

---

## 核心架构

```
聊天页面 (chat-page.html) — 页面标题/图标/欢迎语可配置
    │
    ├── POST /api/ai-assistant/chat  ──►  AgentService.chat()
    │                                      │
    │                                      └── function calling 循环（最多 5 轮）
    │                                              ├── ArticleTool（支持 tags）
    │                                              ├── CategoryTool
    │                                              ├── TagTool
    │                                              └── CommentTool
    │
    ├── GET  /api/ai-assistant/daily-push  ──►  AiChatEndpoint.handleDailyPush()
    │
    ├── POST /api/ai-assistant/auto-ops/test  ──►  AutoOpsService.testNow()
    │                                                   └── 同时测试三个人物
    │
    └── AutoOpsService (定时 @Scheduled 60s)
              │
              ├── 人物一：巫师前沿站（AI前沿）
              │     ├── 主要 RSS 源 + 次要 RSS 源（6:4 分配）
              │     ├── AI 生成 → 标签 "AI前沿"
              │     ├── 独立去重记录（90天）
              │     └── 发布或保存为草稿
              │
              ├── 人物二：书虫漫步（人物传记/每日好书）
              │     ├── 独立 RSS 源
              │     ├── AI 生成 → 标签 "人物传记, 每日好书"
              │     ├── 独立去重记录（90天）
              │     └── 发布或保存为草稿
              │
              └── 人物三：技术猎手（技术干货）
                    ├── 独立 RSS 源
                    ├── AI 生成 → 标签 "技术干货, 优质译文"
                    ├── 独立去重记录（90天）
                    └── 发布或保存为草稿
```

---

## 开发规范

### 新增 AI 工具

实现 `Tool` 接口并标注 `@Component`，工具会自动被 `PluginConfiguration` 注册：

```java
@Component
public class ExampleTool implements Tool {
    @Override public String getName() { return "exampleTool"; }
    @Override public String getDescription() { return "示例工具"; }
    @Override public String getParametersJsonSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "搜索关键词" }
          },
          "required": ["query"]
        }
        """;
    }
    @Override public String execute(JsonNode args) {
        return "执行结果（Markdown 格式）";
    }
}
```

> ⚠️ `execute()` 必须返回 Markdown 格式文本，`JsonNode args` 来自 AI function calling 参数。

### 代码风格

- **语言**：Java 21，`options.release = 21`
- **缩进**：4 空格，UTF-8 编码
- **Lombok**：使用 `@Slf4j`、`@Data`、`@RequiredArgsConstructor`
- **响应式**：优先使用 Reactor（`Flux`/`Mono`），阻塞操作使用 `.block()` 时使用 `subscribeOn(Schedulers.boundedElastic())`
- **异常处理**：`rootMessage()` 提取最深层根因
- **命名**：camelCase，工具类以 `Tool` 结尾，配置类以 `Setting` 结尾

### 插件配置组

| group | 用途 |
|-------|------|
| `basic` | AI 配置 + 页面显示（pageTitle/pageIcon/greeting） |
| `push` | 每日推送（secret/channel/token/time） |
| `autoOps` | 自动运维 — 巫师前沿站 |
| `secondaryOps` | 自动运维 — 书虫漫步 |
| `tertiaryOps` | 自动运维 — 技术猎手 |

### 构建与部署

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew clean build
# 产物: build/libs/halo-ai-assistant-<version>.jar
# → 1Panel → Halo → 插件 → 上传安装
```

---

## 关键注意事项

1. **三个人物管线**：`AutoOpsSetting` 中有三组独立配置，各有独立的 RSS 源、标签、去重记录（90 天），互不影响。
2. **API Key 安全**：Key 只保存在 Halo 数据库（ConfigMap），不写入代码或 Git。
3. **Function Calling 深度**：`AgentService.doChat()` 限制最多 5 轮工具调用。
4. **文章自动标签**：每管线在发布时通过 `tags` 参数传给 `CreateArticleTool`，文章自动带上配置的标签。
5. **测试按钮**：点击「测试自动运维」同时测试三个人物，返回 `{results: [...]}` 合并结果。异常时返回 `{"success": false, "error": "..."}` 而非 HTTP 500。
6. **SSE 流式**：聊天端点返回 `text/event-stream`，前端通过 `ReadableStream` 增量渲染。
7. **页面个性化**：`AiAssistantSetting` 支持 `pageTitle`/`pageIcon`/`greeting` 字段，`handleChatPage` 动态替换 HTML。
8. **RouterFunction 注册**：`PluginConfiguration` 中的两个 `RouterFunction` Bean（`aiChatRouter` + `autoOpsTestRouter`）由 Spring 自动注册。部署后如路由不生效，需重启 Halo。
9. **Reactor 线程模型**：避免在 Reactor 线程直接调用阻塞 API，使用 `Schedulers.boundedElastic()` 包裹阻塞操作。

---

## 用户文档索引

- [README.md](./README.md) — 完整安装、配置、功能说明
- [build.sh](./build.sh) — 构建辅助脚本
- [cron-push.sh](./cron-push.sh) — 1Panel/crontab 推送脚本
