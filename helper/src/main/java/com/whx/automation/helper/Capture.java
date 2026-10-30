package com.whx.automation.helper;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.lang.reflect.Method;

/**
 * 屏幕捕获：SurfaceControl 反射调用。
 *
 * <p>实测要点（雷电 9 / Android 9）：
 * <ul>
 *   <li>设备没有 framebuffer 节点，只能用 SurfaceControl；</li>
 *   <li>{@code screenshot()} 返回的位图常为 {@code Config.HARDWARE}，
 *       直接 {@code getPixels()} 会抛
 *       IllegalStateException: unable to getPixels() ... Config#HARDWARE；</li>
 *   <li>必须 {@code copy(ARGB_8888)} 回读，实测约 5.7ms；合成本身约 3.5ms。</li>
 * </ul>
 */
public final class Capture {

    private static Method screenshotRectIntIntInt;  // API 28:  screenshot(Rect, w, h, rotation)
    private static Method screenshotRectIntInt;     // API 26~27: screenshot(Rect, w, h)
    private static Method screenshotIntInt;         // 早期: screenshot(w, h)
    private static String apiName = "none";

    static {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            screenshotRectIntIntInt = get(sc, "screenshot", Rect.class, int.class, int.class, int.class);
            if (screenshotRectIntIntInt != null) {
                apiName = "screenshot(Rect,w,h,rotation)";
            } else {
                screenshotRectIntInt = get(sc, "screenshot", Rect.class, int.class, int.class);
                if (screenshotRectIntInt != null) {
                    apiName = "screenshot(Rect,w,h)";
                } else {
                    screenshotIntInt = get(sc, "screenshot", int.class, int.class);
                    if (screenshotIntInt != null) {
                        apiName = "screenshot(w,h)";
                    }
                }
            }
        } catch (Throwable t) {
            apiName = "unavailable: " + t;
        }
    }

    private static Method get(Class<?> c, String name, Class<?>... params) {
        try {
            Method m = c.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String apiName() {
        return apiName;
    }

    public static boolean available() {
        return screenshotRectIntIntInt != null
                || screenshotRectIntInt != null
                || screenshotIntInt != null;
    }

    /**
     * 抓一帧。
     *
     * @param region {@code [x, y, w, h]}，传 null 表示整屏
     * @return ARGB_8888 像素，长度 = 宽 × 高
     */
    public static Shot shot(int w, int h, int[] region) throws Exception {
        long t0 = System.nanoTime();
        Bitmap bmp = invoke(w, h);
        long t1 = System.nanoTime();
        if (bmp == null) {
            throw new IllegalStateException("SurfaceControl 返回 null（权限或 SELinux 拒绝）");
        }

        boolean hardware = bmp.getConfig() == Bitmap.Config.HARDWARE;
        Bitmap soft = bmp;
        if (hardware) {
            soft = bmp.copy(Bitmap.Config.ARGB_8888, false);
            bmp.recycle();
            if (soft == null) {
                throw new IllegalStateException("HARDWARE 位图回读失败");
            }
        }
        long t2 = System.nanoTime();

        int bw = soft.getWidth();
        int bh = soft.getHeight();
        int[] full = new int[bw * bh];
        soft.getPixels(full, 0, bw, 0, 0, bw, bh);
        soft.recycle();
        long t3 = System.nanoTime();

        int[] out;
        int ow = bw;
        int oh = bh;
        if (region != null && region.length == 4) {
            int rx = Math.max(0, region[0]);
            int ry = Math.max(0, region[1]);
            int rw = Math.min(region[2], bw - rx);
            int rh = Math.min(region[3], bh - ry);
            if (rw <= 0 || rh <= 0) {
                throw new IllegalArgumentException("区域非法: " + rx + "," + ry + "," + rw + "," + rh);
            }
            out = new int[rw * rh];
            for (int y = 0; y < rh; y++) {
                System.arraycopy(full, (ry + y) * bw + rx, out, y * rw, rw);
            }
            ow = rw;
            oh = rh;
        } else {
            out = full;
        }

        return new Shot(ow, oh, out, hardware,
                (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6);
    }

    private static Bitmap invoke(int w, int h) throws Exception {
        if (screenshotRectIntIntInt != null) {
            return (Bitmap) screenshotRectIntIntInt.invoke(null, new Rect(0, 0, w, h), w, h, 0);
        }
        if (screenshotRectIntInt != null) {
            return (Bitmap) screenshotRectIntInt.invoke(null, new Rect(0, 0, w, h), w, h);
        }
        if (screenshotIntInt != null) {
            return (Bitmap) screenshotIntInt.invoke(null, w, h);
        }
        throw new UnsupportedOperationException("本机没有可用的 SurfaceControl 截图接口");
    }

    /** 一帧结果与各阶段耗时（毫秒）。 */
    public static final class Shot {
        public final int width;
        public final int height;
        public final int[] argb;
        public final boolean hardwareBitmap;
        public final double composeMs;
        public final double readbackMs;
        public final double copyMs;

        Shot(int width, int height, int[] argb, boolean hardwareBitmap,
             double composeMs, double readbackMs, double copyMs) {
            this.width = width;
            this.height = height;
            this.argb = argb;
            this.hardwareBitmap = hardwareBitmap;
            this.composeMs = composeMs;
            this.readbackMs = readbackMs;
            this.copyMs = copyMs;
        }

        public double totalMs() {
            return composeMs + readbackMs + copyMs;
        }
    }

    private Capture() {
    }
}
