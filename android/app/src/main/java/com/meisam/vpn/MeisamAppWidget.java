package com.meisam.vpn;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

public class MeisamAppWidget extends AppWidgetProvider {
    public static final String ACTION_TOGGLE_VPN = "com.meisam.vpn.ACTION_TOGGLE_VPN";
    private static final String PREF_NAME = "veepn_config";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    public static void updateAllWidgets(Context context) {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        ComponentName thisWidget = new ComponentName(context, MeisamAppWidget.class);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget);
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String status = prefs.getString("vpn_status", "disconnected");

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);

        if ("connected".equals(status)) {
            views.setInt(R.id.widget_button, "setBackgroundResource", R.drawable.widget_bg_connected);
        } else if ("connecting".equals(status)) {
            views.setInt(R.id.widget_button, "setBackgroundResource", R.drawable.widget_bg_connecting);
        } else {
            views.setInt(R.id.widget_button, "setBackgroundResource", R.drawable.widget_bg_disconnected);
        }

        Intent toggleIntent = new Intent(context, MeisamAppWidget.class);
        toggleIntent.setAction(ACTION_TOGGLE_VPN);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, toggleIntent, flags);
        views.setOnClickPendingIntent(R.id.widget_button, pendingIntent);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_TOGGLE_VPN.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String status = prefs.getString("vpn_status", "disconnected");

            if ("connected".equals(status) || "connecting".equals(status)) {
                Intent stopIntent = new Intent(context, MeisamVpnService.class);
                stopIntent.setAction(MeisamVpnService.ACTION_DISCONNECT);
                context.startService(stopIntent);
            } else {
                Intent mainIntent = new Intent(context, MainActivity.class);
                mainIntent.setAction("TRIGGER_CONNECT");
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(mainIntent);
            }
        }
    }
}
