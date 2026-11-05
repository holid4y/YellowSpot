package com.holiday.yellowspot;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.RemoteViews;

/** Optional 4x1 home screen widget using the same Gecko-controlled transport. */
public final class MediaWidget extends AppWidgetProvider {
    private static final String CONTROL = "com.holiday.yellowspot.WIDGET_CONTROL";
    private static final String COMMAND = "command";
    private static final long THROTTLE_MS = 1200;
    private static long lastWidgetDraw;
    private static boolean lastDrawnPlaying;

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        for (int widgetId : widgetIds) {
            manager.updateAppWidget(widgetId, build(context));
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (context == null || intent == null || !CONTROL.equals(intent.getAction())) return;
        String command = intent.getStringExtra(COMMAND);
        // Only the app's own immutable PendingIntents can reach this
        // non-exported receiver; the whitelist still rejects anything odd.
        if (PlaybackService.PLAY.equals(command) || PlaybackService.PAUSE.equals(command)
                || PlaybackService.NEXT.equals(command) || PlaybackService.PREV.equals(command)) {
            ((YellowSpotApp) context.getApplicationContext()).transport(command);
        }
    }

    public static void updateAll(Context context) {
        if (context == null) return;
        YellowSpotApp app = (YellowSpotApp) context.getApplicationContext();
        if (app == null) return;
        long now = SystemClock.elapsedRealtime();
        // Always redraw on a play/pause transition so the toggle icon is
        // never left stale by the position-update throttle.
        boolean force = app.playing != lastDrawnPlaying;
        if (!force && now - lastWidgetDraw < THROTTLE_MS) return;
        lastWidgetDraw = now;
        lastDrawnPlaying = app.playing;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) return;
        ComponentName name = new ComponentName(context, MediaWidget.class);
        int[] ids = manager.getAppWidgetIds(name);
        if (ids.length == 0) return;
        manager.updateAppWidget(name, build(context));
    }

    private static RemoteViews build(Context context) {
        YellowSpotApp app = (YellowSpotApp) context.getApplicationContext();
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.media_widget);
        v.setTextViewText(R.id.widget_song, app.song);
        v.setTextViewText(R.id.widget_artist, app.artist);
        v.setImageViewResource(R.id.widget_toggle, app.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (app.artwork != null) v.setImageViewBitmap(R.id.widget_artwork, app.artwork);
        else v.setImageViewResource(R.id.widget_artwork, R.drawable.ic_launcher_foreground);
        v.setOnClickPendingIntent(R.id.widget_prev, command(context, PlaybackService.PREV, 1));
        v.setOnClickPendingIntent(R.id.widget_toggle, command(context,
                app.playing ? PlaybackService.PAUSE : PlaybackService.PLAY, 2));
        v.setOnClickPendingIntent(R.id.widget_next, command(context, PlaybackService.NEXT, 3));
        PendingIntent launch = PendingIntent.getActivity(context, 4, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_artwork, launch);
        v.setOnClickPendingIntent(R.id.widget_song, launch);
        return v;
    }

    private static PendingIntent command(Context context, String cmd, int id) {
        Intent i = new Intent(context, MediaWidget.class).setAction(CONTROL).putExtra(COMMAND, cmd);
        return PendingIntent.getBroadcast(context, id, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
