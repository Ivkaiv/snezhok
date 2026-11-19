package ru.ivkaiv.snowfall;

import android.view.Choreographer;

/** Draws on display refreshes and skips refreshes evenly when running below the display rate. */
final class SnowFrameLoop implements Choreographer.FrameCallback {
    // A little headroom keeps a 60 Hz display from missing every other refresh due to jitter.
    static final long SMOOTH_GAP_MS = 15;
    static final long ECONOMY_GAP_MS = 30;

    interface Renderer {
        /** Render one frame and return the minimum gap before the next, or zero to stop. */
        long render(long frameTimeNanos);
    }

    private final Choreographer choreographer = Choreographer.getInstance();
    private final Renderer renderer;
    private boolean queued;
    private long nextFrameNanos;

    SnowFrameLoop(Renderer renderer) {
        this.renderer = renderer;
    }

    void start() {
        if (queued) return;
        queued = true;
        choreographer.postFrameCallback(this);
    }

    void stop() {
        choreographer.removeFrameCallback(this);
        queued = false;
        nextFrameNanos = 0;
    }

    @Override public void doFrame(long frameTimeNanos) {
        queued = false;
        if (frameTimeNanos < nextFrameNanos) {
            start();
            return;
        }
        long gapMs = renderer.render(frameTimeNanos);
        if (gapMs <= 0) {
            nextFrameNanos = 0;
            return;
        }
        nextFrameNanos = frameTimeNanos + gapMs * 1_000_000L;
        if (gapMs > 60) {
            queued = true;
            choreographer.postFrameCallbackDelayed(this, Math.max(0L, gapMs - 20L));
        } else {
            start();
        }
    }

    static float elapsedSeconds(long previousFrameNanos, long frameTimeNanos) {
        return previousFrameNanos == 0 ? 0f
                : Math.max(0f, Math.min(.06f, (frameTimeNanos - previousFrameNanos) / 1_000_000_000f));
    }
}
