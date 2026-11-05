package com.holiday.yellowspot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

/** Native media session for HyperOS media card, lock screen, Bluetooth and headset keys. */
public final class PlaybackService extends Service {
    static final String PLAY = "com.holiday.yellowspot.PLAY";
    static final String PAUSE = "com.holiday.yellowspot.PAUSE";
    static final String NEXT = "com.holiday.yellowspot.NEXT";
    static final String PREV = "com.holiday.yellowspot.PREV";
    private static final String CHANNEL = "music";
    private static final int NOTIFICATION_ID = 1001;

    static volatile PlaybackService instance;
    /**
     * True between stopSelf() and onDestroy(). sendState() uses it to restart
     * the service if playback resumes in that window; sync() uses it to ignore
     * stale calls for a service that already decided to stop.
     */
    volatile boolean stopped;

    private MediaSession platformSession;
    private YellowSpotApp app;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus;
    private boolean foreground;
    private boolean lastPlaying;

    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = change -> {
        org.mozilla.geckoview.MediaSession media = app.webMedia;
        if (media == null) return;
        try {
            if (change == AudioManager.AUDIOFOCUS_GAIN)
                media.notifySystemAudioFocusChange(org.mozilla.geckoview.MediaSession.SYSTEM_AUDIO_FOCUS_GAIN);
            else if (change == AudioManager.AUDIOFOCUS_LOSS)
                media.notifySystemAudioFocusChange(org.mozilla.geckoview.MediaSession.SYSTEM_AUDIO_FOCUS_PERMANENT_LOSS);
            else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                     change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
                media.notifySystemAudioFocusChange(org.mozilla.geckoview.MediaSession.SYSTEM_AUDIO_FOCUS_TRANSIENT_LOSS);
        } catch (RuntimeException e) {
            // Stale web media session: dropping the notification-side focus
            // bookkeeping is the safe response.
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        stopped = false;
        app = (YellowSpotApp) getApplication();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        // minSdk 26: the channel constructor always applies.
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Music playback",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Media controls for YellowSpot");
        channel.setSound(null, null);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        platformSession = new MediaSession(this, "YellowSpot Music");
        platformSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        platformSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { app.transport(PLAY); }
            @Override public void onPause() { app.transport(PAUSE); }
            @Override public void onSkipToNext() { app.transport(NEXT); }
            @Override public void onSkipToPrevious() { app.transport(PREV); }
            @Override public void onSeekTo(long pos) {
                org.mozilla.geckoview.MediaSession media = app.webMedia;
                if (media != null) {
                    try { media.seekTo(pos / 1000.0, false); }
                    catch (RuntimeException ignored) { }
                }
            }
        });
        platformSession.setActive(true);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (PLAY.equals(action) || PAUSE.equals(action) || NEXT.equals(action) || PREV.equals(action))
                app.transport(action);
        }
        stopped = false;
        // Required promptly after startForegroundService() on Android 8+.
        if (!foreground) {
            promote(notification());
            foreground = true;
        }
        sync();
        return START_NOT_STICKY;
    }

    private void promote(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /** All Gecko delegate calls are on the main thread. */
    void sync() {
        if (platformSession == null || stopped) return;
        long allowed = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                PlaybackState.ACTION_PLAY_PAUSE;
        if ((app.features & org.mozilla.geckoview.MediaSession.Feature.NEXT_TRACK) != 0)
            allowed |= PlaybackState.ACTION_SKIP_TO_NEXT;
        if ((app.features & org.mozilla.geckoview.MediaSession.Feature.PREVIOUS_TRACK) != 0)
            allowed |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        if ((app.features & org.mozilla.geckoview.MediaSession.Feature.SEEK_TO) != 0)
            allowed |= PlaybackState.ACTION_SEEK_TO;

        int state = app.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        PlaybackState playback = new PlaybackState.Builder()
                .setActions(allowed)
                .setState(state, app.positionMs, app.playing ? (float) app.speed : 0f,
                        SystemClock.elapsedRealtime())
                .build();
        platformSession.setPlaybackState(playback);
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, app.song)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, app.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, app.album);
        if (app.durationMs >= 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, app.durationMs);
        if (app.artwork != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, app.artwork);
        platformSession.setMetadata(metadata.build());
        platformSession.setActive(app.active);

        if (app.playing != lastPlaying) {
            if (app.playing) requestFocus(); else abandonFocus();
            lastPlaying = app.playing;
        }
        if (!app.active) {
            stopped = true;
            if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
            stopSelf();
            return;
        }
        if (foreground) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, notification());
    }

    private Notification notification() {
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL);
        b.setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(app.song)
                .setContentText(app.artist)
                .setContentIntent(openApp)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOngoing(app.playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (app.artwork != null) b.setLargeIcon(app.artwork);
        b.addAction(new Notification.Action.Builder(R.drawable.ic_previous, "Previous", action(PREV, 1)).build());
        b.addAction(new Notification.Action.Builder(app.playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        app.playing ? "Pause" : "Play", action(app.playing ? PAUSE : PLAY, 2)).build());
        b.addAction(new Notification.Action.Builder(R.drawable.ic_next, "Next", action(NEXT, 3)).build());
        b.setStyle(new Notification.MediaStyle()
                .setMediaSession(platformSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent action(String name, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(name);
        return PendingIntent.getService(this, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void requestFocus() {
        if (hasFocus || audio == null) return;
        int outcome;
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(audioFocusListener).build();
            outcome = audio.requestAudioFocus(focusRequest);
        } else {
            outcome = audio.requestAudioFocus(audioFocusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        hasFocus = outcome == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (!hasFocus || audio == null) return;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null)
            audio.abandonAudioFocusRequest(focusRequest);
        else audio.abandonAudioFocus(audioFocusListener);
        hasFocus = false;
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        abandonFocus();
        if (platformSession != null) {
            platformSession.setActive(false);
            platformSession.release();
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
