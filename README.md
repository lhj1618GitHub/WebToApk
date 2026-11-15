# HTML → APK 打包模板

把仓库中的任意 HTML文件/URL地址 打包成 **可安装的 Android 测试 APK**（armv7 / arm64 通用）。

## 特性

| 项目 | 说明 |
| --- | --- |
| 应用名 | 可指定，默认 `WebApp` |
| 包名 | 可指定，默认 `com.example.webapp` |
| 版本 | 可指定，默认 `1.0.0` / `versionCode = 1` |
| 图标 | 默认使用系统图标，可上传 png/jpg 自定义 |
| 签名 | 默认不配置正式签名，使用 debug keystore（可直接安装测试） |
| 架构 | 纯 WebView 壳无原生库，单个 APK 同时支持 armv7 与 arm64 |
| 内置能力 | 本地文件导入、系统下载、内部/外部链接浏览 |

## 使用方式

进入仓库 **Actions → Build APK → Run workflow**，填写参数后运行。

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `build_target` | **主页来源**：填 HTML 文件路径（如 `sample.html`）或 `http(s)://` 开头的 URL | `sample.html` |
| `assets_dir` | 额外静态资源目录（**仅 HTML 文件模式生效**，URL 模式忽略） | 空 |
| `app_name` | 应用显示名称 | `WebApp` |
| `package_name` | 应用包名 | `com.example.webapp` |
| `version_name` | 版本名 | `1.0.0` |
| `version_code` | 版本号（整数） | `1` |
| `icon_file` | 图标路径（png/jpg） | 空（系统默认图标） |

### 构建目标示例

- **本地 HTML 文件**：`build_target = sample.html`
- **在线网页**：`build_target = https://example.com/app`

构建完成后，在 **Artifacts** 中下载 `apk-<应用名>-<版本>.zip`，解压即得 APK。


## 目录说明

```
.github/workflows/build-apk.yml   CI 工作流
scripts/prepare-assets.sh         把 HTML/资源/图标搬运到 Android 工程
android/                          WebView 壳工程（AGP 8.5 + JDK 17）
sample.html                        示例主页，可替换为自己的页面
```

## 关于签名

- 默认输出 **debug 签名** 的 APK，可直接 `adb install` 或点击安装，无需额外配置。
- 如果你需要 **正式签名**，在 `android/app/build.gradle.kts` 的 `release` 中增加：

  ```kotlin
  signingConfigs {
      create("release") {
          storeFile = file(System.getenv("KEYSTORE_PATH") ?: "release.jks")
          storePassword = System.getenv("KEYSTORE_PASSWORD")
          keyAlias = System.getenv("KEY_ALIAS")
          keyPassword = System.getenv("KEY_PASSWORD")
      }
  }
  buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
  ```

  并在工作流中把 keystore 从 Secrets 还原，改为执行 `gradle assembleRelease`。
  不配置时 `assembleRelease` 会产出 `app-release-unsigned.apk`（需自行签名后才能安装）。

## 内置能力对应实现

| 需求 | 实现 |
| --- | --- |
| 导入手机本地共享目录中的文件 | `WebChromeClient.onShowFileChooser` → SAF 文件选择器；页面中 `<input type="file">` 直接可用 |
| 通过手机自带的能力下载产生的文件 | `DownloadListener` → `DownloadManager`（http/https）；`blob:` / `data:` 通过 JS 桥 + `MediaStore` 保存到系统「下载」目录 |
| 浏览内部链接指向的网页内容 | `WebViewAssetLoader` 提供 `https://appassets.androidplatform.net/assets/...` 源，`shouldOverrideUrlLoading` 让 http/https 链接留在 WebView 内，非 http scheme 交给系统处理 |

### 下载文件自定义文件名

页面可通过以下三种方式指定下载文件名（优先级从高到低）：

| 方式 | 用法 | 适用场景 |
| --- | --- | --- |
| 主动调用 | `AndroidBridge.download(url, 'name.ext', 'mime/type')` | 已知远程 URL，主动下载 |
| 直接保存 | `AndroidBridge.saveBase64(b64, 'name.ext', 'mime/type')` | 内容在 JS 内生成，最可控 |
| 预设名 | `AndroidBridge.setNextFileName('name.ext')` 后触发任意下载 | `<a download>`、`blob:`、`data:`、普通跳转下载 |

未指定时回退到原有逻辑：http(s) 从 `Content-Disposition`/URL 推断，`blob:`/`data:` 使用时间戳名 `webapp_<时间戳>.<ext>`。

所有传入的文件名都会经过清洗：移除 `/`、`\` 及 `<>:"|?*`、控制字符，去掉首尾空白与点，长度超过 180 字符时截断并保留扩展名。非法或清洗后为空则回退到默认命名。

## 本地调试

```bash
export ANDROID_HOME=/path/to/android-sdk
export HTML_FILE=sample.html
bash scripts/prepare-assets.sh
cd android && gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```