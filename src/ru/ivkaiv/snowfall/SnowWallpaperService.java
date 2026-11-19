package ru.ivkaiv.snowfall;

import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.Display;

/**
 * User-selected live wallpaper. The platform owns its binding and home/lock target.
 * It draws only our own winter background; existing wallpapers are never read.
 */
public final class SnowWallpaperService extends WallpaperService {
    private static final String TAG = "SnowWallpaper";

    @Override public Engine onCreateEngine() {
        return new WinterEngine();
    }

    private final class WinterEngine extends Engine
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final SnowView renderer = new SnowView(SnowWallpaperService.this);
        private final HolidayScene scene = new HolidayScene(SnowWallpaperService.this);
        private final SharedPreferences preferences = SnowSettings.prefs(SnowWallpaperService.this);
        private final PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        private final KeyguardManager keyguard = (KeyguardManager)
                getSystemService(Context.KEYGUARD_SERVICE);
        private final DisplayManager displays = getSystemService(DisplayManager.class);

        private boolean visible;
        private boolean surfaceReady;
        private boolean destroyed;
        private boolean receiverRegistered;
        private boolean snowfall = true;
        private boolean effectiveSnowfall = true;
        private boolean tree = true;
        private boolean eco;
        private boolean surfaceFailureLogged;
        private boolean softwareCanvas;
        private boolean hardwareCanvasUsed;
        private int width;
        private int height;
        private long previousFrame;
        private int lastDisplayState = Display.STATE_UNKNOWN;
        private int wakeRetries;
        private boolean effectiveTree;
        private boolean sceneLocked;
        private boolean sceneConfigured;

        private final Runnable retryWake = new Runnable() {
            @Override public void run() {
                if (destroyed || !visible || !surfaceReady) return;
                if (canDraw()) {
                    frames.start();
                } else if (--wakeRetries > 0) {
                    handler.postDelayed(this, 180);
                }
            }
        };

        private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { }
            @Override public void onDisplayRemoved(int id) { }
            @Override public void onDisplayChanged(int id) {
                if (destroyed || id != Display.DEFAULT_DISPLAY) return;
                Display display = displays.getDisplay(id);
                int state = display == null ? Display.STATE_UNKNOWN : display.getState();
                if (state == lastDisplayState) return;
                lastDisplayState = state;
                if (state == Display.STATE_ON) {
                    refreshSurfaceAfterWake();
                    restartFrames();
                } else stopFrames();
            }
        };

        private final BroadcastReceiver screenState = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    stopFrames();
                } else {
                    // SCREEN_ON/USER_PRESENT also revive a still-visible lock wallpaper.
                    refreshSurfaceAfterWake();
                    restartFrames();
                }
            }
        };

        private final SnowFrameLoop frames = new SnowFrameLoop(frameTime -> {
            if (!canDraw()) {
                previousFrame = 0;
                return 0;
            }
            renderFrame(frameTime);
            if (!canDraw()) return 0;
            boolean decorations = preferences.getBoolean("holiday_decorations", true);
            if (effectiveSnowfall || (effectiveTree && preferences.getBoolean("holiday_squirrel", true))) {
                return eco ? SnowFrameLoop.ECONOMY_GAP_MS : SnowFrameLoop.SMOOTH_GAP_MS;
            }
            if (effectiveTree || decorations) return 125;
            boolean mayNeedRedraw = snowfall || tree
                    || preferences.getBoolean("wallpaper_lock_snow", true)
                    || preferences.getBoolean("wallpaper_lock_tree", true)
                    || preferences.getBoolean("holiday_clock", false);
            return mayNeedRedraw ? 1000 : 0;
        });

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(false);
            renderer.setAnimating(false);
            if (displays != null) displays.registerDisplayListener(displayListener, handler);
            preferences.registerOnSharedPreferenceChangeListener(this);
            applySettings();
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenState, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenState, filter);
            }
            receiverRegistered = true;
        }

        private void applySettings() {
            snowfall = preferences.getBoolean("wallpaper_snow", true);
            tree = preferences.getBoolean("wallpaper_tree", true);
            eco = SnowSettings.economy(preferences);
            renderer.configure(preferences.getInt("intensity", 42),
                    preferences.getInt("drift", 48), false,
                    preferences.getBoolean("left", false), eco);
            renderer.setCleanupSpeed(preferences.getInt("cleanup_speed", 45));
            sceneConfigured = false;
            updateSnowfallVisibility();
        }

        private void updateSnowfallVisibility() {
            boolean locked = rendersLockScene();
            boolean wantsSnow = locked ? preferences.getBoolean("wallpaper_lock_snow", true) : snowfall;
            boolean wantsTree = locked ? preferences.getBoolean("wallpaper_lock_tree", true) : tree;
            effectiveSnowfall = wantsSnow && (isPreview() || locked || !SnowService.isRunning());
            // Avoid two layers of flakes and drifts while the overlay is active at home.
            renderer.setSnowfallVisible(effectiveSnowfall);
            effectiveTree = wantsTree && (isPreview() || locked || !SnowService.isRunning()
                    || !preferences.getBoolean("tree", true));
            // The large photographic tree is drawn by HolidayScene, not the small overlay renderer.
            renderer.setTreeVisible(false);
            if (!sceneConfigured || sceneLocked != locked) {
                sceneLocked = locked;
                configureScene();
            }
        }

        private boolean rendersLockScene() {
            if (Build.VERSION.SDK_INT >= 34) {
                int flags = getWallpaperFlags() & (WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
                if (flags == WallpaperManager.FLAG_LOCK) return true;
                // A home-only engine must never borrow lock settings during unlock animations.
                if (flags == WallpaperManager.FLAG_SYSTEM || flags == 0) return false;
            }
            // Only a shared home+lock engine (or older Android without target flags) needs keyguard state.
            return keyguard != null && keyguard.isKeyguardLocked();
        }

        @Override public void onWallpaperFlagsChanged(int which) {
            if (Build.VERSION.SDK_INT >= 34) super.onWallpaperFlagsChanged(which);
            if (destroyed) return;
            sceneConfigured = false;
            updateSnowfallVisibility();
            restartFrames();
        }

        private void configureScene() {
            if (width <= 0 || height <= 0) return;
            scene.setSquirrelEnabled(preferences.getBoolean("holiday_squirrel", true));
            scene.configure(width, height, preferences.getBoolean("holiday_decorations", true),
                    sceneLocked && preferences.getBoolean("holiday_clock", false),
                    preferences.getInt("holiday_clock_y", 30),
                    preferences.getInt("holiday_tree_size", 100));
            sceneConfigured = true;
        }

        @Override public void onSharedPreferenceChanged(SharedPreferences shared, String key) {
            if (destroyed) return;
            if ("clear_snow_serial".equals(key)) {
                renderer.clearSnow();
                restartFrames();
                return;
            }
            if (key == null || key.equals("wallpaper_snow") || key.equals("wallpaper_tree")
                    || key.equals("wallpaper_lock_snow") || key.equals("wallpaper_lock_tree")
                    || key.equals("intensity") || key.equals("drift")
                    || key.equals("left") || key.equals("eco") || key.equals("tree")
                    || key.equals("cleanup_speed") || key.startsWith("holiday_")) {
                applySettings();
                restartFrames();
            }
        }

        @Override public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (isVisible) {
                // A previously invalid surface may become usable again on unlock.
                SurfaceHolder holder = getSurfaceHolder();
                surfaceReady = holder != null && holder.getSurface().isValid();
                restartFrames();
            } else {
                stopFrames();
            }
        }

        @Override public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceReady = holder.getSurface().isValid();
            surfaceFailureLogged = false;
            softwareCanvas = false;
            hardwareCanvasUsed = false;
            restartFrames();
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            width = Math.max(0, w);
            height = Math.max(0, h);
            surfaceReady = holder.getSurface().isValid();
            surfaceFailureLogged = false;
            renderer.layout(0, 0, width, height);
            sceneConfigured = false;
            updateSnowfallVisibility();
            restartFrames();
        }

        @Override public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            surfaceReady = holder.getSurface().isValid();
            surfaceFailureLogged = false;
            restartFrames();
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            stopFrames();
            super.onSurfaceDestroyed(holder);
        }

        private boolean canDraw() {
            Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
            return !destroyed && visible && surfaceReady
                    && width > 0 && height > 0 && power != null && power.isInteractive()
                    && display != null && display.getState() == Display.STATE_ON;
        }

        private void stopFrames() {
            frames.stop();
            handler.removeCallbacks(retryWake);
            previousFrame = 0;
        }

        private void refreshSurfaceAfterWake() {
            if (destroyed) return;
            SurfaceHolder holder = getSurfaceHolder();
            surfaceReady = holder != null && holder.getSurface().isValid();
            if (surfaceReady) surfaceFailureLogged = false;
        }

        private void restartFrames() {
            stopFrames();
            if (canDraw()) {
                frames.start();
            } else if (!destroyed && visible && surfaceReady && width > 0 && height > 0) {
                Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
                if (display != null && display.getState() == Display.STATE_ON) {
                    // Interactive state may lag the display-on callback during unlock.
                    wakeRetries = 8;
                    handler.postDelayed(retryWake, 180);
                }
            }
        }

        private Canvas lockDrawingCanvas(SurfaceHolder holder) {
            if (!softwareCanvas) {
                try {
                    Canvas canvas = holder.lockHardwareCanvas();
                    if (canvas != null) hardwareCanvasUsed = true;
                    return canvas;
                } catch (RuntimeException error) {
                    // Stick to one producer once it has drawn into this surface.
                    if (hardwareCanvasUsed || !holder.getSurface().isValid()) throw error;
                    Log.w(TAG, "Hardware wallpaper canvas unavailable; using software", error);
                    softwareCanvas = true;
                }
            }
            return holder.lockCanvas();
        }

        private void renderFrame(long frameTime) {
            SurfaceHolder holder = getSurfaceHolder();
            if (holder == null || !holder.getSurface().isValid()) {
                surfaceReady = false;
                return;
            }
            Canvas canvas = null;
            try {
                canvas = lockDrawingCanvas(holder);
                if (canvas == null) return;
                updateSnowfallVisibility();
                float dt = SnowFrameLoop.elapsedSeconds(previousFrame, frameTime);
                previousFrame = frameTime;
                scene.drawBackdrop(canvas);
                scene.drawTree(canvas, effectiveTree);
                renderer.drawWallpaperFrame(canvas, dt);
                scene.drawClock(canvas);
            } catch (RuntimeException error) {
                surfaceReady = false;
                logSurfaceFailure(error);
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (RuntimeException error) {
                        surfaceReady = false;
                        logSurfaceFailure(error);
                    }
                }
            }
        }

        private void logSurfaceFailure(RuntimeException error) {
            if (!surfaceFailureLogged) {
                // This reports the rendering failure without private screen content.
                Log.w(TAG, "Wallpaper surface unavailable; waiting for platform lifecycle", error);
                surfaceFailureLogged = true;
            }
        }

        @Override public void onDestroy() {
            destroyed = true;
            visible = false;
            surfaceReady = false;
            stopFrames();
            renderer.setAnimating(false);
            if (displays != null) displays.unregisterDisplayListener(displayListener);
            preferences.unregisterOnSharedPreferenceChangeListener(this);
            if (receiverRegistered) {
                unregisterReceiver(screenState);
                receiverRegistered = false;
            }
            super.onDestroy();
        }
    }
}
