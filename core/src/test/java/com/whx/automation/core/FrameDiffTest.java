package com.whx.automation.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 帧差异：自动化里判断"这一步到底有没有生效"的核心指标。 */
public class FrameDiffTest {

    private Frame frame(int w, int h, int color) {
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, color);
        return new Frame(1, System.currentTimeMillis(), 0, 0, w, h, px);
    }

    @Test
    public void identicalFramesAreSame() {
        Frame a = frame(40, 30, 0xFF202020);
        Frame b = frame(40, 30, 0xFF202020);
        FrameDiff.Result r = FrameDiff.compare(a, b);
        assertTrue(r.same);
        assertEquals(0, r.changedPixels);
        assertEquals(0.0, r.ratio, 1e-9);
    }

    @Test
    public void detectsChangedRegionAndBoundingBox() {
        Frame a = frame(40, 30, 0xFF202020);
        Frame b = frame(40, 30, 0xFF202020);
        for (int y = 10; y < 20; y++) {
            for (int x = 5; x < 15; x++) {
                b.pixels[y * 40 + x] = 0xFFFFFFFF;
            }
        }
        FrameDiff.Result r = FrameDiff.compare(a, b);
        assertFalse(r.same);
        assertEquals(100, r.changedPixels);
        assertEquals(100.0 / (40 * 30), r.ratio, 1e-6);
        assertEquals(5, r.x);
        assertEquals(10, r.y);
        assertEquals(10, r.w);
        assertEquals(10, r.h);
    }

    @Test
    public void smallNoiseBelowThresholdIsIgnored() {
        Frame a = frame(20, 20, 0xFF202020);
        Frame b = frame(20, 20, 0xFF202020);
        b.pixels[0] = 0xFF242424;      // 每通道差 4，低于默认阈值 12
        assertTrue(FrameDiff.compare(a, b).same);
    }

    @Test
    public void customThresholdIsRespected() {
        Frame a = frame(20, 20, 0xFF202020);
        Frame b = frame(20, 20, 0xFF202020);
        b.pixels[0] = 0xFF242424;
        assertFalse(FrameDiff.compare(a, b, 2).same);
    }

    @Test(expected = IllegalArgumentException.class)
    public void sizeMismatchIsRejected() {
        FrameDiff.compare(frame(10, 10, 0), frame(20, 10, 0));
    }

    @Test
    public void resultIsReadableForLogs() {
        Frame a = frame(10, 10, 0);
        Frame b = frame(10, 10, 0);
        b.pixels[5] = 0xFFFFFFFF;
        FrameDiff.Result r = FrameDiff.compare(a, b);
        assertTrue(r.toString().contains("1.00%"));
    }
}
