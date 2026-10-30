package com.whx.automation.core;

/**
 * 帧差异：判断"这一步操作到底有没有让屏幕变化"。
 *
 * <p>这是自动化调试里最有用的一个指标 —— 脚本"全绿"但什么都没发生，靠它当场暴露。
 * 用「逐像素灰度差 > 阈值」而不是严格相等：屏幕有抗锯齿与压缩噪声，严格比较会给出假差异。
 */
public final class FrameDiff {

    /** 变化率与变化区域。 */
    public static final class Result {
        public final double ratio;
        public final int changedPixels;
        public final int x;
        public final int y;
        public final int w;
        public final int h;
        public final boolean same;

        Result(double ratio, int changedPixels, int x, int y, int w, int h) {
            this.ratio = ratio;
            this.changedPixels = changedPixels;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.same = changedPixels == 0;
        }

        @Override
        public String toString() {
            return String.format("%.2f%% (%d px) bbox=%d,%d,%d,%d",
                    ratio * 100, changedPixels, x, y, w, h);
        }
    }

    /** 比较两帧（尺寸必须一致）。threshold 为单通道灰度差阈值，默认 12 可忽略噪声。 */
    public static Result compare(Frame a, Frame b, int threshold) {
        if (a.width != b.width || a.height != b.height) {
            throw new IllegalArgumentException("两帧尺寸不同，无法比较");
        }
        int w = a.width;
        int h = a.height;
        int changed = 0;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int pa = a.pixels[row + x];
                int pb = b.pixels[row + x];
                int da = Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF));
                int dg = Math.abs(((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF));
                int db = Math.abs((pa & 0xFF) - (pb & 0xFF));
                if (da > threshold || dg > threshold || db > threshold) {
                    changed++;
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (changed == 0) {
            return new Result(0, 0, 0, 0, 0, 0);
        }
        return new Result((double) changed / (w * (double) h), changed,
                minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    public static Result compare(Frame a, Frame b) {
        return compare(a, b, 12);
    }

    private FrameDiff() {
    }
}
