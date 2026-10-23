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
import java.util.UUID;

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

    public synchronized String getToken() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        if (this.token != null && now < this.tokenExpiry - 3600) {
            return this.token;
        }

        JSONObject deviceInfo = new JSONObject();
        deviceInfo.put("udid", this.udid);
        deviceInfo.put("appVersion", "4.0.2");
        deviceInfo.put("platform", "android");
        deviceInfo.put("platformVersion", "Android 14");
        deviceInfo.put("timeZone", "Asia/Tehran");
        deviceInfo.put("deviceName", "Android Client");

        for (String domain : DOMAINS) {
            try {
                URL url = new URL(domain + "/api/launch/");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
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
            } catch (Exception ignored) {
            }
        }
        throw new Exception("Unable to contact central VeePN API servers");
    }

    public JSONArray getServersForRegion(String regionCode) throws Exception {
        String authToken = getToken();
        JSONObject payload = new JSONObject();
        payload.put("protocol", "https");
        payload.put("region", regionCode);
        payload.put("type", 0);

        URL url = new URL(this.activeDomain + "/api/server/list/");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes("utf-8"));
        }

        if (conn.getResponseCode() == 200) {
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            JSONObject res = new JSONObject(sb.toString());
            if (res.optBoolean("success")) {
                return res.getJSONArray("data");
            }
        }
        throw new Exception("Server list could not be fetched");
    }

    public int testServerPing(String regionCode) {
        try {
            JSONArray srvs = getServersForRegion(regionCode);
            if (srvs.length() == 0) return -1;
            JSONObject s = srvs.getJSONObject(0);
            String host = s.getJSONArray("addresses").getString(0);
            int port = s.getInt("port");
            String username = s.getString("username");
            String password = s.getString("password");

            long t0 = System.currentTimeMillis();
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (Socket raw = new Socket()) {
                raw.connect(new InetSocketAddress(host, port), 4000);
                try (SSLSocket ssl = (SSLSocket) factory.createSocket(raw, host, port, true)) {
                    ssl.setSoTimeout(4000);
                    ssl.startHandshake();

                    String auth = android.util.Base64.encodeToString((username + ":" + password).getBytes(), android.util.Base64.NO_WRAP);
                    String cmd = "CONNECT web.telegram.org:443 HTTP/1.1\r\n" +
                            "Host: web.telegram.org:443\r\n" +
                            "Proxy-Authorization: Basic " + auth + "\r\n" +
                            "User-Agent: Mozilla/5.0\r\n" +
                            "Proxy-Connection: Keep-Alive\r\n\r\n";
                    ssl.getOutputStream().write(cmd.getBytes());
                    ssl.getOutputStream().flush();

                    byte[] buf = new byte[256];
                    int len = ssl.getInputStream().read(buf);
                    long latency = System.currentTimeMillis() - t0;
                    if (len > 0) {
                        String resp = new String(buf, 0, len);
                        if (resp.contains("200")) {
                            return (int) latency;
                        }
                    }
                }
            }
        } catch (Exception e) {
            return -1;
        }
        return -1;
    }
}
