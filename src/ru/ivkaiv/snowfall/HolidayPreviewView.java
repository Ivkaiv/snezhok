package ru.ivkaiv.snowfall;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;

/** A preview of our own wallpaper scene, never a capture of another app. */
public final class HolidayPreviewView extends View {
    private final HolidayScene scene;
    private final SnowView flakes;
    private final SharedPreferences preferences;
    private boolean attached;
    private boolean requested;
    private boolean tree;
    private boolean eco;
    private long lastFrame;

    private final SnowFrameLoop frames = new SnowFrameLoop(frameTime -> {
        if (!canAnimate()) return 0;
        invalidate();
        return eco ? SnowFrameLoop.ECONOMY_GAP_MS : SnowFrameLoop.SMOOTH_GAP_MS;
    });

    public HolidayPreviewView(Context context) {
        super(context);
        scene = new HolidayScene(context);
        flakes = new SnowView(context);
        preferences = SnowSettings.prefs(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        refreshSettings();
    }

    public void refreshSettings() {
        if (scene == null || flakes == null) return;
        tree = preferences.getBoolean("wallpaper_lock_tree", true);
        eco = SnowSettings.economy(preferences);
        flakes.configure(preferences.getInt("intensity", 42), preferences.getInt("drift", 48),
                false, false, eco);
        flakes.setCleanupSpeed(preferences.getInt("cleanup_speed", 45));
        flakes.setSnowfallVisible(preferences.getBoolean("wallpaper_lock_snow", true));
        scene.setSquirrelEnabled(preferences.getBoolean("holiday_squirrel", true));
        if (getWidth() > 0 && getHeight() > 0) {
            scene.configure(getWidth(), getHeight(), preferences.getBoolean("holiday_decorations", true),
                    preferences.getBoolean("holiday_clock", false), preferences.getInt("holiday_clock_y", 30),
                    preferences.getInt("holiday_tree_size", 100));
        }
        invalidate();
    }

    public void setAnimating(boolean enabled) {
        requested = enabled;
        updateLoop();
    }
    public void clearSnow() { flakes.clearSnow(); invalidate(); }
    private boolean canAnimate() { return attached && requested && isShown() && getWindowVisibility() == VISIBLE; }
    private void updateLoop() {
        if (frames == null) return;
        if (canAnimate()) frames.start();
        else {
            frames.stop();
            lastFrame = 0;
        }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; updateLoop(); }
    @Override protected void onDetachedFromWindow() { attached = false; updateLoop(); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateLoop();
    }
    @Override protected void onVisibilityChanged(View view, int visibility) {
        super.onVisibilityChanged(view, visibility);
        updateLoop();
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        flakes.layout(0, 0, w, h);
        refreshSettings();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(.06f, (now - lastFrame) / 1000f);
        lastFrame = now;
        scene.drawBackdrop(canvas);
        scene.drawTree(canvas, tree);
        flakes.drawWallpaperFrame(canvas, dt);
        scene.drawClock(canvas);
    }
}
