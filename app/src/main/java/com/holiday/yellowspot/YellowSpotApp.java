package com.holiday.yellowspot;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Gecko state belongs to the process, not the Activity. Keeps playback alive on screen-off. */
public final class YellowSpotApp extends Application implements MediaSession.Delegate {
    public static final String SPOTIFY = "https://open.spotify.com/";
    private static final String UBO_ID = "uBlock0@raymondhill.net";
    private static final String UBO_URL =
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi";
    private static final String TAG = "YellowSpot";

    private GeckoRuntime runtime;
    public GeckoSession mainSession;
    public MediaSession webMedia;
    public boolean active;
    public boolean playing;
    public String song = "Spotify";
    public String artist = "Ready to play";
    public String album = "";
    public Bitmap artwork;
    public long positionMs;
    public long durationMs = -1;
    public double speed = 1.0;
    public long features = MediaSession.Feature.PLAY | MediaSession.Feature.PAUSE;
    /** True only when both blocking layers (uBlock Origin + bundled ad guard) are active. */
    public volatile boolean blockerReady;
    public volatile String blockerState = "Starting ad blockers…";

    /** Prevents overlapping downloads when the user taps retry repeatedly. */
    private final AtomicBoolean installingUbo = new AtomicBoolean(false);
    private boolean startedPage;
    private boolean uBlockReady;
    private boolean adGuardReady;
    private String uBlockMessage = "installing…";
    private String adGuardMessage = "loading…";
    private int artworkGeneration;
    private MainActivity activity;
    private final List<GeckoSession> popups = new ArrayList<>();

    @Override public void onCreate() {
        super.onCreate();
        // First: even a crash during Gecko init must leave evidence on disk.
        CrashReporter diagnostics = CrashReporter.install(this);
        diagnostics.breadcrumb("Application.onCreate: start");
        // GeckoView uses a single, persistent app profile (cookies/login/localStorage).
        runtime = GeckoRuntime.create(this);
        diagnostics.breadcrumb("GeckoRuntime created");
        mainSession = createSession();
        diagnostics.breadcrumb("main session opened");
        installMobileLayout();
        installAdGuard();
        installBlocker();
        diagnostics.breadcrumb("extension installs scheduled");
        // Spotify remains usable if a network timeout delays the add-on download.
        new Handler(Looper.getMainLooper()).postDelayed(this::loadSpotifyOnce, 10000);
    }

    GeckoRuntime runtime() { return runtime; }
    void attach(MainActivity newActivity) { activity = newActivity; refreshScreen(); }
    void detach(MainActivity oldActivity) { if (activity == oldActivity) activity = null; }

    GeckoSession createSession() {
        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .suspendMediaWhenInactive(false)
                .usePrivateMode(false)
                .build();
        GeckoSession session = new GeckoSession(settings);
        session.setMediaSessionDelegate(this);
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession opener, String uri) {
                GeckoSession popup = createSession();
                popups.add(popup);
                // Do not load uri here. Gecko will load the popup navigation itself.
                if (activity != null) activity.showSession(popup);
                return GeckoResult.fromValue(popup);
            }
        });
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onCloseRequest(GeckoSession closed) {
                if (closed != mainSession) closePopup(closed);
            }

            @Override public void onCrash(GeckoSession session) {
                recoverSession(session, "content process crashed");
            }

            @Override public void onKill(GeckoSession session) {
                recoverSession(session, "content process killed by the OS");
            }
        });
        session.open(runtime);
        return session;
    }

    /**
     * Without this, a Gecko content-process crash or an OS kill left a dead
     * session attached to the view: a black screen until the app was forced
     * to close. The main session is recreated and Spotify reloaded; popup
     * sessions are simply closed.
     */
    private void recoverSession(GeckoSession session, String reason) {
        Log.e(TAG, "GeckoSession lost (" + reason + "); recovering");
        try {
            if (session == mainSession) {
                resetMedia();
                try { session.close(); } catch (RuntimeException ignored) { }
                mainSession = createSession();
                mainSession.loadUri(SPOTIFY);
                if (activity != null) activity.showSession(mainSession);
            } else {
                closePopup(session);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Session recovery failed", e);
        }
    }

    void closePopup(GeckoSession session) {
        if (session == null || session == mainSession) return;
        popups.remove(session);
        if (activity != null && activity.currentSession() == session) activity.showSession(mainSession);
        try { session.close(); } catch (RuntimeException ignored) { }
    }

    private void installMobileLayout() {
        runtime.getWebExtensionController().ensureBuiltIn(
                "resource://android/assets/mobile/", "yellowspot-mobile@holiday.local")
                .accept(extension -> Log.d(TAG, "Mobile layout loaded"),
                        err -> Log.w(TAG, "Mobile CSS could not load", err));
    }

    /**
     * The bundled ad guard is what keeps playback flowing between songs:
     * it mutes/fast-forwards ad media and auto-recovers stalled ad slots
     * (hard network blocking alone wedges the Spotify web player at 0:00).
     * It ships inside the APK, so unlike uBlock Origin it needs no network.
     */
    private void installAdGuard() {
        adGuardMessage = "loading…";
        publishBlockerState();
        runtime.getWebExtensionController().ensureBuiltIn(
                "resource://android/assets/adguard/", "yellowspot-adguard@holiday.local")
                .accept(extension -> {
                    adGuardReady = true;
                    adGuardMessage = "active";
                    publishBlockerState();
                }, err -> {
                    adGuardReady = false;
                    adGuardMessage = "bundled asset failed to load";
                    Log.e(TAG, "Ad guard could not load", err);
                    publishBlockerState();
                });
    }

    private void installBlocker() {
        if (!installingUbo.compareAndSet(false, true)) return; // install already in flight
        uBlockMessage = "installing…";
        publishBlockerState();
        WebExtensionController controller = runtime.getWebExtensionController();
        controller.setPromptDelegate(new WebExtensionController.PromptDelegate() {
            @Override public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                    WebExtension extension, String[] permissions, String[] origins,
                    String[] dataCollectionPermissions) {
                // Authorize only the exact uBlock Origin extension requested by the user.
                boolean trusted = UBO_ID.equals(extension.id);
                return GeckoResult.fromValue(new WebExtension.PermissionPromptResponse(
                        trusted, false, false));
            }
        });
        controller.list().accept(installed -> {
            for (WebExtension ext : installed) {
                if (UBO_ID.equals(ext.id)) {
                    installingUbo.set(false);
                    uBlockReady = true;
                    uBlockMessage = "enabled";
                    publishBlockerState();
                    loadSpotifyOnce();
                    return;
                }
            }
            controller.install(UBO_URL).accept(extension -> {
                installingUbo.set(false);
                if (!UBO_ID.equals(extension.id)) {
                    uBlockReady = false;
                    uBlockMessage = "extension identity mismatch";
                    controller.uninstall(extension);
                } else {
                    uBlockReady = true;
                    uBlockMessage = "enabled";
                }
                publishBlockerState();
                loadSpotifyOnce();
            }, error -> {
                installingUbo.set(false);
                uBlockReady = false;
                uBlockMessage = "install failed — tap shield to retry";
                Log.e(TAG, "uBO installation failed", error);
                publishBlockerState();
                loadSpotifyOnce(); // Not a blocker to launching Spotify when offline.
            });
        }, error -> {
            installingUbo.set(false);
            uBlockReady = false;
            uBlockMessage = "check failed — tap shield to retry";
            Log.e(TAG, "uBO check failed", error);
            publishBlockerState();
            loadSpotifyOnce();
        });
    }

    void retryBlocker() {
        // ensureBuiltIn calls are idempotent, and installBlocker() guards
        // against overlapping installs itself.
        installAdGuard();
        installBlocker();
    }

    private void publishBlockerState() {
        blockerReady = uBlockReady && adGuardReady;
        blockerState = "uBlock Origin: " + uBlockMessage
                + (adGuardReady ? " · ad guard: active" : " · ad guard: " + adGuardMessage);
        refreshScreen();
    }

    private void loadSpotifyOnce() {
        if (!startedPage) {
            startedPage = true;
            mainSession.loadUri(SPOTIFY);
            if (CrashReporter.get() != null) CrashReporter.get().breadcrumb("Spotify load requested");
        }
    }
    private void refreshScreen() { if (activity != null) activity.updateBlockerState(); }

    @Override public void onActivated(GeckoSession session, MediaSession media) {
        webMedia = media;
        active = true;
        sendState();
    }

    @Override public void onDeactivated(GeckoSession session, MediaSession media) {
        if (webMedia != media) return;
        active = false;
        playing = false;
        webMedia = null;
        sendState();
    }

    @Override public void onMetadata(GeckoSession session, MediaSession media, MediaSession.Metadata meta) {
        if (webMedia != media) return;
        song = emptyTo(meta.title, "Spotify");
        artist = emptyTo(meta.artist, "Unknown artist");
        album = emptyTo(meta.album, "");
        // Tag each metadata update with a generation so an older artwork
        // fetch can never overwrite the artwork of a newer track.
        final int generation = ++artworkGeneration;
        artwork = null;
        sendState();
        if (meta.artwork != null) {
            meta.artwork.getBitmap(256).accept(bitmap -> {
                if (generation == artworkGeneration && webMedia == media) {
                    artwork = bitmap;
                    sendState();
                }
            }, err -> Log.w(TAG, "Artwork unavailable", err));
        }
    }

    @Override public void onFeatures(GeckoSession session, MediaSession media, long available) {
        if (webMedia == media) { features = available; sendState(); }
    }

    @Override public void onPositionState(GeckoSession session, MediaSession media,
                                          MediaSession.PositionState state) {
        if (webMedia != media) return;
        positionMs = finiteMillis(state.position, 0);
        durationMs = finiteMillis(state.duration, -1);
        speed = Double.isFinite(state.playbackRate) ? state.playbackRate : 1.0;
        sendState();
    }

    @Override public void onPlay(GeckoSession session, MediaSession media) {
        if (webMedia != media) return;
        playing = true;
        sendState();
    }

    @Override public void onPause(GeckoSession session, MediaSession media) {
        if (webMedia != media) return;
        playing = false;
        sendState();
    }

    @Override public void onStop(GeckoSession session, MediaSession media) {
        if (webMedia != media) return;
        playing = false;
        sendState();
    }

    /** Clears all playback state after the media session is lost or the session dies. */
    private void resetMedia() {
        webMedia = null;
        active = false;
        playing = false;
        features = MediaSession.Feature.PLAY | MediaSession.Feature.PAUSE;
        positionMs = 0;
        durationMs = -1;
        speed = 1.0;
        song = "Spotify";
        artist = "Ready to play";
        album = "";
        artwork = null;
        sendState();
    }

    private void sendState() {
        // Start the foreground service only when Gecko actually reports media playback.
        // instance.stopped covers the race where the service is winding down
        // (stopSelf issued) but onDestroy has not run yet.
        if (playing && active
                && (PlaybackService.instance == null || PlaybackService.instance.stopped)) {
            try {
                Intent service = new Intent(this, PlaybackService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                else startService(service);
            } catch (RuntimeException e) {
                // ForegroundServiceStartNotAllowedException & friends: audio
                // keeps playing even without the notification.
                Log.e(TAG, "Cannot start media playback service", e);
            }
        }
        if (PlaybackService.instance != null) PlaybackService.instance.sync();
        MediaWidget.updateAll(this);
    }

    void transport(String action) {
        if (action == null) return;
        MediaSession media = webMedia;
        if (media == null) { openMainActivity(); return; }
        try {
            if (!media.isActive()) { openMainActivity(); return; }
            switch (action) {
                case PlaybackService.PLAY: media.play(); break;
                case PlaybackService.PAUSE: media.pause(); break;
                case PlaybackService.NEXT: media.nextTrack(); break;
                case PlaybackService.PREV: media.previousTrack(); break;
                default: break; // unknown commands are ignored
            }
        } catch (RuntimeException e) {
            // The web media session can be gone without a deactivation event
            // (e.g. after a content-process crash). Fail safe, never crash.
            Log.w(TAG, "Transport failed; resetting media state", e);
            resetMedia();
        }
    }

    private void openMainActivity() {
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot open MainActivity", e);
        }
    }

    private static String emptyTo(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
    private static long finiteMillis(double secs, long fallback) {
        return !Double.isFinite(secs) || secs < 0 || secs > Long.MAX_VALUE / 1000.0
                ? fallback : (long) (secs * 1000);
    }
}
