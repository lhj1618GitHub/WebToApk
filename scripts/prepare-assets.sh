#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:-$(pwd)}"
ASSETS_DIR="$ROOT/android/app/src/main/assets"
RES_DRAWABLE="$ROOT/android/app/src/main/res/drawable"

HTML_FILE="${HTML_FILE:-index.html}"
EXTRA_ASSETS_DIR="${EXTRA_ASSETS_DIR:-}"
ICON_FILE="${ICON_FILE:-}"

if [[ ! -f "$ROOT/$HTML_FILE" ]]; then
  echo "::error::找不到 HTML 文件: $HTML_FILE"
  exit 1
fi

rm -rf "$ASSETS_DIR"
mkdir -p "$ASSETS_DIR"

# 主页面统一命名为 index.html
cp "$ROOT/$HTML_FILE" "$ASSETS_DIR/index.html"

# 可选：合并额外静态资源
if [[ -n "$EXTRA_ASSETS_DIR" ]]; then
  if [[ ! -d "$ROOT/$EXTRA_ASSETS_DIR" ]]; then
    echo "::error::找不到资源目录: $EXTRA_ASSETS_DIR"
    exit 1
  fi
  cp -R "$ROOT/$EXTRA_ASSETS_DIR"/. "$ASSETS_DIR"/
  cp "$ROOT/$HTML_FILE" "$ASSETS_DIR/index.html"
fi

# 图标（可选）
if [[ -n "$ICON_FILE" ]]; then
  if [[ ! -f "$ROOT/$ICON_FILE" ]]; then
    echo "::error::找不到图标文件: $ICON_FILE"
    exit 1
  fi
  EXT="${ICON_FILE##*.}"
  EXT="${EXT,,}"
  mkdir -p "$RES_DRAWABLE"
  cp "$ROOT/$ICON_FILE" "$RES_DRAWABLE/app_icon.$EXT"
  echo "APP_ICON_REF=@drawable/app_icon" >> "$GITHUB_ENV"
  echo "使用自定义图标: $ICON_FILE"
else
  echo "APP_ICON_REF=@android:drawable/sym_def_app_icon" >> "$GITHUB_ENV"
  echo "未提供图标，使用系统默认图标"
fi

echo "assets 目录内容："
ls -la "$ASSETS_DIR"