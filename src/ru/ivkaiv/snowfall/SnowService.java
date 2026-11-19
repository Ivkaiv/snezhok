package ru.ivkaiv.snowfall;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.Gravity;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.Collections;

public final class SnowService extends Service {
    static final String STOP = "ru.ivkaiv.snowfall.STOP";
    static final String CLEAR = "ru.ivkaiv.snowfall.CLEAR";
    static final String REFRESH = "ru.ivkaiv.snowfall.REFRESH";
    private static final String PAUSE = "ru.ivkaiv.snowfall.PAUSE";
    private static final String RESUME = "ru.ivkaiv.snowfall.RESUME";
    private static final long PAUSE_DURATION_MS = 60_000L;
    private static final long EXCLUSION_CHECK_INTERVAL_MS = 400L;
    private static final String CHANNEL = "snow_controls";
    private static final int NOTIFICATION_ID = 1;
    private static volatile boolean running;
    private static volatile String displayStatus = "Снег выключен";
    private static SnowService activeService;
    private WindowManager windows;
    private SnowView snow;
    private boolean receiverRegistered;
    private boolean added;
    private SharedPreferences prefs;
    private SmsVisibility smsVisibility;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DisplayManager displays;
    private int lastDisplayState = Display.STATE_UNKNOWN;
    private WindowManager.LayoutParams overlayParams;
    private boolean reattachAfterWake;
    private boolean systemUiPaused;
    private boolean userPaused;
    private long lastExclusionHideAt;
    private String systemUiPauseToken;

    private final Runnable resumeTimer = this::resumeUserPause;

    static boolean isRunning() { return running; }
    static String statusText() { return running ? displayStatus : "Снег выключен"; }
    static boolean hidSnowForExclusionSince(long elapsedRealtime) {
        return activeService != null && activeService.lastExclusionHideAt >= elapsedRealtime;
    }
    static boolean isPausedForSystemUi() {
        return running && activeService != null && activeService.systemUiPaused;
    }

    /** Same-process, main-thread controller: fully remove our layer before protected system UI. */
    static String pauseForSystemUi() {
        SnowService service = activeService;
        if (!running || service == null || service.systemUiPaused) return null;
        service.systemUiPaused = true;
        service.systemUiPauseToken = java.util.UUID.randomUUID().toString();
        service.suspendOverlay();
        displayStatus = "Пауза · системное окно настройки";
        return service.systemUiPauseToken;
    }

    static void resumeForUser() {
        SnowService service = activeService;
        if (service != null) resumeAfterSystemUi(service.systemUiPauseToken);
    }

    static void resumeAfterSystemUi(String token) {
        SnowService service = activeService;
        // If the user stopped the service from the notification/tile, never restart it here.
        if (!running || service == null || !service.systemUiPaused
                || token == null || !token.equals(service.systemUiPauseToken)) return;
        service.systemUiPaused = false;
        service.systemUiPauseToken = null;
        service.restoreOverlay();
    }

    private boolean isPaused() {
        return systemUiPaused || userPaused;
    }

    private void suspendOverlay() {
        handler.removeCallbacks(reconcile);
        detachOverlay();
    }

    private void detachOverlay() {
        if (snow != null) {
            snow.setAnimating(false);
            snow.setVisibility(android.view.View.INVISIBLE);
            if (added) {
                try { windows.removeViewImmediate(snow); }
                catch (IllegalArgumentException ignored) { /* Already removed. */ }
                added = false;
            }
        }
        reattachAfterWake = true;
    }

    private void restoreOverlay() {
        try {
            updateVisibility();
            queueReconcile(300);
        } catch (RuntimeException error) {
            Toast.makeText(this, "Не удалось восстановить снег. Включи его в приложении.", Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private void pauseForUser() {
        userPaused = true;
        suspendOverlay();
        updateVisibility();
        handler.removeCallbacks(resumeTimer);
        handler.postDelayed(resumeTimer, PAUSE_DURATION_MS);
        refreshNotification();
    }

    private void resumeUserPause() {
        if (!running || !userPaused) return;
        handler.removeCallbacks(resumeTimer);
        userPaused = false;
        restoreOverlay();
        refreshNotification();
    }

    private void refreshNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }

    private final Runnable reconcile = new Runnable() {
        @Override public void run() {
            if (!running || isPaused()) return;
            try {
                updateVisibility();
                PowerManager power = getSystemService(PowerManager.class);
                if (power != null && power.isInteractive()) {
                    // Some OEM unlock paths omit USER_PRESENT; reconcile without waking the phone.
                    handler.postDelayed(this, exclusionsEnabled() ? EXCLUSION_CHECK_INTERVAL_MS : 1200L);
                }
            } catch (RuntimeException error) {
                stopSelf();
            }
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayRemoved(int id) { }
        @Override public void onDisplayChanged(int id) {
            if (id != Display.DEFAULT_DISPLAY) return;
            Display display = displays.getDisplay(id);
            int state = display == null ? Display.STATE_UNKNOWN : display.getState();
            if (state == lastDisplayState) return;
            lastDisplayState = state;
            if (state == Display.STATE_ON) reattachAfterWake = true;
            queueReconcile(state == Display.STATE_ON ? 180 : 0);
        }
    };

    private void queueReconcile(long delay) {
        handler.removeCallbacks(reconcile);
        if (running && !isPaused()) handler.postDelayed(reconcile, delay);
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                if (snow != null) {
                    snow.setAnimating(false);
                    snow.setVisibility(android.view.View.INVISIBLE);
                }
                reattachAfterWake = true;
                if (!isPaused()) displayStatus = "Пауза · экран выключен";
                handler.removeCallbacks(reconcile);
            } else {
                reattachAfterWake = true;
                queueReconcile(180);
            }
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (preferences, key) -> {
                if (SmsVisibility.PREF.equals(key) || SmsVisibility.TARGET_PREF.equals(key)
                        || SmsVisibility.EXCLUDED_PACKAGES_PREF.equals(key)) {
                    smsVisibility.reset();
                    queueReconcile(0);
                } else if (snow != null) {
                    SnowSettings.apply(this, snow);
                }
            };

    private boolean exclusionsEnabled() {
        if (Build.VERSION.SDK_INT < 35) return false;
        if (!prefs.contains(SmsVisibility.EXCLUDED_PACKAGES_PREF)) {
            return prefs.getBoolean(SmsVisibility.PREF, false);
        }
        return !prefs.getStringSet(SmsVisibility.EXCLUDED_PACKAGES_PREF, Collections.emptySet()).isEmpty();
    }

    @Override public void onCreate() {
        super.onCreate();
        activeService = this;
        windows = getSystemService(WindowManager.class);
        displays = getSystemService(DisplayManager.class);
        if (displays != null) {
            Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) lastDisplayState = display.getState();
            displays.registerDisplayListener(displayListener, handler);
        }
        prefs = SnowSettings.prefs(this);
        smsVisibility = new SmsVisibility(this);
        prefs.registerOnSharedPreferenceChangeListener(settingsListener);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Управление снегом",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Пауза, выключение снега и уборка сугробов");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action) || (!running && (PAUSE.equals(action) || RESUME.equals(action)))) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала разреши показ поверх других приложений", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (PAUSE.equals(action)) {
                pauseForUser();
                return START_NOT_STICKY;
            }
            if (RESUME.equals(action)) {
                resumeUserPause();
                return START_NOT_STICKY;
            }
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification());
            }
            if (snow == null) createOverlay();
            if (CLEAR.equals(action) && snow != null) {
                snow.clearSnow();
            }
            running = true;
            if (REFRESH.equals(action)) reattachAfterWake = true;
            refreshTile();
            updateVisibility();
            queueReconcile(600);
        } catch (RuntimeException error) {
            Toast.makeText(this, "Android не разрешил включить снег. Открой приложение и проверь разрешение.",
                    Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void createOverlay() {
        snow = new SnowView(this);
        SnowSettings.apply(this, snow);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        // Android 12+ only passes touches through sufficiently transparent SAW windows.
        float maxOpacity = 0.8f;
        if (Build.VERSION.SDK_INT >= 31) {
            InputManager input = getSystemService(InputManager.class);
            if (input != null) maxOpacity = input.getMaximumObscuringOpacityForTouch();
        }
        params.alpha = Math.max(0f, Math.min(0.72f, maxOpacity - 0.05f));
        if (Build.VERSION.SDK_INT >= 30) {
            // Cover the gesture area as well: otherwise the drift floats above the bottom edge.
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        overlayParams = params;
        reattachAfterWake = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void updateVisibility() {
        if (snow == null) return;
        if (isPaused()) {
            displayStatus = systemUiPaused ? "Пауза · системное окно настройки" : "Пауза на минуту";
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        boolean show = power != null && power.isInteractive()
                && (keyguard == null || !keyguard.isKeyguardLocked());
        if (show && exclusionsEnabled() && smsVisibility.isVisible()) {
            if (added) {
                detachOverlay();
                lastExclusionHideAt = SystemClock.elapsedRealtime();
            }
            displayStatus = "Снег скрыт в этом приложении";
            return;
        }
        if (show && reattachAfterWake) {
            // Renew the overlay's surface once after sleep; retain the same renderer and snow state.
            if (added) {
                try { windows.removeViewImmediate(snow); }
                catch (IllegalArgumentException ignored) { }
                added = false;
            }
            windows.addView(snow, overlayParams);
            added = true;
            reattachAfterWake = false;
        }
        snow.setVisibility(show ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        snow.setAnimating(show);
        if (show) snow.invalidate();
        displayStatus = show ? "Снег включён · экран разблокирован"
                : (power != null && power.isInteractive()
                ? "Пауза · экран блокировки (для него есть живые обои)" : "Пауза · экран выключен");
    }

    private Notification notification() {
        int pendingFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), pendingFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, SnowService.class).setAction(STOP), pendingFlags);
        PendingIntent clear = PendingIntent.getService(this, 2,
                new Intent(this, SnowService.class).setAction(CLEAR), pendingFlags);
        PendingIntent togglePause = PendingIntent.getService(this, userPaused ? 4 : 3,
                new Intent(this, SnowService.class).setAction(userPaused ? RESUME : PAUSE), pendingFlags);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(ru.ivkaiv.snowfall.R.drawable.ic_notification)
                .setContentTitle(userPaused ? "Снежок на паузе" : "Снежок включён")
                .setContentText(userPaused ? "Возобновится автоматически" : "Снег поверх приложений")
                .setContentIntent(open).setOngoing(true).setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null,
                        userPaused ? "Продолжить" : "Пауза на минуту", togglePause).build())
                .addAction(new Notification.Action.Builder(null, "Убрать сугробы", clear).build())
                .addAction(new Notification.Action.Builder(null, "Выключить", stop).build())
                .build();
    }

    private void refreshTile() {
        TileService.requestListeningState(this, new ComponentName(this, SnowTileService.class));
    }

    @Override public void onDestroy() {
        if (activeService == this) activeService = null;
        running = false;
        displayStatus = "Снег выключен";
        handler.removeCallbacksAndMessages(null);
        if (displays != null) displays.unregisterDisplayListener(displayListener);
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(settingsListener);
        if (receiverRegistered) unregisterReceiver(screenReceiver);
        if (snow != null) {
            snow.setAnimating(false);
            if (added) {
                try { windows.removeViewImmediate(snow); }
                catch (IllegalArgumentException ignored) { /* Already removed by WindowManager. */ }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        refreshTile();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
