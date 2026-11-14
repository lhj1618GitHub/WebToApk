package com.example.webappshell;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER     = 1001;
    private static final int REQ_WRITE_PERMISSION = 1002;

    /** 通过 WebViewAssetLoader 把 assets 暴露为 https 源，避免 file:// 的 CORS 限制 */
    private static final String ASSET_DOMAIN = "appassets.androidplatform.net";
    /** 文件模式下的默认首页 */
    private static final String DEFAULT_START_URL =
            "https://" + ASSET_DOMAIN + "/assets/index.html";

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private ValueCallback<Uri[]> filePathCallback;

    /** 由 JS 桥 setNextFileName() 预设，下一次下载触发后即清空（一次性） */
    private volatile String pendingFileName = null;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        setContentView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------- WebSettings ----------
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // ---------- JS 桥：下载 / 自定义文件名 ----------
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        // ---------- 内部页面加载 ----------
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }
        });

        // ---------- 导入手机本地文件（<input type="file">） ----------
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    toast("设备上没有可用的文件选择器");
                    return false;
                }
                return true;
            }
        });

        // ---------- 下载产生的文件 ----------
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                // 优先消费预设文件名（一次性）
                String custom = pendingFileName;
                pendingFileName = null;
                handleDownload(url, userAgent, contentDisposition, mimeType, custom);
            }
        });

        // 旧系统下载到公共目录需要写权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                   != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_WRITE_PERMISSION);
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(resolveStartUrl());
        }
    }

    // ==================== 起始 URL 解析 ====================

    /**
     * 优先读取 assets/start_url.txt（URL 模式，内容为 http(s) 地址）；
     * 若不存在则回退到内置 index.html（文件模式）。
     */
    private String resolveStartUrl() {
        try (InputStream is = getAssets().open("start_url.txt")) {
            byte[] buf = new byte[8192];
            int len = 0;
            int n;
            while ((n = is.read(buf, len, buf.length - len)) > 0) {
                len += n;
                if (len == buf.length) break; // 防御：URL 不可能这么长
            }
            if (len > 0) {
                String url = new String(buf, 0, len, StandardCharsets.UTF_8).trim();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return url;
                }
            }
        } catch (Exception ignored) {
            // start_url.txt 不存在 → 文件模式
        }
        return DEFAULT_START_URL;
    }

    // ==================== 链接处理 ====================

    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return false; // 内部/外部 http(s) 链接都留在 WebView 内浏览
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            toast("无法打开链接: " + uri);
        }
        return true;
    }

    // ==================== 下载分发 ====================

    private void handleDownload(String url, String userAgent, String contentDisposition,
                                String mimeType, String customFileName) {
        if (url == null) return;

        if (url.startsWith("blob:")) {
            fetchBlobViaJs(url, mimeType, customFileName);
            return;
        }
        if (url.startsWith("data:")) {
            saveDataUrl(url, customFileName);
            return;
        }
        downloadWithDownloadManager(url, userAgent, contentDisposition, mimeType, customFileName);
    }

    private void downloadWithDownloadManager(String url, String userAgent,
                                             String contentDisposition, String mimeType,
                                             String customFileName) {
        String fileName = sanitizeFileName(customFileName);
        if (fileName == null) {
            fileName = sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimeType));
        }
        if (fileName == null) {
            fileName = buildFileName(mimeType);
        }

        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null && !mimeType.isEmpty()) req.setMimeType(mimeType);
            if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            req.setTitle(fileName);
            req.setDescription("正在下载…");

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException("DownloadManager 不可用");
            dm.enqueue(req);
            toast("已开始下载: " + fileName);
        } catch (Exception e) {
            // 兜底：交给系统浏览器下载
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException ex) {
                toast("下载失败: " + e.getMessage());
            }
        }
    }

    private void fetchBlobViaJs(String blobUrl, String mimeType, String customFileName) {
        String mime = (mimeType == null || mimeType.isEmpty()) ? "" : mimeType;
        String fname = (customFileName == null) ? "" : customFileName;
        String js = "(function(){"
                + "var x=new XMLHttpRequest();"
                + "x.open('GET'," + jsString(blobUrl) + ",true);"
                + "x.responseType='blob';"
                + "x.onload=function(){"
                + "  var fr=new FileReader();"
                + "  fr.onloadend=function(){"
                + "    var s=fr.result;var i=s.indexOf(',');"
                + "    AndroidBridge.onBlobReady(s.substring(i+1),"
                +                    jsString(mime) + "," + jsString(fname) + ");"
                + "  };"
                + "  fr.readAsDataURL(x.response);"
                + "};"
                + "x.onerror=function(){AndroidBridge.onBlobError('读取 blob 失败');};"
                + "x.send();"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private void saveDataUrl(String dataUrl, String customFileName) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) { toast("无效的 data URL"); return; }
        String meta    = dataUrl.substring(5, comma);
        String payload = dataUrl.substring(comma + 1);
        String mime = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;

        try {
            if (meta.contains(";base64")) {
                saveBytes(Base64.decode(payload, Base64.DEFAULT), mime, customFileName);
            } else {
                byte[] bytes = URLDecoder.decode(payload, "UTF-8")
                        .getBytes(StandardCharsets.UTF_8);
                saveBytes(bytes, mime, customFileName);
            }
        } catch (Exception e) {
            toast("数据解码失败: " + e.getMessage());
        }
    }

    // ==================== 保存到下载目录 ====================

    private void saveBytes(byte[] data, String mime, String customFileName) {
        String name = sanitizeFileName(customFileName);
        if (name == null) name = buildFileName(mime);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(data, name, mime);
        } else {
            saveViaLegacyStorage(data, name);
        }
    }

    private void saveViaMediaStore(byte[] data, String name, String mime) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE,
                (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        try {
            Uri item = getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (item == null) throw new IllegalStateException("无法创建文件条目");
            try (OutputStream os = getContentResolver().openOutputStream(item)) {
                if (os == null) throw new IllegalStateException("无法打开输出流");
                os.write(data);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(item, values, null, null);
            toast("已保存到「下载」目录: " + name);
        } catch (Exception e) {
            toast("保存失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void saveViaLegacyStorage(byte[] data, String name) {
        File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists() && !dir.mkdirs()) {
            toast("无法访问下载目录");
            return;
        }
        File out = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(data);
            toast("已保存到: " + out.getAbsolutePath());
        } catch (Exception e) {
            toast("保存失败: " + e.getMessage());
        }
    }

    private String buildFileName(String mime) {
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (ext == null || ext.isEmpty()) ext = "bin";
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "webapp_" + stamp + "." + ext;
    }

    /**
     * 清洗文件名：去除路径分隔符/非法字符/首尾空白与前导点，限制长度。
     * 空或非法 → 返回 null 让调用方回退到默认命名。
     */
    private static String sanitizeFileName(String name) {
        if (name == null) return null;
        name = name.trim();
        if (name.isEmpty()) return null;

        name = name.replace('\\', '_').replace('/', '_');
        name = name.replaceAll("[<>:\"|?*\\x00-\\x1F]", "_");

        while (name.startsWith(".") || name.startsWith(" ")) {
            name = name.substring(1);
        }
        while (name.endsWith(" ") || name.endsWith(".")) {
            if (name.isEmpty()) break;
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty()) return null;

        if (name.length() > 180) {
            int dot = name.lastIndexOf('.');
            String ext = (dot > 0) ? name.substring(dot) : "";
            if (ext.length() > 20) ext = "";
            name = name.substring(0, 180 - ext.length()) + ext;
        }
        return name;
    }

    // ==================== JS 桥 ====================

    private class Bridge {

        /**
         * 预设下一次下载的文件名（一次性）。
         * 适用于 <a download>、blob:、data:、普通 http(s) 等任意触发路径。
         */
        @JavascriptInterface
        public void setNextFileName(String fileName) {
            pendingFileName = fileName;
        }

        /**
         * 主动下载：适用于 http(s) URL。
         * @param url      下载地址
         * @param fileName 期望的文件名，可传 null/"" 走默认推断
         * @param mimeType MIME 类型，可传 null/"" 由服务端决定
         */
        @JavascriptInterface
        public void download(String url, String fileName, String mimeType) {
            if (url == null || url.isEmpty()) return;
            final String fname = (fileName == null || fileName.trim().isEmpty()) ? null : fileName;
            final String mime  = (mimeType == null || mimeType.trim().isEmpty()) ? null : mimeType;
            runOnUiThread(() -> handleDownload(url, null, null, mime, fname));
        }

        /**
         * 直接保存 Base64 数据为文件（绕过 URL 下载流程）。
         * @param base64   不含 data: 前缀的纯 Base64 内容
         * @param fileName 期望的文件名，可传 null/"" 走时间戳默认名
         * @param mime     MIME 类型，用于推断扩展名
         */
        @JavascriptInterface
        public void saveBase64(String base64, String fileName, String mime) {
            if (base64 == null) return;
            final String fname = (fileName == null || fileName.trim().isEmpty()) ? null : fileName;
            final String fmime = (mime == null || mime.trim().isEmpty()) ? null : mime;
            runOnUiThread(() -> {
                try {
                    saveBytes(Base64.decode(base64, Base64.DEFAULT), fmime, fname);
                } catch (IllegalArgumentException e) {
                    toast("数据解码失败");
                }
            });
        }

        // ---- blob: 下载回调（内部使用） ----

        @JavascriptInterface
        public void onBlobReady(String base64, String mime, String fileName) {
            final String fname = (fileName == null || fileName.isEmpty()) ? null : fileName;
            runOnUiThread(() -> {
                try {
                    saveBytes(Base64.decode(base64, Base64.DEFAULT), mime, fname);
                } catch (IllegalArgumentException e) {
                    toast("数据解码失败");
                }
            });
        }

        @JavascriptInterface
        public void onBlobError(String message) {
            runOnUiThread(() -> toast("下载失败: " + message));
        }
    }

    // ==================== 生命周期 / 工具 ====================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] result = null;
                if (resultCode == Activity.RESULT_OK) {
                    result = WebChromeClient.FileChooserParams
                            .parseResult(resultCode, data);
                }
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == REQ_WRITE_PERMISSION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) toast("未授予存储权限，下载可能失败");
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void toast(String msg) {
        runOnUiThread(() ->
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default: sb.append(c);
            }
        }
        return sb.append("'").toString();
    }
}