package com.holiday.yellowspot;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostics that survive a crash without adb/logcat.
 *
 * Every run appends a timestamped header and breadcrumbs at each startup
 * stage, and any uncaught Java exception is appended in full. If the process
 * dies mid-startup (e.g. a native crash inside libxul), the breadcrumbs simply
 * stop where it died — which is still enough to localize the failure stage.
 *
 * The file lives in app-specific external storage (readable over USB file
 * transfer and by file managers on older Android), and MainActivity offers a
 * one-tap share of the tail.
 */
public final class CrashReporter {
    static final String FILE_NAME = "yellowspot-diagnostics.txt";
    private static final int MAX_BYTES = 256 * 1024;
    private static final String TAG = "YellowSpotDiag";
    private static CrashReporter instance;

    private final File file;
    private final long runStart = System.currentTimeMillis();

    /** Installs the uncaught-exception hook. Must be the first thing in Application.onCreate. */
    static CrashReporter install(Context context) {
        if (instance != null) return instance;
        File dir = context.getExternalFilesDir(null);
        File target = (dir != null) ? new File(dir, FILE_NAME) : new File(context.getFilesDir(), FILE_NAME);
        CrashReporter reporter = new CrashReporter(target);
        instance = reporter;
        reporter.rotateIfNeeded();
        reporter.append("RUN " + reporter.clock() + "  YellowSpot "
                + versionName(context) + " (build " + versionCode(context) + ")");
        reporter.append("  device: " + Build.MANUFACTURER + " " + Build.MODEL
                + ", Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        reporter.append("  gecko: " + org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION
                + "  abi: " + Build.SUPPORTED_ABIS[0]);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            reporter.recordUncaught(thread, throwable);
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
        return reporter;
    }

    static CrashReporter get() { return instance; }

    private CrashReporter(File file) { this.file = file; }

    void breadcrumb(String stage) {
        append("  +" + (System.currentTimeMillis() - runStart) + "ms  " + stage);
    }

    private void recordUncaught(Thread thread, Throwable throwable) {
        StringWriter buffer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(buffer));
        append("UNCAUGHT EXCEPTION on thread '" + thread.getName() + "' at +"
                + (System.currentTimeMillis() - runStart) + "ms");
        append(buffer.toString());
    }

    /** True when the log shows an uncaught exception, so the UI can offer to share it. */
    boolean hasCrashOnRecord() {
        try {
            return readTail(MAX_BYTES).contains("UNCAUGHT EXCEPTION");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Last up-to-bytes of the diagnostics file, safe to hand to ACTION_SEND. */
    String readTail(int maxBytes) {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            while (true) {
                int read = in.read(chunk);
                if (read < 0) break;
                out.write(chunk, 0, read);
            }
            byte[] all = out.toByteArray();
            if (all.length <= maxBytes) return new String(all, java.nio.charset.StandardCharsets.UTF_8);
            return "...(truncated)...\n" + new String(
                    java.util.Arrays.copyOfRange(all, all.length - maxBytes, all.length),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "No diagnostics readable", e);
            return "(no diagnostics available: " + e + ")";
        }
    }

    private void rotateIfNeeded() {
        try {
            if (file.length() > MAX_BYTES) {
                File previous = new File(file.getParentFile(), FILE_NAME + ".1");
                if (previous.exists() && !previous.delete()) Log.w(TAG, "Could not delete old diagnostics");
                if (!file.renameTo(previous)) Log.w(TAG, "Could not rotate diagnostics");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Rotation failed", e);
        }
    }

    private synchronized void append(String line) {
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(line);
            writer.write('\n');
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not write diagnostics", e);
        }
    }

    private static String clock() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static String versionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static long versionCode(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return -1;
        }
    }
}
