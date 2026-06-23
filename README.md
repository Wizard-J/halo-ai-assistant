# Halo AI Assistant

一款面向 [Halo 2](https://www.halo.run/) 的 AI 博客助手插件。它通过 OpenAI 兼容接口理解自然语言，管理文章、分类、标签和评论，并支持三组自动运维管线，以不同身份定时整理并发布优质内容。

## 功能

- 自然语言查看、创建、更新、删除文章
- 管理文章分类、标签和评论
- 对话历史、新建会话和流式响应
- 每日自动读取 RSS/Atom 资讯
- AI 筛选、去重、生成结构化日报
- **三个人物管线**：
  - **巫师前沿站**（`wizard-frontier`）— AI/科技前沿资讯，标签 `AI前沿`
  - **书虫漫步**（`bookworm-wanderer`）— 人物传记/好书推荐，标签 `人物传记, 每日好书`
  - **技术猎手**（`tech-hunter`）— 系统设计/工程实践，标签 `技术干货, 优质译文`
- 自动创建专属作者
- 自动发布或仅保存草稿
- 测试按钮一键验证三组管线

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
build/libs/halo-ai-assistant-1.2.0.jar
```

如安装了多个 JDK：

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew clean build
```

## 安装

1. 登录 Halo 管理后台。
2. 进入"插件"页面。
3. 上传 `build/libs` 下生成的 JAR。
4. 启用"AI 智能助手"。
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
| 页面标题 | 浏览器标签页和顶部的品牌名称 | `巫师前沿站` |
| 页面图标 | 左上角品牌图标（Emoji 或 Remix 类名） | `🧠` 或 `ri-sparkling-2-fill` |
| 欢迎语 | 页面打开时显示的欢迎标题 | `你好，我是巫师前沿站` |

> API Key 只应保存在 Halo 插件配置中，不要写入代码或提交到 Git。

## 自动运维：三个人物管线

### 人物一：巫师前沿站

自动运维任务在设定时间读取配置的 RSS/Atom 来源，筛选尚未处理的资讯，调用模型生成中文技术日报，并使用独立作者"巫师前沿站"保存或发布文章。

- 主要 RSS 源（头条/重点来源，占 60% 配额）
- 次要 RSS 源（补充来源，占 40% 配额）
- 标签：`AI前沿`

### 人物二：书虫漫步

独立的人物管线，使用独立的 RSS 源和标签系统：

- RSS 来源：人物传记、书评、人文类源
- 标签：`人物传记, 每日好书`
- 独立去重记录

### 人物三：技术猎手

独立的人物管线，关注深度技术内容：

- RSS 来源：系统设计、架构、工程实践类源
- 标签：`技术干货, 优质译文`
- 独立去重记录

### 测试自动运维

聊天页顶部提供"测试自动运维"按钮。测试会同时检查三个人物的 RSS、AI、作者创建与文章写入，并始终生成草稿，不会自动发布，也不会占用正式任务的新闻去重记录。

### 默认新闻来源

**巫师前沿站 — 主要源（高优先级）：**

- Hacker News（社区精选）
- Ars Technica（深度技术）
- InfoQ（软件工程）

**巫师前沿站 — 次要源：**

- GitHub Changelog
- Cloudflare Blog
- OpenAI News
- Google AI Blog

**书虫漫步：**

- The Marginalian
- The New Yorker Books
- The Guardian Books
- Aeon
- LitHub

**技术猎手：**

- High Scalability
- Martin Fowler
- The Pragmatic Engineer
- ByteByteGo
- Quastor

## 自动运维 + 每日推送

### 整体流程

```
每天 08:00 — 自动运维生成文章
    │
每天 08:30 — 每日推送 → 微信
    │
1Panel 计划任务 ──curl──► 插件推送 API
```

### 配置推送

1. 注册 [Server酱](https://sct.ftqq.com) 或 [PushPlus](https://pushplus.plus)
2. Halo 后台 → 插件 → AI 智能助手 → 配置 → 每日推送
3. 设置 1Panel 定时任务：

```bash
curl -s "https://你的域名/plugins/ai-assistant/api/ai-assistant/daily-push?secret=你的密钥"
```

## 架构

```text
Chat Page (可配置页面标题/图标/欢迎语)
   │
   ├── AgentService ── OpenAI-compatible API
   │        │
   │        └── ToolRegistry
   │              ├── ArticleTool (+ tags)
   │              ├── CategoryTool
   │              ├── TagTool
   │              └── CommentTool
   │
   └── AutoOpsService
            ├── 人物一：巫师前沿站（AI前沿）
            │     ├── 主要 RSS 源（60% 配额）
            │     ├── 次要 RSS 源（40% 配额）
            │     ├── AI 生成 → 标签 "AI前沿"
            │     └── Halo Post + Snapshot
            │
            ├── 人物二：书虫漫步（人物传记/每日好书）
            │     ├── 独立 RSS 源
            │     ├── 独立去重记录
            │     ├── AI 生成 → 标签 "人物传记, 每日好书"
            │     └── Halo Post + Snapshot
            │
            └── 人物三：技术猎手（技术干货）
                  ├── 独立 RSS 源
                  ├── 独立去重记录
                  ├── AI 生成 → 标签 "技术干货, 优质译文"
                  └── Halo Post + Snapshot
```

## 安全提示

- 自动发布前建议先使用草稿模式观察一段时间。
- RSS 和网页内容会被视为不可信输入，不能覆盖系统提示词。
- 文章应保留来源链接及 AI 整理声明。
- 控制每日新闻数量和 Token 上限，避免意外消耗。
- 对外开放聊天接口前，建议在反向代理或 Halo 权限层增加访问控制。

## 查看日志

```bash
docker logs -f <halo-container> | grep -E "自动运维|新闻源|巫师|书虫|技术猎手"
```

## License

[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html)
