package ru.ivkaiv.snowfall;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.Arrays;
import java.util.Random;

/** Original, self-contained decorative renderer. It never observes user input. */
public final class SnowView extends View {
    private static final int MAX_FLAKES = 200;
    private static final int MAX_BINS = 180;
    private static final int TIERS = 3;
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float[] TIER_TOP = {0.13f, 0.31f, 0.49f};
    private static final float[] TIER_BOTTOM = {0.48f, 0.70f, 0.91f};
    private static final float[] TIER_HALF = {0.23f, 0.33f, 0.44f};
    private static final int[] GREENS = {0xff236956, 0xff1c594c, 0xff15483f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flakeEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint treeImagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF treeImageBounds = new RectF();
    private final Bitmap treeImage;
    private final Path shape = new Path();
    private final Path groundShape = new Path();
    private final Path groundEdge = new Path();
    private final Random random = new Random();
    private final Flake[] flakes = new Flake[MAX_FLAKES];
    private final float[] ground = new float[MAX_BINS];
    private final float[] scratch = new float[MAX_BINS];
    private final float[] branchSnow = new float[TIERS];
    private final float[] meltProfile = new float[MAX_BINS];
    private final float density;

    private int intensity = 45;
    private int drift = 60;
    private int count;
    private int bins;
    private boolean showTree = true;
    private boolean snowfallVisible = true;
    private boolean treeOnLeft;
    private boolean economy;
    private boolean animationRequested;
    private boolean attached;
    private boolean windowVisible;
    private long previousFrame;
    private float time;
    private float maxDrift;
    private float treeCenter;
    private float treeTop;
    private float treeHeight;
    private int cleanupSpeed = 45;
    private boolean cleanupActive;
    private float cleanupElapsed;

    private static final class Flake {
        float x;
        float y;
        float speed;
        float radius;
        float phase;
        float sway;
        int alpha;
        boolean crystal;
    }

    private final SnowFrameLoop frames = new SnowFrameLoop(frameTime -> {
        if (!canAnimate()) {
            previousFrame = 0;
            return 0;
        }
        step(SnowFrameLoop.elapsedSeconds(previousFrame, frameTime));
        previousFrame = frameTime;
        invalidate();
        return economy ? SnowFrameLoop.ECONOMY_GAP_MS : SnowFrameLoop.SMOOTH_GAP_MS;
    });

    public SnowView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        flakeEdge.setColor(0xff53728b);
        flakeEdge.setStrokeCap(Paint.Cap.ROUND);
        flakeEdge.setStrokeWidth(1.55f * density);
        treeImage = HolidayScene.treeAsset(context);
        for (int i = 0; i < flakes.length; i++) flakes[i] = new Flake();
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Settings are bounded here even if a caller supplies malformed values. */
    public void configure(int intensity, int drift, boolean tree, boolean left, boolean eco) {
        this.intensity = Math.max(1, Math.min(100, intensity));
        this.drift = Math.max(0, Math.min(100, drift));
        showTree = tree;
        treeOnLeft = left;
        economy = eco;
        geometry();
        int oldCount = count;
        count = desiredCount();
        for (int i = oldCount; i < count; i++) resetFlake(flakes[i], true);
        if (this.drift == 0) Arrays.fill(ground, 0.0f);
        for (int i = 0; i < bins; i++) ground[i] = Math.min(ground[i], maxDrift);
        invalidate();
        updateLoop();
    }

    public void setAnimating(boolean enabled) {
        animationRequested = enabled;
        updateLoop();
    }

    /** Independent wallpaper control; overlay and preview keep the default true. */
    public void setSnowfallVisible(boolean visible) {
        if (snowfallVisible == visible) return;
        snowfallVisible = visible;
        if (!visible) clearSnow();
        invalidate();
    }

    /** Allows the wallpaper to avoid duplicating a tree already drawn by the overlay. */
    public void setTreeVisible(boolean visible) {
        if (showTree == visible) return;
        showTree = visible;
        invalidate();
    }

    /** 0 disables automatic cleanup; higher values melt a full drift faster. */
    public void setCleanupSpeed(int value) {
        cleanupSpeed = Math.max(0, Math.min(100, value));
        if (cleanupSpeed == 0) cleanupActive = false;
    }

    /**
     * External frame driver for a detached View, after layout(0, 0, width, height).
     * Its owner keeps setAnimating(false) and controls surface/visibility lifecycle.
     */
    public void drawWallpaperFrame(Canvas canvas, float dt) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float safeDt = Float.isNaN(dt) ? 0.0f : Math.max(0.0f, Math.min(0.06f, dt));
        step(safeDt);
        onDraw(canvas);
    }

    /** Clears accumulated snow, preserving the snowfall itself. */
    public void clearSnow() {
        Arrays.fill(ground, 0.0f);
        Arrays.fill(branchSnow, 0.0f);
        cleanupActive = false;
        cleanupElapsed = 0;
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        windowVisible = getWindowVisibility() == VISIBLE;
        updateLoop();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        frames.stop();
        previousFrame = 0;
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = visibility == VISIBLE;
        updateLoop();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateLoop();
    }

    private boolean canAnimate() {
        return animationRequested && attached && windowVisible && isShown()
                && getWidth() > 0 && getHeight() > 0;
    }

    private void updateLoop() {
        // View's super constructor may dispatch visibility callbacks before field init.
        if (frames == null) return;
        if (canAnimate()) {
            frames.start();
        } else {
            frames.stop();
            previousFrame = 0;
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int oldBins = bins;
        int newBins = Math.max(2, Math.min(MAX_BINS, (int) (w / (6.0f * density)) + 1));
        for (int i = 0; i < newBins; i++) {
            if (oldBins > 1) {
                float index = (float) i * (oldBins - 1) / (newBins - 1);
                int lo = (int) index;
                int hi = Math.min(oldBins - 1, lo + 1);
                scratch[i] = ground[lo] + (ground[hi] - ground[lo]) * (index - lo);
            } else {
                scratch[i] = 0.0f;
            }
        }
        bins = newBins;
        System.arraycopy(scratch, 0, ground, 0, bins);
        geometry();
        for (int i = 0; i < bins; i++) ground[i] = Math.min(ground[i], maxDrift);
        cleanupActive = false;
        groundPaint.setShader(new LinearGradient(0, h - Math.min(96 * density, h * .2f),
                0, h, 0xfffcffff, 0xffcbdce8, Shader.TileMode.CLAMP));
        count = desiredCount();
        for (int i = 0; i < count; i++) resetFlake(flakes[i], true);
        previousFrame = 0;
        updateLoop();
    }

    private void geometry() {
        int w = getWidth();
        int h = getHeight();
        maxDrift = Math.min(96.0f * density, h * .20f) * drift / 100.0f;
        treeHeight = Math.min(174.0f * density, h * .30f);
        float half = treeHeight * .44f;
        float margin = 15.0f * density;
        treeCenter = treeOnLeft ? half + margin : w - half - margin;
        treeTop = h - treeHeight - Math.min(12.0f * density, h * .025f);
    }

    private int desiredCount() {
        if (getWidth() <= 0 || getHeight() <= 0) return 0;
        float area = (getWidth() / density) * (getHeight() / density);
        float sizeFactor = Math.max(.40f, Math.min(1.55f,
                (float) Math.sqrt(area / (400.0f * 800.0f))));
        return Math.max(9, Math.min(MAX_FLAKES, (int) ((14 + intensity * 1.45f) * sizeFactor)));
    }

    private void resetFlake(Flake f, boolean distribute) {
        f.x = random.nextFloat() * Math.max(1, getWidth());
        f.y = distribute ? random.nextFloat() * Math.max(1, getHeight())
                : -12.0f * density;
        f.radius = (.65f + random.nextFloat() * 1.70f) * density;
        f.speed = (17.0f + random.nextFloat() * 32.0f) * density;
        f.phase = random.nextFloat() * TWO_PI;
        f.sway = (6.0f + random.nextFloat() * 15.0f) * density;
        f.alpha = 125 + random.nextInt(121);
        f.crystal = random.nextInt(7) == 0;
    }

    private void step(float dt) {
        if (dt <= 0) return;
        time += dt;
        // Keep trigonometric arguments small even after long uninterrupted sessions.
        if (time > 1800.0f) time -= 1800.0f;
        if (!snowfallVisible) return;
        float wind = (float) Math.sin(time * .17f) * 5.0f * density;
        int w = getWidth();
        int h = getHeight();
        for (int i = 0; i < count; i++) {
            Flake f = flakes[i];
            float oldY = f.y;
            f.x += ((float) Math.sin(time * .72f + f.phase) * f.sway + wind) * dt;
            f.y += f.speed * dt;
            if (f.x < -8 * density) f.x = w + 6 * density;
            if (f.x > w + 8 * density) f.x = -6 * density;
            if (showTree && landOnTree(f.x, oldY, f.y)) {
                resetFlake(f, false);
                continue;
            }
            int bin = Math.max(0, Math.min(bins - 1, (int) (f.x / Math.max(1, w) * (bins - 1))));
            if (f.y + f.radius >= h - ground[bin]) {
                if (maxDrift > 0.0f && !cleanupActive) deposit(bin, f.radius);
                resetFlake(f, false);
            }
        }

        float slopeLimit = 2.8f * density;
        System.arraycopy(ground, 0, scratch, 0, bins);
        for (int i = 0; i < bins; i++) {
            float value = ground[i];
            if (i > 0 && value - ground[i - 1] > slopeLimit) {
                float flow = (value - ground[i - 1] - slopeLimit) * Math.min(.25f, dt * 3.0f);
                scratch[i] -= flow;
                scratch[i - 1] += flow;
            }
            if (i + 1 < bins && value - ground[i + 1] > slopeLimit) {
                float flow = (value - ground[i + 1] - slopeLimit) * Math.min(.25f, dt * 3.0f);
                scratch[i] -= flow;
                scratch[i + 1] += flow;
            }
        }
        for (int i = 0; i < bins; i++) {
            ground[i] = Math.max(0.0f, Math.min(maxDrift, scratch[i] * (1.0f - dt / 420.0f)));
        }
        updateCleanup(dt);
        for (int i = 0; i < TIERS; i++) branchSnow[i] *= 1.0f - dt / 270.0f;
    }

    private void updateCleanup(float dt) {
        if (cleanupSpeed <= 0 || maxDrift <= 0 || bins < 2) return;
        float mean = 0;
        float peak = 0;
        for (int i = 0; i < bins; i++) {
            mean += ground[i];
            peak = Math.max(peak, ground[i]);
        }
        mean /= bins;
        if (!cleanupActive && (mean >= maxDrift * .70f || peak >= maxDrift * .97f)) {
            cleanupActive = true;
            cleanupElapsed = 0;
            // New smooth spatial profile every cycle: different areas settle at different rates.
            float previous = .5f + random.nextFloat();
            float next = .5f + random.nextFloat();
            int span = Math.max(5, bins / 7);
            for (int i = 0; i < bins; i++) {
                if (i > 0 && i % span == 0) { previous = next; next = .5f + random.nextFloat(); }
                float t = (i % span) / (float) span;
                float smooth = t * t * (3f - 2f * t);
                meltProfile[i] = previous + (next - previous) * smooth;
            }
        }
        if (!cleanupActive) return;
        cleanupElapsed += dt;
        float duration = 12f + (100 - cleanupSpeed) * .62f;
        float amount = maxDrift * dt / duration;
        for (int i = 0; i < bins; i++) {
            ground[i] = Math.max(0, ground[i] - amount * meltProfile[i]);
        }
        if ((mean < maxDrift * .24f && peak < maxDrift * .45f)
                || cleanupElapsed >= duration * 1.8f) {
            cleanupActive = false;
            cleanupElapsed = 0;
        }
    }

    private void deposit(int center, float size) {
        for (int j = -3; j <= 3; j++) {
            int i = center + j;
            if (i >= 0 && i < bins) {
                float weight = 1.0f - Math.abs(j) / 4.0f;
                ground[i] = Math.min(maxDrift, ground[i] + size * .60f * weight);
            }
        }
    }

    private boolean landOnTree(float x, float oldY, float newY) {
        float dx = Math.abs(x - treeCenter);
        float nearest = Float.MAX_VALUE;
        int hitTier = -1;
        for (int i = 0; i < TIERS; i++) {
            float half = TIER_HALF[i] * treeHeight;
            if (half > 0 && dx <= half) {
                float y = treeTop + treeHeight * TIER_TOP[i]
                        + treeHeight * (TIER_BOTTOM[i] - TIER_TOP[i]) * dx / half;
                if (y < nearest) {
                    nearest = y;
                    hitTier = i;
                }
            }
        }
        if (hitTier >= 0 && oldY <= nearest && newY >= nearest) {
            branchSnow[hitTier] = Math.min(5.0f * density,
                    branchSnow[hitTier] + .20f * density);
            return true;
        }
        return false;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (showTree) drawTree(canvas);
        if (!snowfallVisible) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(.65f * density);
        for (int i = 0; i < count; i++) {
            Flake f = flakes[i];
            // The cool edge stays visible on white without inspecting the app underneath.
            flakeEdge.setAlpha(76 + f.alpha * 2 / 5);
            float arm = f.radius * 1.8f;
            if (f.crystal) drawCrystal(canvas, f, arm, flakeEdge);
            canvas.drawCircle(f.x, f.y, f.radius + .55f * density, flakeEdge);
            paint.setAlpha(f.alpha);
            canvas.drawCircle(f.x, f.y, f.radius, paint);
            if (f.crystal) drawCrystal(canvas, f, arm, paint);
        }
        paint.setAlpha(255);
        drawGround(canvas);
    }

    private static void drawCrystal(Canvas canvas, Flake flake, float arm, Paint brush) {
        canvas.drawLine(flake.x - arm, flake.y, flake.x + arm, flake.y, brush);
        canvas.drawLine(flake.x, flake.y - arm, flake.x, flake.y + arm, brush);
    }

    private void drawGround(Canvas canvas) {
        if (bins < 2 || maxDrift <= 0) return;
        float largest = 0;
        for (int i = 0; i < bins; i++) largest = Math.max(largest, ground[i]);
        if (largest < .2f * density) return;
        float h = getHeight();
        float step = (float) getWidth() / (bins - 1);
        groundShape.rewind();
        groundEdge.rewind();
        groundShape.moveTo(0, h);
        groundShape.lineTo(0, h - ground[0]);
        groundEdge.moveTo(0, h - ground[0]);
        for (int i = 1; i < bins; i++) {
            float controlX = (i - 1) * step;
            float controlY = h - ground[i - 1];
            float midX = (i - .5f) * step;
            float midY = h - (ground[i - 1] + ground[i]) * .5f;
            groundShape.quadTo(controlX, controlY, midX, midY);
            groundEdge.quadTo(controlX, controlY, midX, midY);
        }
        groundShape.lineTo(getWidth(), h - ground[bins - 1]);
        groundEdge.lineTo(getWidth(), h - ground[bins - 1]);
        groundShape.lineTo(getWidth(), h);
        groundShape.close();
        canvas.drawPath(groundShape, groundPaint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.9f * density);
        paint.setColor(0xdfffffff);
        canvas.drawPath(groundEdge, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawTree(Canvas canvas) {
        if (treeHeight <= 0) return;
        if (treeImage != null) {
            float halfWidth = treeHeight * treeImage.getWidth() / treeImage.getHeight() * .5f;
            treeImageBounds.set(treeCenter - halfWidth, treeTop, treeCenter + halfWidth, treeTop + treeHeight);
            canvas.drawBitmap(treeImage, null, treeImageBounds, treeImagePaint);
            return;
        }
        float x = treeCenter;
        float y = treeTop;
        float h = treeHeight;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff745449);
        canvas.drawRoundRect(x - h * .045f, y + h * .81f,
                x + h * .045f, y + h * .99f, 2 * density, 2 * density, paint);
        for (int i = TIERS - 1; i >= 0; i--) {
            float top = y + h * TIER_TOP[i];
            float bottom = y + h * TIER_BOTTOM[i];
            float half = h * TIER_HALF[i];
            shape.rewind();
            shape.moveTo(x, top);
            shape.cubicTo(x - half * .30f, top + (bottom - top) * .39f,
                    x - half * .65f, bottom - h * .10f, x - half, bottom);
            shape.quadTo(x, bottom + h * .06f, x + half, bottom);
            shape.cubicTo(x + half * .65f, bottom - h * .10f,
                    x + half * .30f, top + (bottom - top) * .39f, x, top);
            shape.close();
            paint.setColor(GREENS[i]);
            canvas.drawPath(shape, paint);
            // The accumulated coating appears only after flakes actually hit the tree.
            if (branchSnow[i] > .12f * density) {
                paint.setColor(0xffecf7f8);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(branchSnow[i]);
                shape.rewind();
                shape.moveTo(x - half * .91f, bottom - h * .025f);
                shape.quadTo(x - half * .35f, bottom - h * .10f, x, top + h * .035f);
                shape.quadTo(x + half * .35f, bottom - h * .10f,
                        x + half * .91f, bottom - h * .025f);
                canvas.drawPath(shape, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }

        // Two fine garlands and tiny warm lights; all geometry is procedural.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.75f * density);
        paint.setColor(0xffbfaa6c);
        shape.rewind();
        shape.moveTo(x - h * .14f, y + h * .39f);
        shape.quadTo(x + h * .05f, y + h * .56f, x + h * .25f, y + h * .60f);
        shape.moveTo(x - h * .29f, y + h * .69f);
        shape.quadTo(x, y + h * .88f, x + h * .35f, y + h * .84f);
        canvas.drawPath(shape, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 12; i++) {
            int row = i / 6;
            float t = (i % 6 + .5f) / 6.0f;
            float lx;
            float ly;
            if (row == 0) {
                lx = x + h * (-.14f + .39f * t);
                ly = y + h * (.39f + .34f * t - .13f * t * t);
            } else {
                lx = x + h * (-.29f + .64f * t);
                ly = y + h * (.69f + .38f * t - .23f * t * t);
            }
            int glow = 45 + (int) (15 * Math.sin(time * .8f + i));
            paint.setColor(Color.argb(glow, 255, 206, 124));
            canvas.drawCircle(lx, ly, h * .028f, paint);
            paint.setColor(i % 3 == 0 ? 0xffffd5b0 : 0xffffedb8);
            canvas.drawCircle(lx, ly, Math.max(density, h * .010f), paint);
        }

        float starX = x;
        float starY = y + h * .095f;
        float r = h * .055f;
        paint.setColor(0x38ffd991);
        canvas.drawCircle(starX, starY, r * 1.8f, paint);
        shape.rewind();
        for (int i = 0; i < 10; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / 5;
            float radius = i % 2 == 0 ? r : r * .45f;
            float sx = starX + (float) Math.cos(angle) * radius;
            float sy = starY + (float) Math.sin(angle) * radius;
            if (i == 0) shape.moveTo(sx, sy); else shape.lineTo(sx, sy);
        }
        shape.close();
        paint.setColor(0xffffe3a3);
        canvas.drawPath(shape, paint);
        paint.setAlpha(255);
    }
}
