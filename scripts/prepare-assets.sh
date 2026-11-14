#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:-$(pwd)}"
ASSETS_DIR="$ROOT/android/app/src/main/assets"
RES_DRAWABLE="$ROOT/android/app/src/main/res/drawable"

BUILD_TARGET="${BUILD_TARGET:-index.html}"
EXTRA_ASSETS_DIR="${EXTRA_ASSETS_DIR:-}"
ICON_FILE="${ICON_FILE:-}"

rm -rf "$ASSETS_DIR"
mkdir -p "$ASSETS_DIR"

# ---------- 判定模式：URL 或 HTML 文件 ----------
if [[ "$BUILD_TARGET" =~ ^https?:// ]]; then
  echo "::notice::URL 模式：$BUILD_TARGET"
  if [[ -n "$EXTRA_ASSETS_DIR" ]]; then
    echo "::warning::URL 模式下 assets_dir 将被忽略"
  fi
  # Java 侧读取此文件决定初始 URL
  printf '%s' "$BUILD_TARGET" > "$ASSETS_DIR/start_url.txt"
else
  echo "::notice::HTML 文件模式：$BUILD_TARGET"
  if [[ ! -f "$ROOT/$BUILD_TARGET" ]]; then
    echo "::error::找不到 HTML 文件: $BUILD_TARGET"
    exit 1
  fi
  cp "$ROOT/$BUILD_TARGET" "$ASSETS_DIR/index.html"

  if [[ -n "$EXTRA_ASSETS_DIR" ]]; then
    if [[ ! -d "$ROOT/$EXTRA_ASSETS_DIR" ]]; then
      echo "::error::找不到资源目录: $EXTRA_ASSETS_DIR"
      exit 1
    fi
    cp -R "$ROOT/$EXTRA_ASSETS_DIR"/. "$ASSETS_DIR"/
    cp "$ROOT/$BUILD_TARGET" "$ASSETS_DIR/index.html"
  fi
fi

# ---------- 图标（可选） ----------
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