package ru.ivkaiv.snowfall;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

/** Original Canvas animation; its caller owns visibility, scheduling and consent. */
public final class SquirrelAnimator {
    private static final String TAG = "SquirrelAnimator";
    // The supplied 1254px sheet remains intact; larger future sheets are bounded.
    private static final int MAX_SHEET_EDGE = 1536;
    private static final int POSES = 4;

    // Perching points matched to leafy branches of premium_tree, not the star.
    private static final float[] BRANCH_X = {
            .27f, .62f, .77f, .55f, .63f, .39f, .28f, .42f
    };
    private static final float[] BRANCH_Y = {
            .78f, .67f, .51f, .39f, .27f, .20f, .41f, .58f
    };
    private static final int[] REST_MS = {1900, 2200, 1700, 2350, 1850, 2050, 2400, 1750};
    private static final int[] HOP_MS = {840, 930, 790, 900, 860, 820, 910, 880};
    private static final long CYCLE_MS = cycleLength();
    private static final Object ATLAS_LOCK = new Object();
    private static Atlas cachedAtlas;
    private static boolean atlasLoadAttempted;

    private final Atlas atlas;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix transform = new Matrix();
    private final RectF destination = new RectF();
    private long lastUptime = -1;
    private long animationTime;

    private static final class Atlas {
        final Bitmap bitmap;
        final Rect[] pose;
        final int widestPose;

        Atlas(Bitmap bitmap, Rect[] pose, int widestPose) {
            this.bitmap = bitmap;
            this.pose = pose;
            this.widestPose = widestPose;
        }
    }

    public SquirrelAnimator(Context context) {
        synchronized (ATLAS_LOCK) {
            if (!atlasLoadAttempted) {
                atlasLoadAttempted = true;
                cachedAtlas = loadAtlas(context.getResources());
            }
            atlas = cachedAtlas;
        }
    }

    /**
     * treeBounds must be the exact destination rectangle of the premium tree image.
     * No callbacks or timers are created; hidden periods do not fast-forward jumps.
     */
    public void draw(Canvas canvas, RectF treeBounds, long uptimeMillis) {
        if (atlas == null || treeBounds == null || treeBounds.width() <= 0
                || treeBounds.height() <= 0) return;

        if (lastUptime >= 0 && uptimeMillis >= lastUptime) {
            // A delayed callback or screen wake resumes near the previous position.
            long elapsed = Math.min(100L, uptimeMillis - lastUptime);
            animationTime = (animationTime + elapsed) % CYCLE_MS;
        }
        lastUptime = uptimeMillis;

        int branch = 0;
        long phaseTime = animationTime;
        for (int i = 0; i < BRANCH_X.length; i++) {
            long span = REST_MS[i] + HOP_MS[i];
            if (phaseTime < span) {
                branch = i;
                break;
            }
            phaseTime -= span;
        }
        int next = (branch + 1) % BRANCH_X.length;
        int previous = (branch + BRANCH_X.length - 1) % BRANCH_X.length;
        float x = treeBounds.left + treeBounds.width() * BRANCH_X[branch];
        float y = treeBounds.top + treeBounds.height() * BRANCH_Y[branch];
        float endX = treeBounds.left + treeBounds.width() * BRANCH_X[next];
        float endY = treeBounds.top + treeBounds.height() * BRANCH_Y[next];
        float scaleX = 1.0f;
        float scaleY = 1.0f;
        float rotation = 0.0f;
        boolean faceRight;
        int pose;

        if (phaseTime < REST_MS[branch]) {
            long remaining = REST_MS[branch] - phaseTime;
            faceRight = remaining <= 240L ? endX >= x
                    : BRANCH_X[branch] >= BRANCH_X[previous];
            pose = 0; // top-left: relaxed, perched and looking right
            scaleY = 1.0f + .006f * (float) Math.sin(animationTime * .0027f);
            if (remaining < 150L) {
                float preparation = 1.0f - remaining / 150.0f;
                pose = 1; // top-right: leaning into takeoff
                scaleX = 1.0f + .035f * preparation;
                scaleY = 1.0f - .055f * preparation;
            }
        } else {
            float t = Math.max(0.0f, Math.min(1.0f,
                    (phaseTime - REST_MS[branch]) / (float) HOP_MS[branch]));
            float smooth = t * t * (3.0f - 2.0f * t);
            float dx = endX - x;
            float dy = endY - y;
            float rise = Math.min(treeBounds.height() * .10f,
                    treeBounds.height() * .045f + Math.abs(dx) * .11f);
            x += dx * smooth;
            y += dy * smooth - 4.0f * rise * t * (1.0f - t);
            faceRight = dx >= 0;
            pose = t < .16f ? 1 : (t < .79f ? 2 : 3);
            float derivative = 6.0f * t * (1.0f - t);
            float vy = dy * derivative - 4.0f * rise * (1.0f - 2.0f * t);
            float vx = Math.abs(dx * derivative) + 1.0f;
            float tilt = (float) Math.toDegrees(Math.atan2(vy, vx)) * .15f;
            rotation = Math.max(-14.0f, Math.min(14.0f, faceRight ? tilt : -tilt));
            scaleX = 1.025f;
            scaleY = .985f;
            if (t > .84f) {
                float landing = (t - .84f) / .16f;
                float squash = (float) Math.sin(landing * Math.PI);
                scaleX = 1.0f + .045f * squash;
                scaleY = 1.0f - .065f * squash;
            }
        }

        Rect source = atlas.pose[pose];
        // One common source-pixel scale preserves relative pose sizes in the sheet.
        float pixelScale = treeBounds.width() * .135f / atlas.widestPose;
        float spriteWidth = source.width() * pixelScale;
        float spriteHeight = source.height() * pixelScale;
        destination.set(-spriteWidth * .5f, -spriteHeight, spriteWidth * .5f, 0);
        transform.setScale(faceRight ? scaleX : -scaleX, scaleY);
        transform.postRotate(rotation);
        transform.postTranslate(x, y);
        int saved = canvas.save();
        try {
            canvas.concat(transform);
            canvas.drawBitmap(atlas.bitmap, source, destination, paint);
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    private static long cycleLength() {
        long result = 0;
        for (int i = 0; i < REST_MS.length; i++) result += REST_MS[i] + HOP_MS[i];
        return result;
    }

    private static Atlas loadAtlas(Resources resources) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            bounds.inScaled = false;
            BitmapFactory.decodeResource(resources, R.drawable.premium_squirrel_sheet, bounds);
            if (bounds.outWidth < 4 || bounds.outHeight < 4) {
                Log.w(TAG, "Squirrel sheet has no usable 2x2 cells");
                return null;
            }
            int sample = 1;
            while (((long) bounds.outWidth + sample - 1) / sample > MAX_SHEET_EDGE
                    || ((long) bounds.outHeight + sample - 1) / sample > MAX_SHEET_EDGE) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inMutable = false;
            Bitmap bitmap = BitmapFactory.decodeResource(resources,
                    R.drawable.premium_squirrel_sheet, options);
            if (bitmap == null) {
                Log.w(TAG, "Squirrel sheet could not be decoded");
                return null;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            Rect[] cells = new Rect[POSES];
            int widest = 1;
            for (int i = 0; i < POSES; i++) {
                int col = i % 2;
                int row = i / 2;
                // Integer proportional boundaries work even after odd-sized sampling.
                int left = width * col / 2;
                int top = height * row / 2;
                int right = width * (col + 1) / 2;
                int bottom = height * (row + 1) / 2;
                int minX = right;
                int minY = bottom;
                int maxX = left - 1;
                int maxY = top - 1;
                for (int y = top; y < bottom; y++) {
                    int offset = y * width;
                    for (int x = left; x < right; x++) {
                        if ((pixels[offset + x] >>> 24) > 16) {
                            minX = Math.min(minX, x);
                            minY = Math.min(minY, y);
                            maxX = Math.max(maxX, x);
                            maxY = Math.max(maxY, y);
                        }
                    }
                }
                if (maxX < minX || maxY < minY) {
                    Log.w(TAG, "Squirrel sheet contains an empty pose");
                    return null;
                }
                // Retain up to one border pixel, never erode the flight pose's edges.
                cells[i] = new Rect(Math.max(left, minX - 1), Math.max(top, minY - 1),
                        Math.min(right, maxX + 2), Math.min(bottom, maxY + 2));
                widest = Math.max(widest, cells[i].width());
            }
            return new Atlas(bitmap, cells, widest);
        } catch (RuntimeException | OutOfMemoryError error) {
            Log.w(TAG, "Squirrel sheet unavailable; skipping this optional animation", error);
            return null;
        }
    }
}
