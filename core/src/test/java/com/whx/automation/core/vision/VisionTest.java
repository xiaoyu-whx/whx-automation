package com.whx.automation.core.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.whx.automation.core.Frame;

import org.junit.Test;

import java.util.List;
import java.util.Random;

/** 识别算法：取色、找色、多点找色、找图。全部用合成图像，不需要设备。 */
public class VisionTest {

    private static final int W = 200;
    private static final int H = 120;
    private static final int BG = 0xFF101010;

    private Frame frame(int[][] patches) {
        int[] px = new int[W * H];
        java.util.Arrays.fill(px, BG);
        int[][] p = patches;
        for (int i = 0; i < p.length; i++) {
            int x0 = p[i][0], y0 = p[i][1], w = p[i][2], h = p[i][3], color = p[i][4];
            for (int y = y0; y < y0 + h; y++) {
                for (int x = x0; x < x0 + w; x++) {
                    px[y * W + x] = color;
                }
            }
        }
        return new Frame(1, System.currentTimeMillis(), 0, 0, W, H, px);
    }

    /**
     * 造一张"类 UI"图像：渐变背景 + 带边框的按钮 + 文字状条纹。
     *
     * <p>不用纯随机噪声：金字塔粗筛对白噪声不友好（下采样后纹理丢失），
     * 而真实界面是平滑色块 + 边缘，这才是算法要面对的场景。
     */
    private Frame texturedFrame() {
        int[] px = new int[W * H];
        // 1) 垂直渐变背景
        for (int y = 0; y < H; y++) {
            int v = 0x20 + (y * 0x20 / H);
            int c = 0xFF000000 | (v << 16) | (v << 8) | v;
            for (int x = 0; x < W; x++) {
                px[y * W + x] = c;
            }
        }
        // 2) 一个带白边的按钮（强边缘 + 纯色块）
        for (int y = 30; y < 70; y++) {
            for (int x = 80; x < 160; x++) {
                boolean border = (x < 83 || x >= 157 || y < 33 || y >= 67);
                px[y * W + x] = border ? 0xFFFFFFFF : 0xFFFF5722;
            }
        }
        // 3) 文字状条纹（横向短划线）
        for (int row = 0; row < 5; row++) {
            int y0 = 85 + row * 6;
            for (int seg = 0; seg < 8; seg++) {
                int x0 = 20 + seg * 20;
                for (int y = y0; y < y0 + 3; y++) {
                    for (int x = x0; x < x0 + 14; x++) {
                        px[y * W + x] = 0xFFE0E0E0;
                    }
                }
            }
        }
        return new Frame(2, System.currentTimeMillis(), 0, 0, W, H, px);
    }

    // ------------------------------------------------------------------ 取色
    @Test
    public void colorAtReturnsExactValue() {
        Frame f = frame(new int[][]{{10, 20, 5, 5, 0xFF00FF00}});
        assertEquals(0xFF00FF00, Vision.colorAt(f, 12, 22));
        assertTrue(Vision.isColor(f, 12, 22, 0xFF00FF00, 0));
        assertTrue(Vision.isColor(f, 0, 0, BG, 0));
    }

    @Test
    public void colorAtRejectsOutOfBounds() {
        Frame f = frame(new int[][]{});
        try {
            Vision.colorAt(f, W, 0);
            fail("越界应当抛异常，而不是静默返回 0");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ------------------------------------------------------------------ 找色
    @Test
    public void findColorReturnsTopLeftFirstInRowOrder() {
        Frame f = frame(new int[][]{
                {50, 60, 4, 4, 0xFFFF0000},
                {10, 10, 4, 4, 0xFFFF0000},
        });
        Point p = Vision.findColor(f, 0xFFFF0000, 0, null, 1, Vision.Order.ROW_FIRST);
        assertNotNull(p);
        assertEquals(10, p.x);
        assertEquals(10, p.y);
    }

    @Test
    public void findColorReturnsNullWhenAbsent() {
        Frame f = frame(new int[][]{});
        assertNull(Vision.findColor(f, 0xFFFF00FF, 0, null, 1, Vision.Order.ROW_FIRST));
    }

    @Test
    public void findColorRespectsRegion() {
        Frame f = frame(new int[][]{
                {10, 10, 4, 4, 0xFF00FF00},
                {150, 80, 4, 4, 0xFF00FF00},
        });
        Point all = Vision.findColor(f, 0xFF00FF00, 0, null, 1, Vision.Order.ROW_FIRST);
        assertEquals(10, all.x);
        Point inRight = Vision.findColor(f, 0xFF00FF00, 0, new int[]{100, 0, 100, 120}, 1,
                Vision.Order.ROW_FIRST);
        assertNotNull(inRight);
        assertEquals(150, inRight.x);
    }

    @Test
    public void findColorAllRespectsMaxAndStep() {
        Frame f = frame(new int[][]{{0, 0, 20, 20, 0xFF0000FF}});
        List<Point> all = Vision.findColorAll(f, 0xFF0000FF, 0, new int[]{0, 0, 20, 20}, 1, 0);
        assertEquals(400, all.size());
        List<Point> capped = Vision.findColorAll(f, 0xFF0000FF, 0, new int[]{0, 0, 20, 20}, 1, 5);
        assertEquals(5, capped.size());
        List<Point> stepped = Vision.findColorAll(f, 0xFF0000FF, 0, new int[]{0, 0, 20, 20}, 2, 0);
        assertEquals(100, stepped.size());
    }

    @Test
    public void findColorToleranceWorks() {
        Frame f = frame(new int[][]{{5, 5, 2, 2, 0xFF808080}});
        assertNotNull(Vision.findColor(f, 0xFF888888, 8, null, 1, Vision.Order.ROW_FIRST));
        assertNull(Vision.findColor(f, 0xFF888888, 7, null, 1, Vision.Order.ROW_FIRST));
    }

    // -------------------------------------------------------------- 多点找色
    @Test
    public void findMultiColorRequiresAllOffsets() {
        Frame f = frame(new int[][]{
                {30, 30, 2, 2, 0xFFFF0000},
                {40, 30, 2, 2, 0xFF00FF00},
                {30, 40, 2, 2, 0xFF0000FF},
        });
        int[][] offsets = {{10, 0, 0xFF00FF00}, {0, 10, 0xFF0000FF}};
        Point p = Vision.findMultiColor(f, 0xFFFF0000, offsets, 0, null, 1);
        assertNotNull(p);
        assertEquals(30, p.x);
        assertEquals(30, p.y);
    }

    @Test
    public void findMultiColorFailsWhenOffsetMissing() {
        Frame f = frame(new int[][]{{30, 30, 2, 2, 0xFFFF0000}});
        int[][] offsets = {{10, 0, 0xFF00FF00}};
        assertNull(Vision.findMultiColor(f, 0xFFFF0000, offsets, 0, null, 1));
    }

    // ------------------------------------------------------------------ 找图
    @Test
    public void findImageLocatesTemplateAtExactPosition() {
        Frame f = texturedFrame();
        int tw = 40, th = 30, tx = 140, ty = 25;
        int[] tpl = Vision.crop(f, tx, ty, tw, th);

        Hit hit = Vision.findImage(f, tpl, tw, th, 0.9, null);
        assertNotNull("同样的模板必须能在同一帧里找回原位", hit);
        assertEquals(tx, hit.x);
        assertEquals(ty, hit.y);
        assertTrue("完全相同的区域得分应接近 1.0，实际 " + hit.score, hit.score > 0.99);
    }

    @Test
    public void findImageRespectsRegionAndReturnsScreenCoords() {
        Frame f = texturedFrame();
        int tw = 40, th = 30;
        int[] tpl = Vision.crop(f, 140, 25, tw, th);
        // 只在按钮右半侧的区域里找，验证 region 生效且返回屏幕坐标
        Hit hit = Vision.findImage(f, tpl, tw, th, 0.9, new int[]{120, 10, 80, 60});
        assertNotNull(hit);
        assertEquals(140, hit.x);
        assertEquals(25, hit.y);
    }

    @Test
    public void findImageReturnsNullWhenTemplateAbsent() {
        Frame f = texturedFrame();
        int[] tpl = new int[20 * 20];
        Random rnd = new Random(7);
        for (int i = 0; i < tpl.length; i++) {
            tpl[i] = 0xFF000000 | rnd.nextInt(0xFFFFFF);
        }
        assertNull(Vision.findImage(f, tpl, 20, 20, 0.95, null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void findImageRejectsFlatTemplate() {
        Frame f = texturedFrame();
        int[] flat = new int[20 * 20];
        java.util.Arrays.fill(flat, BG);
        Vision.findImage(f, flat, 20, 20, 0.9, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void findImageRejectsMismatchedSize() {
        Frame f = texturedFrame();
        Vision.findImage(f, new int[10], 5, 5, 0.9, null);
    }

    @Test
    public void cropValidatesBounds() {
        Frame f = texturedFrame();
        int[] ok = Vision.crop(f, 10, 10, 20, 20);
        assertEquals(400, ok.length);
        try {
            Vision.crop(f, W - 5, 0, 20, 20);
            fail("越界裁剪应当抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void regionOffsetsAreAppliedForPartialFrames() {
        // 帧只是屏幕的一块（origin 偏移）：返回坐标必须是屏幕坐标
        int[] px = new int[50 * 50];
        java.util.Arrays.fill(px, BG);
        px[10 * 50 + 10] = 0xFFFF00FF;
        Frame sub = new Frame(3, System.currentTimeMillis(), 300, 400, 50, 50, px);

        Point p = Vision.findColor(sub, 0xFFFF00FF, 0, null, 1, Vision.Order.ROW_FIRST);
        assertNotNull(p);
        assertEquals(310, p.x);
        assertEquals(410, p.y);
        assertEquals(0xFFFF00FF, Vision.colorAt(sub, 310, 410));
    }
}
