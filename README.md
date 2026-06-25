# Halo AI Assistant

一款面向 [Halo 2](https://www.halo.run/) 的 AI 博客助手插件。支持自然语言管理博客、多角色 AI 助手（Persona 系统）、自动运维三人物管线、每日推送。

## 功能

- **智能对话**：自然语言管理文章、分类、标签和评论，流式 SSE 响应，支持 Persona 思考短语动画
- **多角色 Persona 系统**：
  - **老巫师** 🧙 — 博客管理助手，默认角色
  - **芒格视角** — 以查理·芒格的多元思维模型对话分析问题
  - 支持上传 SKILL.md 自定义角色
- **自动标签**：`autoTagArticles` 一键用 AI 分析所有文章标题并自动打标签
- **自动运维**（三人物管线，定时执行）：
  - **巫师前沿站** — AI/科技前沿资讯，标签 `AI前沿`
  - **书虫漫步** — 跨学科知识卡片/好书核心观点，标签 `知识卡片, 好书快读`
  - **技术猎手** — 系统设计/工程实践，标签 `技术干货, 优质译文`
- **每日推送**：通过 Server酱/PushPlus 推送到微信
- **对话历史**：按 Persona + session 持久化

## 环境要求

- Halo `2.20.0+`（验证版本：`2.22.8`）
- JDK `21+`
- OpenAI 兼容的模型 API（如 DeepSeek / OpenAI）

## 快速构建

```bash
# 完整构建（含编译 + 测试）
JAVA_HOME=/path/to/jdk-21 ./gradlew clean build

# 仅运行测试
JAVA_HOME=/path/to/jdk-21 ./gradlew test
```

产物：`build/libs/halo-ai-assistant-2.24.0.jar`

> 测试：20 个单元测试（PersonaUtils 12 + AutoOps 7），覆盖 token 估算、压缩逻辑、Reactor 异常处理、RSS 解析等。
> 注：PersonaService 集成测试需在 Halo 运行时验证（`ReactiveExtensionClient` 为 compileOnly）。

## 安装

1. 登录 Halo 管理后台 → 插件 → 上传 JAR → 启用
2. 打开插件设置，配置 API Key、模型、页面标题/图标/欢迎语等
3. 聊天页面：`https://你的域名/api/ai-assistant/chat-page`


### 热部署

替换 JAR 后 Halo 自动热加载（约 15-30s），无需重启容器。

## 配置文件

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| Base URL | API 端点 | `https://api.deepseek.com` |
| API Key | 模型密钥 | — |
| 模型名称 | Chat 模型 | `deepseek-v4-flash` |
| 最大 Token | 单次输出上限 | `16384` |
| 页面标题 | 浏览器标签页 + 顶部品牌名 | `老巫师` |
| 页面图标 | 左上角图标（Emoji / Remix 类名 / 图片 URL） | `🧙` |
| 欢迎语 | 页面打开时显示的标题 | `你好，我是老巫师，巫师前沿站的AI助手` |

## Persona 系统

### 内置角色

| ID | 名称 | 头像 | 思考短语 | 说明 |
|----|------|------|------|------|
| `default` | 老巫师 | wizard-avatar.png | 翻动古籍…挥动法杖…念动咒语… | 博客管理助手 |
| `munger` | 芒格视角 | munger.jpeg | 摘下眼镜…擦拭眼镜…翻阅《穷查理宝典》… | 查理·芒格思维模型 |

### 自定义角色

上传 SKILL.md 文件可创建新角色。SKILL.md 格式：

```markdown
---
name: 角色名称
description: 简短描述
---

## 角色定义

角色系统提示词...
```

## 自动运维：三人物管线

每天定时读取 RSS/Atom 来源 → AI 筛选 → 生成文章 → 保存/发布。

| 人物 | 作者 | 标签 | RSS 源 |
|------|------|------|--------|
| 巫师前沿站 | wizard-frontier | AI前沿 | 主要 60% + 次要 40% |
| 书虫漫步 | bookworm-wanderer | 知识卡片, 好书快读 | 科普/跨学科源 |
| 技术猎手 | tech-hunter | 技术干货, 优质译文 | 独立技术源 |

## 部署到服务器

1. 本地构建：`JAVA_HOME=/path/to/jdk-21 ./gradlew clean build`
2. 产物：`build/libs/halo-ai-assistant-2.24.0.jar`
3. 通过 **1Panel 后台 → 插件 → 上传 JAR** 上传部署
4. Halo 自动热加载，无需重启容器

## 架构

```
聊天页面（可配置标题/图标/欢迎语）
  ├── AgentService ── AI API
  │     └── ToolRegistry
  │           ├── ArticleTool（+ autoTagArticles）
  │           ├── CategoryTool
  │           ├── TagTool
  │           └── CommentTool
  ├── Persona 系统（上传 SKILL.md / 内置角色）
  │     ├── PersonaDefinition（角色定义）
  │     ├── PersonaUtils（Token 估算/压缩 纯函数）
  │     └── Conversation（对话持久化）
  └── AutoOpsService（三人物定时管线）
```

## 查看日志

```bash
docker logs -f <halo-container> | grep -E "已注册工具|autoTag|Persona|自动运维"
```

## 测试

```bash
# 运行所有单元测试（19 个）
JAVA_HOME=/path/to/jdk-21 ./gradlew test
```

> 测试覆盖：Token 估算（中/英/混合）、压缩判断、消息裁剪、Reactor defer 异常处理、null publisher 兜底、RSS 源解析、去重逻辑。



## 上下文导出与提炼

| API | 方法 | 说明 |
|-----|------|------|
| `/api/ai-assistant/persona/{id}/context/download?sessionId=xxx` | GET | 导出：自动提炼 + 返回合并后的 AGENTS.md |
| `/api/ai-assistant/persona/{id}/context/refine?sessionId=xxx` | POST | 仅提炼，不下 |
| `/api/ai-assistant/persona/{id}/context` | POST | 上传 AGENTS.md |

导出流程：点击导出 → 自动提炼（新对话 AI 合并到 AGENTS.md）→ 只返回合并后的 AGENTS.md（不追加原始对话）。
---

## 权限说明

> ⚠️ **本插件仅支持 Halo 超级管理员使用**。
>
> 聊天页面 `/api/ai-assistant/chat-page` 需要管理员登录才能访问。所有涉及文章创建/更新/删除、分类/标签管理、评论审核/删除、自动生成草稿/发布的功能，均以当前登录管理员的身份执行。
>
> 这不是公开的访客聊天工具，普通访客无法看到或使用此插件提供的任何功能。

---

## 使用说明

### 第一步：插件设置

1. 登录 Halo 管理后台 → **插件** → 找到 **AI 智能助手** → 点击 **设置**
2. 填入以下信息：
   - **API 端点**：你的模型服务地址（DeepSeek / OpenAI 兼容）
   - **API Key**：你的模型密钥
   - **模型名称**：如 `deepseek-v4-flash`、`gpt-4o-mini` 等
   - 可选：页面标题、图标、欢迎语
3. 保存设置后，通过 `https://你的域名/api/ai-assistant/chat-page` 访问聊天页面

### 第二步：智能对话

进入聊天页面后，你可以：

| 你想做什么 | 怎么说 |
|-----------|--------|
| 查看最近文章 | "帮我看看最近发布的文章" |
| 创建文章 | "写一篇关于 Docker 的文章" |
| 管理分类 | "帮我创建一个叫'技术'的分类" |
| 管理标签 | "给所有文章自动打标签" |
| 查看评论 | "查看待审核的评论" |

> 所有操作都通过自然语言完成，AI 会自动调用对应的工具函数。

### 第三步：切换角色

点击聊天框右上角的角色切换按钮，可以在以下角色间切换：
- **老巫师** 🧙 — 默认博客管理助手
- **芒格视角** — 查理·芒格思维模型分析

也可以在 Persona 管理面板上传自定义 SKILL.md 创建新角色。

### 第四步：自动运维（可选）

在插件设置 → **自动运维** 选项卡中：
1. 开启对应人物（巫师前沿站 / 书虫漫步 / 技术猎手）
2. 配置 RSS 源和发布时间
3. 插件会在每日设定时间自动拉取 RSS → AI 筛选 → 生成文章 → 发布

> 此功能适合希望保持站点每日更新的站长。

---

### 管理面板入口

| 功能 | 访问路径 |
|------|---------|
| 插件设置 | Halo 后台 → 插件 → AI 智能助手 → 设置 |
| 聊天页面 | `https://你的域名/api/ai-assistant/chat-page` |
| 角色管理 | 聊天页 → 右上角切换按钮 → 管理 Persona |
| 自动运维配置 | 插件设置 → 自动运维选项卡 |

---

## 隐私与第三方服务披露

### 1. 收集和处理的本地数据

| 数据类型 | 用途 | 存储位置 | 保存期限 |
|---------|------|---------|---------|
| 对话上下文（用户输入 + AI 回复） | 维持多轮对话连贯性 | Halo 数据库（MySQL/PostgreSQL） | 直到用户删除对话或卸载插件 |
| 文章标题/内容摘要 | AI 分析后执行管理操作（创建/更新/打标签等） | 不额外存储，操作后仅保留文章本身 | — |
| 分类/标签/评论数据 | AI 读取后供用户管理 | 不额外存储 | — |
| 上传的 SKILL.md | 自定义角色定义 | Halo 数据库 | 直到用户删除角色或卸载插件 |

### 2. 发送到第三方服务的数据

| 第三方服务 | 发送的数据 | 用途 | 配置入口 | 关闭方式 |
|-----------|-----------|------|---------|---------|
| OpenAI / DeepSeek 等模型 API | 用户输入的对话消息、系统提示词、文章标题/摘要 | AI 生成回复和操作指令 | 插件设置 → Base URL + API Key | 清空 API Key 字段并保存 |
| RSS 源（如 Hacker News、Ars Technica 等） | 无（插件主动拉取，不发送） | 获取资讯内容用于自动发布 | 插件设置 → 自动运维 → RSS 来源 | 关闭对应人物的"启用"开关 |
| Server酱 / PushPlus | 每日文章摘要 | 推送到用户微信 | 插件设置 → 每日推送 → Token | 清空 Token 字段或关闭推送 |

### 3. 数据控制权

- **查看数据**：所有对话记录保存在 Halo 数据库中，可通过数据库管理工具查看
- **删除数据**：在聊天页面删除对话，或卸载插件后所有相关数据一并清理
- **撤销授权**：清空 API Key 并保存即可停止向模型服务发送数据
- **导出数据**：支持通过 Persona 管理面板导出上下文 AGENTS.md

### 4. 数据安全

- 对话数据仅存储在用户自己的 Halo 数据库中，不会上传到除模型 API 外的任何第三方
- API Key 存储在 Halo 的 ConfigMap 中（加密存储由 Halo 平台保证）
- 插件本身不包含任何遥测或分析 SDK

---


## License

[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html)
