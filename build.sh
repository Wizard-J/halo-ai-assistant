#!/bin/bash
# HALO AI 助手插件构建脚本
# 用法: ./build.sh

set -e

echo "=== HALO AI 助手插件构建 ==="

# Halo 2.22 的 API 使用 Java 21 字节码
if ! java -version 2>&1 | grep -q 'version "2[1-9]\|version "[3-9][0-9]'; then
    echo "⚠️  未检测到 JDK 21+，请安装 JDK 21 或更高版本"
    echo "   macOS: brew install openjdk@21"
    echo "   或使用 1Panel 服务器上的 JDK"
    exit 1
fi

# 初始化 Gradle Wrapper（如果不存在）
if [ ! -f "gradlew" ]; then
    echo "📦 初始化 Gradle Wrapper..."
    gradle wrapper --gradle-version 8.10 2>/dev/null || {
        echo "   本地未安装 Gradle，从模板下载..."
        curl -sL "https://github.com/halo-dev/plugin-starter/raw/main/gradlew" -o gradlew
        curl -sL "https://github.com/halo-dev/plugin-starter/raw/main/gradlew.bat" -o gradlew.bat
        mkdir -p gradle/wrapper
        curl -sL "https://github.com/halo-dev/plugin-starter/raw/main/gradle/wrapper/gradle-wrapper.jar" \
            -o gradle/wrapper/gradle-wrapper.jar
        chmod +x gradlew
    }
fi

echo "🔨 构建插件..."
./gradlew clean build

echo ""
echo "✅ 构建完成！"
echo "   插件文件: build/libs/halo-ai-assistant-*.jar"
echo ""
echo "📤 部署到 1Panel:"
echo "   1Panel → HALO → 插件 → 上传安装"
