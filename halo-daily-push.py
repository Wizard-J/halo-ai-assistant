#!/usr/bin/env python3
"""
HALO 每日文章推送脚本
每天早上定时推送昨日/今日发布的文章到微信（Server酱 / PushPlus）

用法:
  1. 注册 Server酱 https://sct.ftqq.com 扫码绑定微信获取 SendKey
  2. ./halo-daily-push.py --blog https://你的博客.com --sendkey YOUR_SENDKEY

定时运行 (1Panel):
  1Panel → 计划任务 → 创建 Shell 脚本 → 填入下方命令，每天 8:00 执行
"""

import urllib.request
import urllib.parse
import json
import sys
import os
import argparse
from datetime import datetime, timezone, timedelta

BJT = timezone(timedelta(hours=8))


def get_today_posts(blog_url: str) -> list | None:
    """从 HALO 公开 API 获取今天发布的文章"""
    api = f"{blog_url}/apis/api.halo.run/v1alpha1/posts"
    # 获取最近 50 篇已发布文章
    url = f"{api}?page=0&size=50&sort=publishTime,desc&publishPhase=published"
    
    req = urllib.request.Request(url, headers={"User-Agent": "HaloPush/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read())
    except Exception as e:
        print(f"❌ 请求 HALO API 失败: {e}", file=sys.stderr)
        return None

    today = datetime.now(BJT).date()
    yesterday = today - timedelta(days=1)
    today_posts = []
    yesterday_posts = []

    for post in data.get("items", []):
        spec = post.get("spec", {})
        pub_time = spec.get("publishTime", "")
        if not pub_time:
            continue
        try:
            dt = datetime.fromisoformat(pub_time.replace("Z", "+00:00"))
            pub_date = dt.astimezone(BJT).date()
        except:
            continue
        if pub_date == today:
            today_posts.append(post)
        elif pub_date == yesterday:
            yesterday_posts.append(post)

    return today_posts, yesterday_posts


def format_message(today_posts: list, yesterday_posts: list, blog_url: str) -> tuple[str, str] | None:
    """格式化为 Markdown 推送内容"""
    if not today_posts and not yesterday_posts:
        return None

    today_count = len(today_posts)
    yesterday_count = len(yesterday_posts)
    total = today_count + yesterday_count

    if today_count > 0:
        title = f"📝 博客今日更新 {today_count} 篇"
    else:
        title = f"📝 博客昨日更新 {yesterday_count} 篇"

    content = f"# 📝 博客文章更新\n\n"
    if today_count > 0:
        content += f"### 今日发布 ({today_count} 篇)\n\n"
        content += _build_post_list(today_posts, blog_url)
    if yesterday_count > 0:
        content += f"\n### 昨日发布 ({yesterday_count} 篇)\n\n"
        content += _build_post_list(yesterday_posts, blog_url)
    
    stats_line = f"\n> 📊 共 {total} 篇更新 · {datetime.now(BJT).strftime('%Y-%m-%d %H:%M')}"
    content += stats_line

    return title, content


def _build_post_list(posts: list, blog_url: str) -> str:
    """生成文章列表 Markdown"""
    lines = []
    for i, post in enumerate(posts, 1):
        spec = post.get("spec", {})
        meta = post.get("metadata", {})
        name = meta.get("name", "")
        post_title = spec.get("title", "无标题")

        # 摘要
        summary = spec.get("summary", "") or spec.get("excerpt", {}).get("autoGenerate", "")
        if not summary or summary is True:
            summary = "点击查看全文"

        # 构建文章链接
        slug = spec.get("slug", "")
        if slug:
            link = f"{blog_url}/archives/{slug}"
        else:
            link = f"{blog_url}/?p={name}"

        lines.append(f"**{i}. [{post_title}]({link})**\n> {summary}\n")
    return "\n".join(lines)


def send_serverchan(title: str, content: str, sendkey: str):
    """通过 Server酱 推送到微信"""
    url = f"https://sctapi.ftqq.com/{sendkey}.send"
    data = urllib.parse.urlencode({
        "title": title,
        "desp": content,
        "tags": "博客推送",
    }).encode()
    try:
        with urllib.request.urlopen(url, data=data, timeout=15) as resp:
            result = json.loads(resp.read())
            if result.get("code") == 0:
                print(f"✅ Server酱 推送成功: {title}")
            else:
                print(f"❌ Server酱 推送失败: {result.get('message', result)}",
                      file=sys.stderr)
    except Exception as e:
        print(f"❌ Server酱 请求异常: {e}", file=sys.stderr)


def send_pushplus(title: str, content: str, token: str):
    """通过 PushPlus 推送到微信"""
    url = "https://www.pushplus.plus/send"
    payload = json.dumps({
        "token": token,
        "title": title,
        "content": content,
        "template": "markdown",
    }).encode()
    req = urllib.request.Request(
        url, data=payload,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            result = json.loads(resp.read())
            if result.get("code") == 200:
                print(f"✅ PushPlus 推送成功: {title}")
            else:
                print(f"❌ PushPlus 推送失败: {result.get('msg', result)}",
                      file=sys.stderr)
    except Exception as e:
        print(f"❌ PushPlus 请求异常: {e}", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(
        description="HALO 每日文章推送脚本",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
使用示例:
  # Server酱 推送
  ./halo-daily-push.py --blog https://example.com \\
      --sendkey SCT123456...

  # PushPlus 推送
  ./halo-daily-push.py --blog https://example.com \\
      --pushplus YOUR_TOKEN

配置环境变量（免命令行参数）:
  export HALO_BLOG_URL=https://example.com
  export SERVERCHAN_SENDKEY=SCT123456...
  export PUSHPLUS_TOKEN=xxx
        """,
    )
    parser.add_argument("--blog", help="博客地址，如 https://example.com")
    parser.add_argument("--sendkey", help="Server酱 SendKey")
    parser.add_argument("--pushplus", help="PushPlus Token")
    args = parser.parse_args()

    # 从环境变量或参数读取配置
    blog_url = args.blog or os.environ.get("HALO_BLOG_URL")
    sendkey = args.sendkey or os.environ.get("SERVERCHAN_SENDKEY")
    push_token = args.pushplus or os.environ.get("PUSHPLUS_TOKEN")

    if not blog_url:
        print("❌ 请提供博客地址 (--blog 或 HALO_BLOG_URL 环境变量)", file=sys.stderr)
        sys.exit(1)
    if not sendkey and not push_token:
        print("❌ 请提供推送方式 (--sendkey / --pushplus 或对应环境变量)",
              file=sys.stderr)
        sys.exit(1)

    # 获取文章
    today_posts, yesterday_posts = get_today_posts(blog_url)
    if today_posts is None:
        sys.exit(1)

    # 格式化
    result = format_message(today_posts, yesterday_posts, blog_url)
    if result is None:
        print("📭 今日无新文章，跳过推送")
        return

    title, content = result
    print(f"\n{'='*40}")
    print(f"📄 推送标题: {title}")
    print(f"{'='*40}")

    # 发送
    if sendkey:
        send_serverchan(title, content, sendkey)
    if push_token:
        send_pushplus(title, content, push_token)

    print("✅ 推送完成")


if __name__ == "__main__":
    main()
