#!/bin/bash
set -e

# ==============================================================================
# Tiny Web Games 离线游戏包抓取与构建工具
# 用法:
#   ./scripts/fetch_games.sh <repo-name>
# 例如:
#   ./scripts/fetch_games.sh tank
# ==============================================================================

REPO_NAME=${1:-tank}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$ROOT_DIR/app/src/main/assets/games/$REPO_NAME"
TEMP_DIR="/tmp/tiny-web-game-$REPO_NAME"

echo "🎮 准备同步离线游戏: $REPO_NAME"
echo "📂 目标存放路径: $ASSETS_DIR"

rm -rf "$TEMP_DIR"
mkdir -p "$TEMP_DIR"

echo "⬇️ 正在克隆仓库 https://github.com/tiny-web-games/$REPO_NAME.git ..."
git clone --depth 1 "https://github.com/tiny-web-games/$REPO_NAME.git" "$TEMP_DIR"

cd "$TEMP_DIR"

if [ -f "package.json" ]; then
    echo "📦 正在安装依赖并构建静态产物..."
    npm install
    npm run build || true
fi

# 寻找构建产物目录
OUTPUT_DIR=""
if [ -d "$TEMP_DIR/dist" ]; then
    OUTPUT_DIR="$TEMP_DIR/dist"
elif [ -d "$TEMP_DIR/build" ]; then
    OUTPUT_DIR="$TEMP_DIR/build"
elif [ -f "$TEMP_DIR/index.html" ]; then
    OUTPUT_DIR="$TEMP_DIR"
fi

if [ -z "$OUTPUT_DIR" ]; then
    echo "❌ 未检测到可用的静态页面或产物目录！"
    exit 1
fi

echo "🚀 复制产物到 Android assets..."
mkdir -p "$ASSETS_DIR"
cp -r "$OUTPUT_DIR"/* "$ASSETS_DIR"/

echo "✅ 离线游戏包 [$REPO_NAME] 同步打包完成！"
ls -la "$ASSETS_DIR"
