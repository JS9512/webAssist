package org.webassist.unofficial;

import static android.webkit.WebView.HitTestResult.IMAGE_TYPE;
import static android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE;
import static android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Objects;

public class MainActivity extends Activity {

    private static final ArrayList<String> allowedDomains = new ArrayList<>();
    private final static int FILE_CHOOSER_REQUEST_CODE = 1;
    private final Context context = this;
    private WebView WebView = null;
    private CookieManager CookieManager = null;
    private final String TAG = "Qwant";
    private final String urlToLoad = "https://qwant.com/";
    private ValueCallback<Uri[]> mUploadMessage;
    private String urlToDownload; // Variable to hold the URL to download

    private final boolean restrictDomains = false;

    private static void initURLs() {
        // Allowed Domains
        allowedDomains.add("duck.ai");
        allowedDomains.add("duckduckgo.com");
        allowedDomains.add("huggingface.co");
        allowedDomains.add("phind.com");
    }

    private void downloadFile(String url) {
        // Set the URL to download
        urlToDownload = url; // Store the URL for later use

        // Create an Intent to open the file picker
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // Set the MIME type for the file
        intent.putExtra(Intent.EXTRA_TITLE, URLUtil.guessFileName(url, null, null)); // Suggest a filename
        startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE); // Start the file picker
    }

    @Override
    protected void onPause() {
        if (CookieManager != null) CookieManager.flush();
        super.onPause();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Create the WebView
        WebView = findViewById(R.id.WebView);

        // Set cookie options
        CookieManager = android.webkit.CookieManager.getInstance();
        CookieManager.setAcceptCookie(true);
        CookieManager.setAcceptThirdPartyCookies(WebView, false);

        // Restrict what gets loaded
        initURLs();
        registerForContextMenu(WebView);

        WebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage.message().contains("NotAllowedError: Write permission denied.") || consoleMessage.message().contains("DOMException")) {
                    Toast.makeText(context, R.string.error_copy, Toast.LENGTH_LONG).show();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
                    }
                }
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                }

                mUploadMessage = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                return true;
            }
        });

        WebView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(final WebView view, WebResourceRequest request) {
                if (request.getUrl().toString().equals("about:blank")) {
                    return null;
                }
                if (!request.getUrl().toString().startsWith("https://")) {
                    Log.d(TAG, "[shouldInterceptRequest][NON-HTTPS] Blocked access to " + request.getUrl().toString());
                    return new WebResourceResponse("text/javascript", "UTF-8", null); // Deny URLs that aren't HTTPS
                }
                if (restrictDomains) {
                    boolean allowed = false;
                    for (String domain : allowedDomains) {
                        if (Objects.requireNonNull(request.getUrl().getHost()).endsWith(domain)) {
                            allowed = true;
                            break;
                        }
                    }
                    if (!allowed) {
                        Log.d(TAG, "[shouldInterceptRequest][NOT ON ALLOWLIST] Blocked access to " + request.getUrl().getHost());
                        Log.d(TAG, "[shouldInterceptRequest][NOT ON ALLOWLIST] Blocked access to " + request.getUrl());
                        return new WebResourceResponse("text/javascript", "UTF-8", null); // Deny URLs not on ALLOWLIST
                    }
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.getUrl().toString().equals("about:blank")) {
                    return false;
                }
                if (!request.getUrl().toString().startsWith("https://")) {
                    Log.d(TAG, "[shouldOverrideUrlLoading][NON-HTTPS] Blocked access to " + request.getUrl().toString());
                    return true; // Deny URLs that aren't HTTPS
                }
                if (restrictDomains) {
                    boolean allowed = false;
                    for (String domain : allowedDomains) {
                        if (Objects.requireNonNull(request.getUrl().getHost()).endsWith(domain)) {
                            allowed = true;
                            break;
                        }
                    }
                    if (!allowed) {
                        Log.d(TAG, "[shouldOverrideUrlLoading][NOT ON ALLOWLIST] Blocked access to " + request.getUrl().getHost());
                        return true; // Deny URLs not on ALLOWLIST
                    }
                }
                return false;
            }
        });

        // Set more options
        WebSettings webSettings = WebView.getSettings();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);
            webSettings.setAllowFileAccess(true);
            webSettings.setAllowFileAccessFromFileURLs(true);
            webSettings.setAllowUniversalAccessFromFileURLs(true);
            webSettings.setAllowContentAccess(true);
            webSettings.setSaveFormData(true);
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            webSettings.setOffscreenPreRaster(true);
            webSettings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
            webSettings.setDatabaseEnabled(true);
            webSettings.setDisplayZoomControls(false);
            webSettings.setSupportZoom(true);
            webSettings.setBuiltInZoomControls(true);
            webSettings.setDisplayZoomControls(false);
            webSettings.setSupportZoom(true);
            webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
            webSettings.setMediaPlaybackRequiresUserGesture(false);
            webSettings.setUserAgentString("Mozilla/5.0 (Linux; Unspecified Device) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.79 Mobile Safari/537.36");
        }

        // Load initial website
        WebView.loadUrl(urlToLoad);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Credit (CC BY-SA 3.0): https://stackoverflow.com/a/6077173
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (WebView.canGoBack() && !Objects.equals(WebView.getUrl(), "about:blank")) {
                    WebView.goBack();
                } else {
                    finish();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                if (intent != null) {
                    Uri uri = intent.getData(); // Get the URI where the user wants to save the file
                    if (uri != null) {
                        // Start a background thread to download the file
                        new Thread(() -> {
                            try {
                                // Open a connection to the URL
                                URL url = new URL(urlToDownload); // Use the URL you want to download
                                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                                connection.connect();

                                // Check for successful response code
                                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                                    return;
                                }

                                // Get the input stream
                                InputStream inputStream = connection.getInputStream();
                                OutputStream outputStream = getContentResolver().openOutputStream(uri); // Open output stream to the selected URI

                                // Write the input stream to the output stream
                                byte[] buffer = new byte[4096];
                                int bytesRead;
                                while ((bytesRead = inputStream.read(buffer)) != -1) {
                                    Objects.requireNonNull(outputStream).write(buffer, 0, bytesRead);
                                }

                                // Close streams
                                Objects.requireNonNull(outputStream).close();
                                inputStream.close();
                            } catch (IOException e) {
                                e.printStackTrace(); // Handle exceptions
                            }
                        }).start();
                    }
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, you can now access the storage
                Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show();
            } else {
                // Permission denied, you cannot access the storage
                Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        WebView.HitTestResult result = WebView.getHitTestResult();
        String url = "";
        if (result.getExtra() != null) {
            if (result.getType() == IMAGE_TYPE) {
                url = result.getExtra();
                // Set the URL to download
                urlToDownload = url; // Store the URL for later use
                Toast.makeText(this, "IMAGE: " + url, Toast.LENGTH_SHORT).show();
                if (url != null && !url.isEmpty() && !url.contains("/avatar.jpg?")) {
                    Toast.makeText(this, getString(R.string.downloading), Toast.LENGTH_LONG).show();
                    downloadFile(url); // Call the modified downloadFile method
                }
            } else if (result.getType() == SRC_IMAGE_ANCHOR_TYPE || result.getType() == SRC_ANCHOR_TYPE) {
                if (result.getType() == SRC_IMAGE_ANCHOR_TYPE) {
                    // Create a background thread that has a Looper
                    HandlerThread handlerThread = new HandlerThread("HandlerThread");
                    handlerThread.start();
                    // Create a handler to execute tasks in the background thread.
                    Handler backgroundHandler = new Handler(handlerThread.getLooper());
                    Message msg = backgroundHandler.obtainMessage();
                    WebView.requestFocusNodeHref(msg);
                    url = (String) msg.getData().get("url");
                    Toast.makeText(this, "SRC_IMAGE: " + url, Toast.LENGTH_SHORT).show();
                } else if (result.getType() == SRC_ANCHOR_TYPE) {
                    url = result.getExtra();
                    // Set the URL to download and store it for later use
                    urlToDownload = url;
                    Toast.makeText(this, "SRC_ANCHOR: " + url, Toast.LENGTH_SHORT).show();
                }
                String host = Uri.parse(url).getHost();
                if (host != null) {
                    boolean allowed = false;
                    for (String domain : allowedDomains) {
                        if (host.endsWith(domain)) {
                            allowed = true;
                            break;
                        }
                    }
                    if (!allowed) {  // Copy URLs that are not allowed to open to clipboard
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText(getString(R.string.app_name), url);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(this, getString(R.string.url_copied), Toast.LENGTH_SHORT).show();
                    } else {
                        downloadFile(url); // Call the modified downloadFile method
                    }
                }
            }
        }
    }
}


