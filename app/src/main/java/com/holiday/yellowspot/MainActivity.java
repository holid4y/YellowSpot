package com.holiday.yellowspot;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/** Only the browser chrome lives here; service and GeckoSession outlive the Activity. */
public final class MainActivity extends Activity {
    private YellowSpotApp app;
    private GeckoView browser;
    private GeckoSession shown;
    private TextView blocker;
    private LinearLayout root;
    private int yellow = Color.rgb(255, 222, 53);

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (YellowSpotApp) getApplication();
        getWindow().setStatusBarColor(Color.rgb(9, 9, 9));
        getWindow().setNavigationBarColor(Color.rgb(9, 9, 9));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(9, 9, 9));
        root.setFitsSystemWindows(true);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        setContentView(root);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(10), 0);
        bar.setBackgroundColor(Color.rgb(16, 16, 16));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(52)));

        TextView name = new TextView(this);
        name.setText("● YellowSpot");
        name.setTextColor(yellow);
        name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

        blocker = action(bar, "◈", "Ad blocking status / retry", () -> {
            Toast.makeText(this, app.blockerState, Toast.LENGTH_LONG).show();
            if (!app.blockerReady) app.retryBlocker();
        });
        action(bar, "⌂", "Spotify home", () -> app.mainSession.loadUri(YellowSpotApp.SPOTIFY));
        action(bar, "⟳", "Reload", () -> { if (shown != null) shown.reload(); });
        action(bar, "↗", "Open in Firefox (fallback)", this::openFirefox);

        browser = new GeckoView(this);
        root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        showSession(app.mainSession);
        app.attach(this);
        askNotificationPermission();
    }

    private TextView action(LinearLayout bar, String glyph, String tooltip, Runnable onTap) {
        TextView view = new TextView(this);
        view.setText(glyph);
        view.setTextColor(Color.WHITE);
        view.setTextSize(23);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(tooltip);
        view.setOnClickListener(v -> onTap.run());
        bar.addView(view, new LinearLayout.LayoutParams(dp(43), dp(48)));
        return view;
    }

    void updateBlockerState() {
        if (blocker == null) return;
        blocker.setTextColor(app.blockerReady ? yellow : Color.LTGRAY);
        blocker.setContentDescription(app.blockerState);
    }

    GeckoSession currentSession() { return shown; }

    void showSession(GeckoSession target) {
        if (browser == null || target == null || shown == target) return;
        if (browser.getSession() != null) browser.releaseSession();
        shown = target;
        browser.setSession(target);
    }

    @Override public void onBackPressed() {
        GeckoSession current = shown;
        if (current != null && current != app.mainSession) { app.closePopup(current); return; }
        if (current != null) current.goBack();
    }

    private void openFirefox() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(YellowSpotApp.SPOTIFY));
        i.setPackage("org.mozilla.firefox");
        try { startActivity(i); }
        catch (ActivityNotFoundException e) {
            i.setPackage(null);
            try { startActivity(i); } catch (ActivityNotFoundException ex) {
                Toast.makeText(this, "No browser installed", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 100);
    }

    @Override protected void onStop() {
        super.onStop();
        // Background audio can continue after the UI loses its compositor surface.
        if (app.playing && app.webMedia != null) {
            try { app.mainSession.setActive(true); }
            catch (RuntimeException ignored) { }
        }
    }

    @Override protected void onDestroy() {
        if (browser != null && browser.getSession() != null) browser.releaseSession();
        app.detach(this);
        super.onDestroy();
    }

    private int dp(int d) { return (int) (d * getResources().getDisplayMetrics().density + 0.5f); }
}
