package com.autobackup.client;

import com.autobackup.ClientConfig;

public final class IndicatorState {
    private static final long FADE_IN_NANOS = 150_000_000L;
    private static final long FADE_OUT_NANOS = 400_000_000L;
    private static final long FAILSAFE_NANOS = 15L * 60L * 1_000_000_000L;

    private static boolean visible;
    private static boolean releaseRequested;
    private static long shownAt;
    private static long releasedAt;

    private IndicatorState() {
    }

    public static void set(boolean active) {
        long now = System.nanoTime();
        if (active) {
            if (!visible) {
                visible = true;
                shownAt = now;
            }
            releaseRequested = false;
        } else if (visible && !releaseRequested) {
            releaseRequested = true;
            releasedAt = now;
        }
    }

    public static void reset() {
        visible = false;
        releaseRequested = false;
    }

    public static float alpha() {
        if (!visible) {
            return 0.0F;
        }
        long now = System.nanoTime();
        long shownFor = now - shownAt;
        if (shownFor > FAILSAFE_NANOS) {
            visible = false;
            return 0.0F;
        }
        float fadeIn = Math.min(1.0F, (float) shownFor / (float) FADE_IN_NANOS);
        float fadeOut = 1.0F;
        if (releaseRequested) {
            long minHold = (long) (ClientConfig.MIN_DISPLAY_SECONDS.get() * 1_000_000_000.0D);
            long holdUntil = Math.max(releasedAt, shownAt + minHold);
            if (now > holdUntil) {
                fadeOut = 1.0F - (float) (now - holdUntil) / (float) FADE_OUT_NANOS;
                if (fadeOut <= 0.0F) {
                    visible = false;
                    releaseRequested = false;
                    return 0.0F;
                }
            }
        }
        return fadeIn * fadeOut;
    }

    public static float bob() {
        double seconds = (System.nanoTime() - shownAt) / 1_000_000_000.0D;
        return (float) (0.5D - 0.5D * Math.cos(seconds * Math.PI * 3.0D));
    }
}
