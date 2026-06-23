#!/bin/bash
# HALO 每日推送 Cron 脚本
# 用法: ./cron-push.sh <博客地址> <推送密钥>
# 示例: ./cron-push.sh https://your-blog.com my-secret-key
#
# 可直接粘贴到 1Panel → 计划任务 → Shell 脚本

BLOG="${1:-https://your-blog.com}"
SECRET="${2:-}"

if [ "$BLOG" = "https://your-blog.com" ] && [ -z "$SECRET" ]; then
    # 从环境变量读取
    BLOG="${HALO_BLOG_URL:-}"
    SECRET="${HALO_PUSH_SECRET:-}"
fi

if [ -z "$BLOG" ]; then
    echo "❌ 请提供博客地址"
    echo "用法: $0 <博客地址> <推送密钥>"
    echo "或设置环境变量 HALO_BLOG_URL, HALO_PUSH_SECRET"
    exit 1
fi

echo "📡 正在获取今日文章..."

# 调用插件推送接口
URL="${BLOG}/plugins/ai-assistant/api/ai-assistant/daily-push"
if [ -n "$SECRET" ]; then
    URL="${URL}?secret=${SECRET}"
fi

RESPONSE=$(curl -s "$URL")

echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(data.get('message', '❌ 请求失败'))
    if 'title' in data:
        print(f'标题: {data[\"title\"]}')
    if 'count' in data:
        print(f'文章数: {data[\"count\"]}')
except:
    print(f'原始响应: {sys.stdin.read()}')
"

echo ""
echo "✅ 推送任务完成"
