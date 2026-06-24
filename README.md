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
  - **书虫漫步** — 人物传记/好书推荐，标签 `人物传记, 每日好书`
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

> 测试：19 个单元测试（PersonaUtils 12 + AutoOps 7），覆盖 token 估算、压缩逻辑、Reactor 异常处理、RSS 解析等。
> 注：PersonaService 集成测试需在 Halo 运行时验证（`ReactiveExtensionClient` 为 compileOnly）。

## 安装

1. 登录 Halo 管理后台 → 插件 → 上传 JAR → 启用
2. 打开插件设置，配置 API Key、模型、页面标题/图标/欢迎语等
3. 聊天页面：`https://你的域名/api/ai-assistant/chat-page`

### 热部署

替换 JAR 后 Halo 自动热加载（约 15-30s），无需重启容器：

```bash
# 一键构建 + 部署
cd halo-ai-assistant && \
  JAVA_HOME=/path/to/jdk-21 ./gradlew clean build && \
  scp build/libs/halo-ai-assistant-2.24.0.jar root@your-server:/tmp/ && \
  ssh root@your-server "docker cp /tmp/halo-ai-assistant-2.24.0.jar <container>:/root/.halo2/plugins/"
```

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

| 人物 | 作者 | 标签 | RSS 源类型 |
|------|------|------|------------|
| 巫师前沿站 | wizard-frontier | AI前沿 | 主要 60% + 次要 40% |
| 书虫漫步 | bookworm-wanderer | 人物传记, 每日好书 | 独立人文源 |
| 技术猎手 | tech-hunter | 技术干货, 优质译文 | 独立技术源 |

## 部署到服务器

### 一键构建 + 热部署

```bash
cd /Users/zhangjianmin/project/halo-ai-assistant && \
  JAVA_HOME=/path/to/jdk-21 ./gradlew clean build && \
  scp build/libs/halo-ai-assistant-2.24.0.jar root@your-server:/tmp/ && \
  ssh root@your-server "docker cp /tmp/halo-ai-assistant-2.24.0.jar <container>:/root/.halo2/plugins/"
```

> 替换 JAR 后 Halo 自动热加载（约 15-30s），无需重启容器。

### 数据库直接更新 Persona

```sql
UPDATE extensions SET data = JSON_SET(CONVERT(data USING utf8mb4),
  '$.spec.iconUrl', 'https://your.domain/upload/image.png')
WHERE name = '/registry/.../personadefinitions/default';
```

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

## License

[GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html)
