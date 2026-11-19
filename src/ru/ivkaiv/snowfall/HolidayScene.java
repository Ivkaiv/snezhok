package ru.ivkaiv.snowfall;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.Log;

import java.util.Date;

/**
 * Original premium holiday composition. All image data belongs to this app.
 * This class neither reads the user's wallpaper nor owns any animation callback.
 * Call configure/draw/release from the wallpaper engine's rendering thread.
 */
public final class HolidayScene {
    private static final String TAG = "HolidayScene";
    private static final Object BITMAP_LOCK = new Object();
    private static final int TREE_MAX_EDGE = 1536;
    private static final int BACKDROP_MAX_EDGE = 2048;
    private static final long MINUTE_MS = 60_000L;
    private static final float TWO_PI = (float) (Math.PI * 2.0);

    // Shared immutable resources: one tree and one background per app process.
    // No Context/Resources/Activity instance is kept in this static cache.
    private static Bitmap cachedTree;
    private static Bitmap cachedBackdrop;

    // Positions were matched to actual lights in the 1024 x 1536 premium asset.
    // Values are normalized, so the glow follows any proportional image scaling.
    private static final float[] LIGHT_X = {
            .489f, .478f, .561f, .425f, .447f, .596f,
            .382f, .486f, .699f, .225f, .484f, .805f,
            .447f, .592f, .654f, .610f, .596f
    };
    private static final float[] LIGHT_Y = {
            .068f, .184f, .242f, .288f, .370f, .381f,
            .531f, .481f, .524f, .652f, .634f, .682f,
            .732f, .766f, .758f, .823f, .859f
    };
    private static final float[] GARLAND_X = {.05f, .09f, .19f, .26f, .36f, .66f, .86f, .93f, .97f, .83f};
    private static final float[] GARLAND_Y = {.037f, .031f, .033f, .007f, .010f, .022f, .022f, .045f, .153f, .042f};

    private final Context application;
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF backdropBounds = new RectF();
    private final RectF treeBounds = new RectF();
    private final Path snowBank = new Path();
    private final Matrix glowMatrix = new Matrix();
    private final Date dateValue = new Date();
    private final SquirrelAnimator squirrel;
    private final RadialGradient bulbGlow = new RadialGradient(0, 0, 1,
            new int[]{0xd9fff3cf, 0x78ffd58c, 0x00e5ae55},
            new float[]{0.0f, .30f, 1.0f}, Shader.TileMode.CLAMP);

    private Bitmap treeBitmap;
    private Bitmap backdropBitmap;
    private int width;
    private int height;
    private boolean decorations = true;
    private boolean clockVisible;
    private boolean squirrelVisible = true;
    private int clockPercent = 20;
    private int treePercent = 100;
    private final float density;
    private float clockBaseline;
    private float dateBaseline;
    private float clockSize;
    private float cachedDateWidth;
    private long lastMinute = Long.MIN_VALUE;
    private String timeText = "";
    private String dateText = "";

    static Bitmap treeAsset(Context context) {
        synchronized (BITMAP_LOCK) {
            if (cachedTree == null) cachedTree = decodeBounded(context.getResources(),
                    R.drawable.premium_tree, TREE_MAX_EDGE);
            return cachedTree;
        }
    }

    public HolidayScene(Context context) {
        application = context.getApplicationContext();
        squirrel = new SquirrelAnimator(context);
        density = context.getResources().getDisplayMetrics().density;
        synchronized (BITMAP_LOCK) {
            if (cachedTree == null) {
                cachedTree = decodeBounded(context.getResources(), R.drawable.premium_tree,
                        TREE_MAX_EDGE);
            }
            if (cachedBackdrop == null) {
                cachedBackdrop = decodeBounded(context.getResources(), R.drawable.premium_backdrop,
                        BACKDROP_MAX_EDGE);
            }
            treeBitmap = cachedTree;
            backdropBitmap = cachedBackdrop;
        }
        Typeface serif = Typeface.create("serif", Typeface.NORMAL);
        if (Build.VERSION.SDK_INT >= 28) serif = Typeface.create(serif, 300, false);
        timePaint.setTypeface(serif);
        timePaint.setTextAlign(Paint.Align.CENTER);
        timePaint.setShadowLayer(density * 1.4f, 0, density, 0xb509151b);
        datePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        datePaint.setTextAlign(Paint.Align.CENTER);
        datePaint.setColor(0xffead5ac);
        datePaint.setShadowLayer(density, 0, density, 0xa309151b);
        linePaint.setColor(0x99d6b176);
        linePaint.setStrokeWidth(Math.max(1.0f, density * .45f));
        glowPaint.setShader(bulbGlow);
    }

    /**
     * clockY is the clock's time baseline as a percentage of the surface height.
     * This clock is optional and does not replace or hide MagicOS's own clock.
     */
    public void configure(int w, int h, boolean decorations, boolean clock,
                          int clockY, int treeScale) {
        int newWidth = Math.max(0, w);
        int newHeight = Math.max(0, h);
        int newClockPercent = Math.max(10, Math.min(70, clockY));
        int newTreePercent = Math.max(70, Math.min(110, treeScale));
        boolean dimensionsChanged = width != newWidth || height != newHeight;
        boolean clockChanged = dimensionsChanged || clockPercent != newClockPercent;
        boolean treeChanged = dimensionsChanged || treePercent != newTreePercent;
        width = newWidth;
        height = newHeight;
        this.decorations = decorations;
        clockVisible = clock;
        clockPercent = newClockPercent;
        treePercent = newTreePercent;
        if (width == 0 || height == 0) return;

        if (dimensionsChanged) {
            backgroundPaint.setShader(new LinearGradient(0, 0, 0, height,
                    new int[]{0xff081b22, 0xff102e35, 0xff2b484e},
                    new float[]{0.0f, .62f, 1.0f}, Shader.TileMode.CLAMP));
            snowPaint.setShader(new LinearGradient(0, height * .94f, 0, height,
                    0xff9fb6bc, 0xffdae7e8, Shader.TileMode.CLAMP));
            snowBank.rewind();
            snowBank.moveTo(0, height * .967f);
            snowBank.cubicTo(width * .28f, height * .938f, width * .60f,
                    height * .988f, width, height * .952f);
            snowBank.lineTo(width, height);
            snowBank.lineTo(0, height);
            snowBank.close();
            if (backdropBitmap != null) {
                float scale = Math.max((float) width / backdropBitmap.getWidth(),
                        (float) height / backdropBitmap.getHeight());
                float bw = backdropBitmap.getWidth() * scale;
                float bh = backdropBitmap.getHeight() * scale;
                backdropBounds.set((width - bw) * .5f, (height - bh) * .5f,
                        (width + bw) * .5f, (height + bh) * .5f);
            }
        }
        if (treeChanged && treeBitmap != null) {
            float scale = Math.min(height * .78f * treePercent / 100.0f / treeBitmap.getHeight(),
                    width * 1.10f / treeBitmap.getWidth());
            float tw = treeBitmap.getWidth() * scale;
            float th = treeBitmap.getHeight() * scale;
            float bottom = height * .97f;
            treeBounds.set((width - tw) * .5f, bottom - th,
                    (width + tw) * .5f, bottom);
        }
        if (clockChanged) layoutClock();
    }

    public void drawBackdrop(Canvas canvas) {
        if (width <= 0 || height <= 0) return;
        // Always cover the complete surface, including transparent pixels of an asset.
        canvas.drawRect(0, 0, width, height, backgroundPaint);
        if (decorations && backdropBitmap != null) {
            canvas.drawBitmap(backdropBitmap, null, backdropBounds, imagePaint);
            float phase = (SystemClock.uptimeMillis() % 8000L) * TWO_PI / 8000f;
            float scale = backdropBounds.width() / 841f;
            for (int i = 0; i < GARLAND_X.length; i++) {
                float brightness = .5f + .5f * (float) Math.sin(phase * (1 + i % 3) + i * 1.73f);
                float radius = (5.5f + i % 3) * scale;
                float x = backdropBounds.left + backdropBounds.width() * GARLAND_X[i];
                float y = backdropBounds.top + backdropBounds.height() * GARLAND_Y[i];
                glowMatrix.setScale(radius, radius);
                glowMatrix.postTranslate(x, y);
                bulbGlow.setLocalMatrix(glowMatrix);
                glowPaint.setAlpha(35 + (int) (brightness * 170));
                canvas.drawCircle(x, y, radius, glowPaint);
            }
        } else {
            canvas.drawPath(snowBank, snowPaint);
        }
    }

    public void drawTree(Canvas canvas, boolean visible) {
        if (!visible || treeBitmap == null || width <= 0 || height <= 0) return;
        canvas.drawBitmap(treeBitmap, null, treeBounds, imagePaint);
        // Glow radii remain only a few source-image pixels, never large circles.
        float pixelScale = treeBounds.width() / 1024.0f;
        long now = SystemClock.uptimeMillis();
        float phase = (now % 6000L) * TWO_PI / 6000.0f;
        for (int i = 0; i < LIGHT_X.length; i++) {
            float brightness = .5f + .5f * (float) Math.sin(phase * (1 + i % 3) + i * 1.37f);
            float radius = (i == 0 ? 12.0f : 7.0f + i % 3 * 1.2f) * pixelScale;
            float x = treeBounds.left + treeBounds.width() * LIGHT_X[i];
            float y = treeBounds.top + treeBounds.height() * LIGHT_Y[i];
            glowMatrix.setScale(radius, radius);
            glowMatrix.postTranslate(x, y);
            bulbGlow.setLocalMatrix(glowMatrix);
            glowPaint.setAlpha(45 + (int) (brightness * 180));
            canvas.drawCircle(x, y, radius, glowPaint);
        }
        if (squirrelVisible) squirrel.draw(canvas, treeBounds, now);
    }

    public void setSquirrelEnabled(boolean enabled) { squirrelVisible = enabled; }

    /** Draw after particles; no timers, callbacks, or clock text baked into images. */
    public void drawClock(Canvas canvas) {
        if (!clockVisible || width <= 0 || height <= 0 || application == null) return;
        long now = System.currentTimeMillis();
        long minute = now / MINUTE_MS;
        if (minute != lastMinute) {
            lastMinute = minute;
            dateValue.setTime(now);
            // New locale-aware formatters also pick up current timezone and 12/24h mode.
            timeText = DateFormat.getTimeFormat(application).format(dateValue);
            dateText = DateFormat.getLongDateFormat(application).format(dateValue);
            layoutClock();
        }
        canvas.drawText(timeText, width * .5f, clockBaseline, timePaint);
        canvas.drawText(dateText, width * .5f, dateBaseline, datePaint);
        float gap = Math.max(10.0f * density, width * .025f);
        float center = width * .5f;
        float lineY = dateBaseline - datePaint.getTextSize() * .31f;
        float leftEnd = center - cachedDateWidth * .5f - gap;
        float rightStart = center + cachedDateWidth * .5f + gap;
        float lineWidth = Math.min(width * .12f, clockSize * .56f);
        if (leftEnd - lineWidth > width * .05f) {
            canvas.drawLine(leftEnd - lineWidth, lineY, leftEnd, lineY, linePaint);
            canvas.drawLine(rightStart, lineY, rightStart + lineWidth, lineY, linePaint);
        }
    }

    private void layoutClock() {
        if (width <= 0 || height <= 0) return;
        clockBaseline = height * clockPercent / 100.0f;
        clockSize = Math.min(width * .225f, height * .115f);
        timePaint.setTextSize(clockSize);
        if (!timeText.isEmpty()) {
            float measured = timePaint.measureText(timeText);
            if (measured > width * .86f) {
                clockSize *= width * .86f / measured;
                timePaint.setTextSize(clockSize);
            }
        }
        datePaint.setTextSize(clockSize * .235f);
        cachedDateWidth = datePaint.measureText(dateText);
        if (cachedDateWidth > width * .75f && cachedDateWidth > 0) {
            datePaint.setTextSize(datePaint.getTextSize() * width * .75f / cachedDateWidth);
            cachedDateWidth = datePaint.measureText(dateText);
        }
        dateBaseline = clockBaseline + clockSize * .43f;
        timePaint.setShader(new LinearGradient(0, clockBaseline - clockSize, 0,
                clockBaseline + clockSize * .05f,
                new int[]{0xfffff0cf, 0xffecc98b, 0xffbd8c4f},
                new float[]{0.0f, .58f, 1.0f}, Shader.TileMode.CLAMP));
    }

    private static Bitmap decodeBounded(Resources resources, int id, int maxEdge) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            bounds.inScaled = false;
            BitmapFactory.decodeResource(resources, id, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (((long) bounds.outWidth + sample - 1) / sample > maxEdge
                    || ((long) bounds.outHeight + sample - 1) / sample > maxEdge) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inMutable = false;
            return BitmapFactory.decodeResource(resources, id, options);
        } catch (RuntimeException | OutOfMemoryError error) {
            // If an optional visual asset cannot decode, the native background still works.
            Log.w(TAG, "Holiday asset unavailable; using the built-in background", error);
            return null;
        }
    }

    /** Shared immutable bitmaps must not be recycled while another engine uses them. */
    public void release() {
        treeBitmap = null;
        backdropBitmap = null;
    }
}
