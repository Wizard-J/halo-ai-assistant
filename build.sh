#!/bin/bash
# HALO AI 助手插件构建脚本
# 用法: ./build.sh          # 构建并升级 patch 版本号
#        ./build.sh --minor # 构建并升级 minor 版本号
#        ./build.sh --major # 构建并升级 major 版本号
#        ./build.sh --no-bump # 仅构建，不升级版本号

set -e

echo "=== HALO AI 助手插件构建 ==="

# 检测 JDK 21+
if ! java -version 2>&1 | grep -q 'version "2[1-9]\|version "[3-9][0-9]'; then
    echo "⚠️  未检测到 JDK 21+，请安装 JDK 21 或更高版本"
    exit 1
fi

# 自动升级版本号
BUMP_MODE="patch"
if [ "$1" = "--minor" ]; then BUMP_MODE="minor"; fi
if [ "$1" = "--major" ]; then BUMP_MODE="major"; fi
if [ "$1" = "--no-bump" ]; then BUMP_MODE="none"; fi

if [ "$BUMP_MODE" != "none" ]; then
    CURRENT=$(grep "^version=" gradle.properties | cut -d= -f2)
    MAJOR=$(echo "$CURRENT" | cut -d. -f1)
    MINOR=$(echo "$CURRENT" | cut -d. -f2)
    PATCH=$(echo "$CURRENT" | cut -d. -f3 | cut -d- -f1)

    if [ "$BUMP_MODE" = "major" ]; then
        MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0
    elif [ "$BUMP_MODE" = "minor" ]; then
        MINOR=$((MINOR + 1)); PATCH=0
    else
        PATCH=$((PATCH + 1))
    fi
    NEW_VERSION="${MAJOR}.${MINOR}.${PATCH}"
    sed -i '' "s/^version=.*/version=${NEW_VERSION}/" gradle.properties
    echo "📈 版本号: ${CURRENT} → ${NEW_VERSION}"
fi

# 初始化 Gradle Wrapper
if [ ! -f "gradlew" ]; then
    echo "📦 初始化 Gradle Wrapper..."
    gradle wrapper --gradle-version 8.10 2>/dev/null || {
        echo "下载 Wrapper..."
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

VERSION=$(grep "^version=" gradle.properties | cut -d= -f2)
echo ""
echo "✅ 构建完成！"
echo "   插件文件: build/libs/halo-ai-assistant-${VERSION}.jar"
echo ""
echo "📤 部署到 1Panel:"
echo "   1Panel → HALO → 插件 → 上传安装"
