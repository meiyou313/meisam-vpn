package com.meisam.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class MeisamVpnService extends VpnService {
    private static final String TAG = "MeisamVpnService";
    public static final String ACTION_CONNECT = "com.meisam.vpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.meisam.vpn.DISCONNECT";
    public static final String ACTION_STATUS_CHANGED = "com.meisam.vpn.STATUS_CHANGED";
    private static final String CHANNEL_ID = "meisam_vpn_channel";
    private static final int NOTIF_ID = 1001;
    private static final String PREF_NAME = "veepn_config";
    private static final int LOCAL_PROXY_PORT = 10809;

    private ParcelFileDescriptor vpnInterface = null;
    private ServerSocket localProxyServer = null;
    private boolean isRunning = false;
    private ExecutorService threadPool;
    private long totalBytes = 0;
    private String currentServerName = "United Kingdom";

    @Override
    public void onCreate() {
        super.onCreate();
        threadPool = Executors.newCachedThreadPool();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.vpn_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.vpn_channel_desc));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent contentIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingContent = PendingIntent.getActivity(this, 0, contentIntent, flags);

        Intent disconnectIntent = new Intent(this, MeisamVpnService.class);
        disconnectIntent.setAction(ACTION_DISCONNECT);
        PendingIntent pendingDisconnect = PendingIntent.getService(this, 1, disconnectIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pendingContent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.disconnect), pendingDisconnect)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_DISCONNECT.equals(action)) {
            stopVpn();
            return START_NOT_STICKY;
        }

        if (ACTION_CONNECT.equals(action)) {
            final String regionCode = intent.getStringExtra("region_code") != null ?
                    intent.getStringExtra("region_code") : "gb-lnd";
            final String serverName = intent.getStringExtra("server_name") != null ?
                    intent.getStringExtra("server_name") : "United Kingdom";
            this.currentServerName = serverName;

            updateStatus("connecting", serverName);
            startForeground(NOTIF_ID, buildNotification("Connecting to " + serverName + "…"));

            threadPool.execute(() -> connectTunnel(regionCode, serverName));
        }

        return START_STICKY;
    }

    private void updateStatus(String status, String serverName) {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString("vpn_status", status).apply();

        MeisamAppWidget.updateAllWidgets(this);

        Intent b = new Intent(ACTION_STATUS_CHANGED);
        b.putExtra("status", status);
        b.putExtra("server_name", serverName);
        b.putExtra("total_bytes", totalBytes);
        sendBroadcast(b);
    }

    private void connectTunnel(String regionCode, String serverName) {
        try {
            VeePNApi api = new VeePNApi(this);
            JSONArray servers = api.getServersForRegion(regionCode);
            if (servers.length() == 0) throw new Exception("No servers available for " + regionCode);

            JSONObject srv = servers.getJSONObject(0);
            String remoteHost = srv.getJSONArray("addresses").getString(0);
            int remotePort = srv.getInt("port");
            String username = srv.getString("username");
            String password = srv.getString("password");

            startLocalProxy(remoteHost, remotePort, username, password);
            establishVpnInterface();

            isRunning = true;
            updateStatus("connected", serverName);
            startForeground(NOTIF_ID, buildNotification("Connected: " + serverName));

        } catch (Exception e) {
            Log.e(TAG, "Connect error: ", e);
            stopVpn();
        }
    }

    private void establishVpnInterface() throws Exception {
        Builder builder = new Builder();
        builder.setSession("Meisam VPN");
        builder.addAddress("10.0.0.2", 24);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("1.1.1.1");
        builder.addDnsServer("8.8.8.8");
        builder.setMtu(1500);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", LOCAL_PROXY_PORT));
        }

        vpnInterface = builder.establish();
    }

    private void startLocalProxy(String remoteHost, int remotePort, String username, String password) throws Exception {
        if (localProxyServer != null && !localProxyServer.isClosed()) {
            localProxyServer.close();
        }
        localProxyServer = new ServerSocket(LOCAL_PROXY_PORT);
        threadPool.execute(() -> {
            while (!localProxyServer.isClosed()) {
                try {
                    Socket client = localProxyServer.accept();
                    threadPool.execute(() -> handleClientSocket(client, remoteHost, remotePort, username, password));
                } catch (Exception ignored) {
                    break;
                }
            }
        });
    }

    private void handleClientSocket(Socket client, String remoteHost, int remotePort, String username, String password) {
        try {
            protect(client);
            InputStream clientIn = client.getInputStream();
            OutputStream clientOut = client.getOutputStream();

            byte[] reqBuf = new byte[4096];
            int reqLen = clientIn.read(reqBuf);
            if (reqLen <= 0) {
                client.close();
                return;
            }

            String firstLine = new String(reqBuf, 0, reqLen).split("\r\n")[0];
            String[] parts = firstLine.split(" ");
            if (parts.length < 2) {
                client.close();
                return;
            }

            String target = parts[1];
            Socket rawSocket = new Socket();
            protect(rawSocket);
            rawSocket.connect(new InetSocketAddress(remoteHost, remotePort), 6000);

            SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket sslSocket = (SSLSocket) sf.createSocket(rawSocket, remoteHost, remotePort, true);
            sslSocket.startHandshake();

            String auth = Base64.encodeToString((username + ":" + password).getBytes(), Base64.NO_WRAP);
            String connectCmd = "CONNECT " + target + " HTTP/1.1\r\n" +
                    "Host: " + target + "\r\n" +
                    "Proxy-Authorization: Basic " + auth + "\r\n" +
                    "User-Agent: Mozilla/5.0\r\n" +
                    "Proxy-Connection: Keep-Alive\r\n\r\n";

            sslSocket.getOutputStream().write(connectCmd.getBytes());
            sslSocket.getOutputStream().flush();

            byte[] respBuf = new byte[1024];
            int respLen = sslSocket.getInputStream().read(respBuf);
            if (respLen > 0 && new String(respBuf, 0, respLen).contains("200")) {
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
                clientOut.flush();

                // Bi-directional pipe
                threadPool.execute(() -> pipe(clientIn, sslSocket.getOutputStream(), true));
                pipe(sslSocket.getInputStream(), clientOut, false);
            }

            client.close();
            sslSocket.close();
        } catch (Exception ignored) {
        }
    }

    private void pipe(InputStream in, OutputStream out, boolean count) {
        byte[] buffer = new byte[8192];
        int len;
        try {
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
                out.flush();
                if (count) {
                    totalBytes += len;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void stopVpn() {
        isRunning = false;
        try {
            if (localProxyServer != null) localProxyServer.close();
        } catch (Exception ignored) {}
        try {
            if (vpnInterface != null) vpnInterface.close();
        } catch (Exception ignored) {}

        stopForeground(true);
        updateStatus("disconnected", currentServerName);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        if (threadPool != null) threadPool.shutdownNow();
        super.onDestroy();
    }
}
