package org.webassist.unofficial;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import android.webkit.*;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.List;
import java.net.InetAddress;
import android.content.pm.PackageManager;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private static final String TAG = "webAssist";
    private static final String[] ALLOWED_DOMAINS = {"duck.ai", "duckduckgo.com", "huggingface.co", "phind.com", "qwant.com"};
    private static final boolean RESTRICT_DOMAINS = false;
    private static final boolean DARK_MODE_ENABLED = true;
    private static final boolean SAVE_DATA_ENABLED = true;
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int CREATE_FILE_REQUEST_CODE = 1002;

    private DnsOverHttps dnsOverHttps;
    private String pendingDownloadUrl;
    private String pendingMimeType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);
            setupUI();
            setupWebView();
            checkAndRequestPermissions();
        } catch (Exception e) {
            Log.e(TAG, "onCreate failed", e);
            finish();
        }
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[] {
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                    android.Manifest.permission.READ_MEDIA_AUDIO
            }, PERMISSION_REQUEST_CODE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[] {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, PERMISSION_REQUEST_CODE);
        }
    }

    private void setupUI() {
        webView = findViewById(R.id.WebView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        progressBar = findViewById(R.id.progressBar);

        swipeRefresh.setColorSchemeColors(Color.BLUE);
        swipeRefresh.setProgressViewOffset(false, 0, 200);
        progressBar.setMax(100);

        swipeRefresh.setOnRefreshListener(() -> {
            if (webView != null) webView.reload();
            else swipeRefresh.setRefreshing(false);
        });

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onScroll(boolean isAtTop) {
                runOnUiThread(() -> swipeRefresh.setEnabled(isAtTop));
            }
        }, "Android");
    }

    private void setupWebView() {
        try {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            registerForContextMenu(webView);
            setupDnsOverHttps();

            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setAllowFileAccess(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setLoadsImagesAutomatically(true);
            settings.setCacheMode(SAVE_DATA_ENABLED ?
                    WebSettings.LOAD_CACHE_ELSE_NETWORK :
                    WebSettings.LOAD_NO_CACHE);
            settings.setDisplayZoomControls(false);
            settings.setSupportZoom(true);
            settings.setBuiltInZoomControls(true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
            }
            CookieManager.getInstance().setAcceptCookie(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                settings.setForceDark(DARK_MODE_ENABLED ?
                        WebSettings.FORCE_DARK_ON :
                        WebSettings.FORCE_DARK_OFF);
            }

            configureWebViewClients();
            webView.loadUrl("https://qwant.com/", new HashMap<String, String>() {{
                put("Save-Data", "on");
            }});
        } catch (Exception e) {
            Log.e(TAG, "WebView setup failed", e);
        }
    }

    private void setupDnsOverHttps() {
        try {
            dnsOverHttps = new DnsOverHttps.Builder()
                    .client(new OkHttpClient.Builder()
                            .connectTimeout(30, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .build())
                    .url(HttpUrl.get("https://dns.adguard-dns.com/dns-query"))
                    .includeIPv6(false)
                    .build();
        } catch (Exception e) {
            Log.e(TAG, "DNS setup failed", e);
        }
    }

    private void configureWebViewClients() {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                swipeRefresh.setRefreshing(false);
                progressBar.setVisibility(View.GONE);
                view.evaluateJavascript(
                        "window.addEventListener('scroll', function() {" +
                                "  Android.onScroll(window.scrollY === 0);" +
                                "}, true);", null);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    if (request != null && request.getUrl() != null) {
                        String host = request.getUrl().getHost();
                        if (handleDnsRequest(host)) {
                            return new WebResourceResponse("text/plain", "UTF-8",
                                    new ByteArrayInputStream("".getBytes()));
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Request interception failed", e);
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String mimeType = URLConnection.guessContentTypeFromName(url);

                if (isDownloadableFile(url, mimeType)) {
                    initiateDownload(url, mimeType);
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(newProgress);
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                initiateDownload(url, mimeType));
    }

    private boolean isDownloadableFile(String url, String mimeType) {
        if (mimeType == null) mimeType = "application/octet-stream";

        return !url.startsWith("http://") && !url.startsWith("https://") ||
                mimeType.startsWith("application/") ||
                mimeType.startsWith("video/") ||
                mimeType.startsWith("audio/") ||
                mimeType.startsWith("image/") ||
                url.endsWith(".pdf") ||
                url.endsWith(".doc") ||
                url.endsWith(".docx") ||
                url.endsWith(".xls") ||
                url.endsWith(".xlsx") ||
                url.endsWith(".7z") ||
                url.endsWith(".zip") ||
                url.endsWith(".rar");
    }

    private void initiateDownload(String url, String mimeType) {
        pendingDownloadUrl = url;
        pendingMimeType = mimeType != null ? mimeType : "application/octet-stream";
        String fileName = URLUtil.guessFileName(url, null, pendingMimeType);

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(pendingMimeType)
                .putExtra(Intent.EXTRA_TITLE, fileName);

        try {
            startActivityForResult(intent, CREATE_FILE_REQUEST_CODE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file manager found", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean handleDnsRequest(String host) {
        if (host == null) return false;
        if (dnsOverHttps != null) {
            try {
                List<InetAddress> addresses = dnsOverHttps.lookup(host);
                if (addresses.isEmpty()) return true;
            } catch (Exception e) {
                Log.e(TAG, "DNS lookup failed: " + host, e);
            }
        }
        if (RESTRICT_DOMAINS) {
            for (String domain : ALLOWED_DOMAINS) {
                if (host.endsWith(domain)) return false;
            }
            return true;
        }
        return false;
    }

    private void downloadWithSaf(String url, Uri destinationUri) {
        new Thread(() -> {
            InputStream input = null;
            OutputStream output = null;
            try {
                URL downloadUrl = new URL(url);
                HttpURLConnection connection = (HttpURLConnection) downloadUrl.openConnection();
                connection.setRequestProperty("Save-Data", "on");
                connection.connect();

                input = connection.getInputStream();
                output = getContentResolver().openOutputStream(destinationUri);

                if (output != null) {
                    byte[] buffer = new byte[4096];
                    int count;

                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }

                    output.flush();
                    runOnUiThread(() ->
                            Toast.makeText(this, "Download completed", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show());
            } finally {
                try {
                    if (output != null) output.close();
                    if (input != null) input.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing streams", e);
                }
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CREATE_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null && pendingDownloadUrl != null) {
                Uri destinationUri = data.getData();
                downloadWithSaf(pendingDownloadUrl, destinationUri);
                pendingDownloadUrl = null;
                pendingMimeType = null;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        if (requestCode == PERMISSION_REQUEST_CODE && !allPermissionsGranted(results)) {
            Toast.makeText(this, "Storage permission required for downloads", Toast.LENGTH_LONG).show();
        }
    }

    private boolean allPermissionsGranted(int[] results) {
        for (int result : results) {
            if (result != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        if (v instanceof WebView) {
            WebView.HitTestResult result = ((WebView)v).getHitTestResult();
            if (result.getExtra() != null) {
                String url = result.getExtra();
                String mimeType = URLConnection.guessContentTypeFromName(url);
                if (isDownloadableFile(url, mimeType)) {
                    initiateDownload(url, mimeType);
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.clearCache(true);
            webView.clearHistory();
            webView.clearFormData();
            webView.clearSslPreferences();
            CookieManager.getInstance().removeAllCookies(null);
            WebStorage.getInstance().deleteAllData();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (webView != null && keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}