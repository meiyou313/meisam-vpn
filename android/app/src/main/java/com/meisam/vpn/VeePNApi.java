package com.meisam.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class VeePNApi {
    private static final String PREF_NAME = "veepn_config";
    private static final String[] DOMAINS = {
            "https://antpeak.com",
            "https://hibchr.com",
            "https://hisball.com",
            "https://bitphox.com",
            "https://freloop.com"
    };

    private static final Map<String, JSONObject> CACHED_ENDPOINTS = new ConcurrentHashMap<>();
    static {
        try {
            CACHED_ENDPOINTS.put("gb-lnd", new JSONObject("{\"host\":\"erith-5-uk.nexorcdn.com\",\"port\":19666,\"username\":\"371f6ca0caeca591ec4af62ca9849da6\",\"password\":\"HTIEUUN8SMKODaKK\"}"));
            CACHED_ENDPOINTS.put("nl", new JSONObject("{\"host\":\"maasdam-3-nl.nexorcdn.com\",\"port\":16644,\"username\":\"371f6ca0caeca591ec4af62ca9849da6\",\"password\":\"HTIEUUN8SMKODaKK\"}"));
            CACHED_ENDPOINTS.put("fr-prs", new JSONObject("{\"host\":\"pinot-fr.nexorcdn.com\",\"port\":44976,\"username\":\"371f6ca0caeca591ec4af62ca9849da6\",\"password\":\"HTIEUUN8SMKODaKK\"}"));
            CACHED_ENDPOINTS.put("us-va", new JSONObject("{\"host\":\"trout-east-5-us.nexorcdn.com\",\"port\":13376,\"username\":\"371f6ca0caeca591ec4af62ca9849da6\",\"password\":\"HTIEUUN8SMKODaKK\"}"));
            CACHED_ENDPOINTS.put("us-or", new JSONObject("{\"host\":\"trout-west-5-us.nexorcdn.com\",\"port\":25310,\"username\":\"371f6ca0caeca591ec4af62ca9849da6\",\"password\":\"HTIEUUN8SMKODaKK\"}"));
            CACHED_ENDPOINTS.put("sg", new JSONObject("{\"host\":\"singha-sg.nexorcdn.com\",\"port\":53179,\"username\":\"371f6ca0caeca591ec4af62ca9849da6\",\"password\":\"HTIEUUN8SMKODaKK\"}"));
            CACHED_ENDPOINTS.put("ru-spb", new JSONObject("{\"host\":\"chalo-ru.nexorcdn.com\",\"port\":51320,\"username\":\"371f6ca0caeca591ec4af62ca9849da6\",\"password\":\"HTIEUUN8SMKODaKK\"}"));
        } catch (Exception ignored) {}
    }

    private final Context context;
    private String udid;
    private String token;
    private long tokenExpiry = 0;
    private String activeDomain = DOMAINS[0];

    public VeePNApi(Context context) {
        this.context = context.getApplicationContext();
        loadConfig();
    }

    private void loadConfig() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.udid = prefs.getString("udid", null);
        this.token = prefs.getString("token", null);
        this.tokenExpiry = prefs.getLong("expiry", 0);
        if (this.udid == null) {
            this.udid = UUID.randomUUID().toString();
            saveConfig();
        }
    }

    private void saveConfig() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString("udid", this.udid)
                .putString("token", this.token)
                .putLong("expiry", this.tokenExpiry)
                .apply();
    }

    public synchronized String getToken() {
        long now = System.currentTimeMillis() / 1000;
        if (this.token != null && now < this.tokenExpiry - 3600) {
            return this.token;
        }

        JSONObject deviceInfo = new JSONObject();
        try {
            deviceInfo.put("udid", this.udid);
            deviceInfo.put("appVersion", "4.0.2");
            deviceInfo.put("platform", "android");
            deviceInfo.put("platformVersion", "Android 14");
            deviceInfo.put("timeZone", "Asia/Tehran");
            deviceInfo.put("deviceName", "Android Client");
        } catch (Exception ignored) {}

        for (String domain : DOMAINS) {
            try {
                URL url = new URL(domain + "/api/launch/");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(deviceInfo.toString().getBytes("utf-8"));
                }

                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();

                    JSONObject res = new JSONObject(sb.toString());
                    if (res.optBoolean("success")) {
                        JSONObject data = res.getJSONObject("data");
                        this.token = data.getString("accessToken");
                        this.activeDomain = domain;
                        this.tokenExpiry = now + (25 * 86400);
                        saveConfig();
                        return this.token;
                    }
                }
            } catch (Exception ignored) {}
        }
        return "cached_token";
    }

    public JSONArray getServersForRegion(String regionCode) {
        String code = regionCode != null ? regionCode.toLowerCase() : "gb-lnd";
        JSONObject cached = CACHED_ENDPOINTS.get(code);
        if (cached == null) cached = CACHED_ENDPOINTS.get("gb-lnd");

        if (cached != null) {
            try {
                JSONArray arr = new JSONArray();
                JSONObject s = new JSONObject();
                JSONArray addrs = new JSONArray();
                addrs.put(cached.getString("host"));
                s.put("addresses", addrs);
                s.put("port", cached.getInt("port"));
                s.put("username", cached.getString("username"));
                s.put("password", cached.getString("password"));
                arr.put(s);
                return arr;
            } catch (Exception ignored) {}
        }

        JSONArray fallback = new JSONArray();
        try {
            JSONObject s = new JSONObject();
            JSONArray addrs = new JSONArray();
            addrs.put("erith-5-uk.nexorcdn.com");
            s.put("addresses", addrs);
            s.put("port", 19666);
            s.put("username", "371f6ca0caeca591ec4af62ca9849da6");
            s.put("password", "HTIEUUN8SMKODaKK");
            fallback.put(s);
        } catch (Exception ignored) {}
        return fallback;
    }

    public int testServerPing(String regionCode) {
        String code = regionCode != null ? regionCode.toLowerCase() : "gb-lnd";
        JSONObject s = CACHED_ENDPOINTS.get(code);
        if (s == null) s = CACHED_ENDPOINTS.get("gb-lnd");
        if (s == null) return -1;

        try {
            String host = s.getString("host");
            int port = s.getInt("port");
            String username = s.getString("username");
            String password = s.getString("password");

            long t0 = System.currentTimeMillis();
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (Socket raw = new Socket()) {
                raw.connect(new InetSocketAddress(host, port), 1800);
                try (SSLSocket ssl = (SSLSocket) factory.createSocket(raw, host, port, true)) {
                    ssl.setSoTimeout(1800);
                    ssl.startHandshake();

                    String auth = android.util.Base64.encodeToString((username + ":" + password).getBytes(), android.util.Base64.NO_WRAP);
                    String cmd = "CONNECT web.telegram.org:443 HTTP/1.1\r\n" +
                            "Host: web.telegram.org:443\r\n" +
                            "Proxy-Authorization: Basic " + auth + "\r\n" +
                            "User-Agent: Mozilla/5.0\r\n" +
                            "Proxy-Connection: Keep-Alive\r\n\r\n";
                    ssl.getOutputStream().write(cmd.getBytes());
                    ssl.getOutputStream().flush();

                    byte[] buf = new byte[512];
                    int len = ssl.getInputStream().read(buf);
                    long latency = System.currentTimeMillis() - t0;
                    if (len > 0) {
                        return (int) Math.max(20, latency);
                    }
                }
            }
        } catch (Exception e) {
            return -1;
        }
        return -1;
    }
}
