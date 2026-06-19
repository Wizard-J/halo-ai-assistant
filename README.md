# Halo AI Assistant

一款面向 [Halo 2](https://www.halo.run/) 的 AI 博客助手插件。它通过 OpenAI 兼容接口理解自然语言，管理文章、分类、标签和评论，并能以“巫师前沿站”的身份定时整理技术资讯。

## 功能

- 自然语言查看、创建、更新、删除文章
- 管理文章分类、标签和评论
- Markdown/富文本内容快照兼容
- 批量读取正文日期并同步文章发布时间
- 对话历史、新建会话和流式响应
- 每日自动读取 RSS/Atom 技术资讯
- AI 筛选、去重、生成技术日报
- 自动创建“巫师前沿站”专属作者
- 自动发布或仅保存草稿
- 自动运维测试按钮和明确的错误日志

## 环境要求

- Halo `2.20.0+`（开发与验证版本：`2.22.x`）
- JDK `21+`
- OpenAI 兼容的模型 API，例如 DeepSeek 或 OpenAI

## 构建

```bash
./gradlew clean build
```

构建产物位于：

```text
build/libs/halo-ai-assistant-<version>.jar
```

如果本机安装了多个 JDK，可显式指定 Java 21：

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew clean build
```

## 安装

1. 登录 Halo 管理后台。
2. 进入“插件”页面。
3. 上传 `build/libs` 下生成的 JAR。
4. 启用“AI 智能助手”。
5. 打开插件设置完成模型配置。

聊天页面地址：

```text
https://你的域名/api/ai-assistant/chat-page
```

## 基本配置

| 配置项 | 说明 | 示例 |
| --- | --- | --- |
| Base URL | OpenAI 兼容接口地址 | `https://api.deepseek.com` |
| API Key | 模型服务密钥 | `sk-...` |
| 模型名称 | Chat Completions 模型 | `deepseek-chat` |
| 最大 Token 数 | 单次对话输出上限 | `2048` |
| 系统提示词 | 助手角色与行为约束 | 默认即可 |

> API Key 只应保存在 Halo 插件配置中，不要写入代码或提交到 Git。

## 自动运维：巫师前沿站

自动运维任务会在设定时间读取配置的 RSS/Atom 来源，筛选尚未处理的资讯，调用模型生成中文技术日报，并使用独立作者“巫师前沿站”保存或发布文章。

可配置内容包括：

- 总开关与自动发布开关
- 每日执行时间和时区
- 作者昵称与用户名
- RSS/Atom 来源列表
- 关注方向
- 每次新闻数量与输出 Token 上限

### 测试自动运维

聊天页顶部提供“测试自动运维”按钮。测试会检查 RSS、AI、作者创建与文章写入，并始终生成草稿，不会自动发布，也不会占用正式任务的新闻去重记录。

### 默认新闻来源

- GitHub Changelog
- Cloudflare Blog
- OpenAI News
- Google AI Blog

建议优先选择官方 RSS，避免使用来源不明或版权状态不清晰的聚合内容。

## 批量同步发布时间

插件可以扫描文章正文中的 `date`、`publishTime`、`published_at` 等字段，并同步到 Halo 的实际发布时间。

支持常见 ISO 8601、中文日期、斜杠日期、Unix 时间戳以及 1–9 位小数秒。例如：

```text
2023-03-02 22:40:00
2019-09-07 07:26:26.052628
2023-03-02T22:40:00+08:00
2023年3月2日 22时40分
```

建议先让 AI 预览差异，确认后再执行批量修改。

## 架构

```text
Chat Page
   │
   ├── AgentService ── OpenAI-compatible API
   │        │
   │        └── ToolRegistry
   │              ├── ArticleTool
   │              ├── CategoryTool
   │              ├── TagTool
   │              └── CommentTool
   │
   └── AutoOpsService
            ├── RSS / Atom feeds
            ├── AI generation
            ├── URL deduplication
            └── Halo Post + Snapshot
```

## 安全提示

- 自动发布前建议先使用草稿模式观察一段时间。
- RSS 和网页内容会被视为不可信输入，不能覆盖系统提示词。
- 文章应保留来源链接及 AI 整理声明。
- 请控制每日新闻数量和 Token 上限，避免意外消耗。
- 对外开放聊天接口前，建议在反向代理或 Halo 权限层增加访问控制。

## 查看日志

```bash
docker logs -f <halo-container> | grep -E "巫师前沿站|自动运维|新闻源"
```

## 开发

新增 AI 工具时，实现 `Tool` 接口并注册为 Spring `@Component`：

```java
@Component
public class ExampleTool implements Tool {
    @Override
    public String getName() {
        return "exampleTool";
    }

    @Override
    public String getDescription() {
        return "示例工具";
    }

    @Override
    public String getParametersJsonSchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public String execute(JsonNode args) {
        return "ok";
    }
}
```

## License

[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html)
