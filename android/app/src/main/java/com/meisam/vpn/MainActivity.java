package com.meisam.vpn;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int VPN_REQUEST_CODE = 2026;
    private static final String PREF_NAME = "veepn_config";

    private WebView webView;
    private ExecutorService threadPool;
    private VeePNApi api;
    private String selectedRegionCode = "gb-lnd";
    private String selectedServerName = "United Kingdom";

    private final BroadcastReceiver vpnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MeisamVpnService.ACTION_STATUS_CHANGED.equals(intent.getAction())) {
                String status = intent.getStringExtra("status");
                String serverName = intent.getStringExtra("server_name");
                long bytes = intent.getLongExtra("total_bytes", 0);
                double mb = bytes / (1024.0 * 1024.0);
                String formatted = mb < 0.1 ? "0 MB" : (mb >= 1000 ? String.format("%.2f GB", mb / 1000.0) : String.format("%.1f MB", mb));
                double pct = Math.min(100.0, (mb / 1000.0) * 100.0);

                runOnUiThread(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript("if(window.setVpnState) window.setVpnState('" + status + "', '" + serverName + "');", null);
                        webView.evaluateJavascript("if(window.updateTraffic) window.updateTraffic(" + String.format("%.1f", mb) + ", '" + formatted + "', " + String.format("%.1f", pct) + ");", null);
                    }
                });
            }
        }
    };

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        threadPool = Executors.newCachedThreadPool();
        api = new VeePNApi(this);

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        selectedRegionCode = prefs.getString("selected_region", "gb-lnd");
        selectedServerName = prefs.getString("selected_server_name", "United Kingdom");

        webView = findViewById(R.id.webView);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectBridgePolyfill();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/www/index.html");

        IntentFilter filter = new IntentFilter(MeisamVpnService.ACTION_STATUS_CHANGED);
        androidx.core.content.ContextCompat.registerReceiver(
                this, vpnReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        );

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // If sheet is open in webview, close it; otherwise finish
                webView.evaluateJavascript(
                        "(function(){ " +
                        "  var s1 = document.getElementById('scrim'); " +
                        "  var s2 = document.getElementById('usageScrim'); " +
                        "  if(s1 && s1.classList.contains('open')) { s1.classList.remove('open'); return 'closed'; } " +
                        "  if(s2 && s2.classList.contains('open')) { s2.classList.remove('open'); return 'closed'; } " +
                        "  return 'none'; " +
                        "})()",
                        value -> {
                            if (!"\"closed\"".equals(value)) {
                                finish();
                            }
                        }
                );
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && "TRIGGER_CONNECT".equals(intent.getAction())) {
            toggleConnect();
        }
    }

    private void injectBridgePolyfill() {
        String js = "window.isAndroid = true;\n" +
                "var pingCallbacks = {};\n" +
                "window.onPingResult = function(callId, ping) {\n" +
                "  if (pingCallbacks[callId]) {\n" +
                "    pingCallbacks[callId](ping);\n" +
                "    delete pingCallbacks[callId];\n" +
                "  }\n" +
                "};\n" +
                "window.measurePingAsync = function(code) {\n" +
                "  return new Promise(function(resolve) {\n" +
                "    var callId = 'p_' + Math.random().toString(36).substr(2, 9);\n" +
                "    pingCallbacks[callId] = function(p) {\n" +
                "      resolve(p > 0 ? p : null);\n" +
                "    };\n" +
                "    window.AndroidBridge.measurePingAsync(code, callId);\n" +
                "  });\n" +
                "};\n" +
                "window.pywebview = {\n" +
                "  api: {\n" +
                "    get_initial_state: function() {\n" +
                "      return Promise.resolve(JSON.parse(window.AndroidBridge.getInitialState()));\n" +
                "    },\n" +
                "    toggle_connect: function() {\n" +
                "      window.AndroidBridge.toggleConnect();\n" +
                "    },\n" +
                "    select_server: function(code, country, city) {\n" +
                "      window.AndroidBridge.selectServer(code, country, city);\n" +
                "    },\n" +
                "    measure_ping: function(code) {\n" +
                "      return window.measurePingAsync(code);\n" +
                "    },\n" +
                "    toggle_widget: function(enabled) {\n" +
                "      window.AndroidBridge.toggleWidget(enabled);\n" +
                "    },\n" +
                "    set_theme: function(theme) {\n" +
                "      window.AndroidBridge.setTheme(theme);\n" +
                "    }\n" +
                "  }\n" +
                "};\n" +
                "window.dispatchEvent(new Event('pywebviewready'));\n";
        webView.evaluateJavascript(js, null);
    }

    private void toggleConnect() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String currentStatus = prefs.getString("vpn_status", "disconnected");

        if ("connected".equals(currentStatus) || "connecting".equals(currentStatus)) {
            Intent stopIntent = new Intent(this, MeisamVpnService.class);
            stopIntent.setAction(MeisamVpnService.ACTION_DISCONNECT);
            startService(stopIntent);
        } else {
            Intent vpnPrepare = VpnService.prepare(this);
            if (vpnPrepare != null) {
                startActivityForResult(vpnPrepare, VPN_REQUEST_CODE);
            } else {
                startVpnService();
            }
        }
    }

    private void startVpnService() {
        Intent startIntent = new Intent(this, MeisamVpnService.class);
        startIntent.setAction(MeisamVpnService.ACTION_CONNECT);
        startIntent.putExtra("region_code", selectedRegionCode);
        startIntent.putExtra("server_name", selectedServerName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent);
        } else {
            startService(startIntent);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            startVpnService();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(vpnReceiver);
        } catch (Exception ignored) {}
        if (threadPool != null) threadPool.shutdown();
        super.onDestroy();
    }

    public class WebAppInterface {
        @JavascriptInterface
        public String getInitialState() {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            JSONObject obj = new JSONObject();
            try {
                obj.put("status", prefs.getString("vpn_status", "disconnected"));
                obj.put("server_code", selectedRegionCode);
                obj.put("server_name", selectedServerName);
                obj.put("theme", prefs.getString("theme", "dark"));
                obj.put("widget_enabled", prefs.getBoolean("widget_enabled", false));
                obj.put("version", "Version 3.5 (2026)");
                obj.put("data_mb", 0.0);
                obj.put("data_formatted", "0 MB");
                obj.put("data_percent", 0.0);
            } catch (Exception ignored) {}
            return obj.toString();
        }

        @JavascriptInterface
        public void toggleConnect() {
            runOnUiThread(MainActivity.this::toggleConnect);
        }

        @JavascriptInterface
        public void selectServer(String code, String country, String city) {
            selectedRegionCode = code;
            selectedServerName = country;
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString("selected_region", code)
                    .putString("selected_server_name", country)
                    .apply();
        }

        @JavascriptInterface
        public int measurePing(String code) {
            return api.testServerPing(code);
        }

        @JavascriptInterface
        public void measurePingAsync(final String code, final String callId) {
            threadPool.execute(() -> {
                int latency = api.testServerPing(code);
                runOnUiThread(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript("if(window.onPingResult) window.onPingResult('" + callId + "', " + latency + ");", null);
                    }
                });
            });
        }

        @JavascriptInterface
        public void toggleWidget(boolean enabled) {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean("widget_enabled", enabled).apply();

            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AppWidgetManager appWidgetManager = getSystemService(AppWidgetManager.class);
                ComponentName provider = new ComponentName(MainActivity.this, MeisamAppWidget.class);
                if (appWidgetManager.isRequestPinAppWidgetSupported()) {
                    Intent pinnedWidgetCallbackIntent = new Intent(MainActivity.this, MeisamAppWidget.class);
                    PendingIntent successCallback = PendingIntent.getBroadcast(
                            MainActivity.this, 0, pinnedWidgetCallbackIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    );
                    appWidgetManager.requestPinAppWidget(provider, null, successCallback);
                }
            }
        }

        @JavascriptInterface
        public void setTheme(String theme) {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString("theme", theme).apply();
        }
    }
}
