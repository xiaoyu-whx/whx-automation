package com.whx.automation.core.vision;

import com.whx.automation.core.Frame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 图像识别引擎：取色 / 找色 / 多点找色 / 模板匹配。
 *
 * <p>设计原则（项目书 §4.2）：先自主实现，不引 OpenCV。
 * 所有算法直接读 {@link Frame#pixels} 的 int[]，不经过 Bitmap.getPixel() 的 JNI 开销。
 *
 * <p>坐标：入参与返回值都是**屏幕坐标**；Frame 可能只是屏幕的一块（带 originX/Y），
 * 换算在这里完成，调用方不需要关心帧偏移。
 */
public final class Vision {

    /** 扫描方向。 */
    public enum Order {
        /** 逐行扫描（先找最上面的）。 */
        ROW_FIRST,
        /** 逐列扫描（先找最左边的）。 */
        COLUMN_FIRST
    }

    // ============================================================ 取色 / 判断
    public static int colorAt(Frame frame, int x, int y) {
        int lx = x - frame.originX;
        int ly = y - frame.originY;
        if (lx < 0 || ly < 0 || lx >= frame.width || ly >= frame.height) {
            throw new IllegalArgumentException("坐标越界: (" + x + ", " + y + ") 不在帧 "
                    + frame.width + "×" + frame.height + " 内（origin="
                    + frame.originX + "," + frame.originY + "）");
        }
        return frame.pixels[ly * frame.width + lx];
    }

    public static boolean isColor(Frame frame, int x, int y, int rgb, int tolerance) {
        return Color.similar(colorAt(frame, x, y), rgb, tolerance);
    }

    // ================================================================== 找色
    /**
     * 找第一个匹配的颜色点。
     *
     * @param region {@code [x, y, w, h]}，null = 整帧
     * @param step   采样步长，≥1；低配设备可用 2~3 提速
     * @return 屏幕坐标，未找到返回 null
     */
    public static Point findColor(Frame frame, int rgb, int tolerance,
                                  int[] region, int step, Order order) {
        Bounds b = bounds(frame, region);
        int st = Math.max(1, step);
        int tr = (rgb >> 16) & 0xFF;
        int tg = (rgb >> 8) & 0xFF;
        int tb = rgb & 0xFF;

        if (order == Order.COLUMN_FIRST) {
            for (int x = b.x; x < b.x + b.w; x += st) {
                for (int y = b.y; y < b.y + b.h; y += st) {
                    if (match(frame, x, y, tr, tg, tb, tolerance)) {
                        return new Point(x, y);
                    }
                }
            }
        } else {
            for (int y = b.y; y < b.y + b.h; y += st) {
                int row = (y - frame.originY) * frame.width - frame.originX;
                for (int x = b.x; x < b.x + b.w; x += st) {
                    int p = frame.pixels[row + x];
                    if (diff(((p >> 16) & 0xFF) - tr, tolerance)) continue;
                    if (diff(((p >> 8) & 0xFF) - tg, tolerance)) continue;
                    if (diff((p & 0xFF) - tb, tolerance)) continue;
                    return new Point(x, y);
                }
            }
        }
        return null;
    }

    /** 找所有匹配的颜色点，最多 max 个（max ≤ 0 表示不限）。 */
    public static List<Point> findColorAll(Frame frame, int rgb, int tolerance,
                                           int[] region, int step, int max) {
        Bounds b = bounds(frame, region);
        int st = Math.max(1, step);
        List<Point> out = new ArrayList<>();
        for (int y = b.y; y < b.y + b.h; y += st) {
            for (int x = b.x; x < b.x + b.w; x += st) {
                if (match(frame, x, y, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, tolerance)) {
                    out.add(new Point(x, y));
                    if (max > 0 && out.size() >= max) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    // ============================================================= 多点找色
    /**
     * 多点找色：先找锚点颜色，再校验若干相对偏移上的颜色。
     *
     * @param offsets 每项 {@code [dx, dy, rgb]}
     */
    public static Point findMultiColor(Frame frame, int anchorRgb, int[][] offsets,
                                       int tolerance, int[] region, int step) {
        Bounds b = bounds(frame, region);
        int st = Math.max(1, step);
        for (int y = b.y; y < b.y + b.h; y += st) {
            for (int x = b.x; x < b.x + b.w; x += st) {
                if (!match(frame, x, y, (anchorRgb >> 16) & 0xFF,
                        (anchorRgb >> 8) & 0xFF, anchorRgb & 0xFF, tolerance)) {
                    continue;
                }
                if (checkOffsets(frame, x, y, offsets, tolerance)) {
                    return new Point(x, y);
                }
            }
        }
        return null;
    }

    private static boolean checkOffsets(Frame frame, int x, int y, int[][] offsets, int tolerance) {
        for (int[] off : offsets) {
            int lx = x + off[0] - frame.originX;
            int ly = y + off[1] - frame.originY;
            if (lx < 0 || ly < 0 || lx >= frame.width || ly >= frame.height) {
                return false;
            }
            int p = frame.pixels[ly * frame.width + lx];
            int rgb = off[2];
            if (diff(((p >> 16) & 0xFF) - ((rgb >> 16) & 0xFF), tolerance)) return false;
            if (diff(((p >> 8) & 0xFF) - ((rgb >> 8) & 0xFF), tolerance)) return false;
            if (diff((p & 0xFF) - (rgb & 0xFF), tolerance)) return false;
        }
        return true;
    }

    // ================================================================== 找图
    /**
     * 模板匹配：灰度 → 金字塔粗筛（降采样 + 步长）→ 全分辨率精修（精确 NCC）。
     *
     * @param template ARGB 像素，长度 = tw × th
     * @param region   {@code [x,y,w,h]}，null = 整帧
     * @return 最佳命中；未达阈值返回 null
     */
    public static Hit findImage(Frame frame, int[] template, int tw, int th,
                                double threshold, int[] region) {
        if (template == null || tw <= 0 || th <= 0 || template.length != tw * th) {
            throw new IllegalArgumentException("模板尺寸与像素数不匹配");
        }
        Bounds b = bounds(frame, region);
        if (tw > b.w || th > b.h) {
            return null;
        }

        int[] gray = toGray(frame, b);
        int[] tplGray = new int[tw * th];
        for (int i = 0; i < template.length; i++) {
            tplGray[i] = luma(template[i]);
        }
        TplStats tplStats = TplStats.of(tplGray, tw, th, 1);
        if (tplStats.variance() < 4.0) {
            throw new IllegalArgumentException(
                    "模板几乎没有纹理变化（方差 " + String.format("%.2f", tplStats.variance())
                            + "），无法可靠匹配；请选有文字/图标的区域");
        }

        int factor = 1;
        int stride = 1;
        if (tw >= 16 && th >= 16 && b.w >= tw * 2 && b.h >= th * 2) {
            factor = 4;
            stride = 2;
        } else if (tw >= 8 && th >= 8) {
            factor = 2;
            stride = 1;
        }

        List<int[]> candidates;
        if (factor > 1) {
            int[] small = downsample(gray, b.w, b.h, factor);
            int[] tplSmall = downsample(tplGray, tw, th, factor);
            int sw = b.w / factor;
            int sh = b.h / factor;
            int stw = tw / factor;
            int sth = th / factor;
            candidates = coarse(small, sw, sh, tplSmall, stw, sth, stride, 4);
        } else {
            candidates = new ArrayList<>();
            candidates.add(new int[]{0, 0});   // 小模板直接全扫
        }

        double bestScore = -2;
        int bestX = -1;
        int bestY = -1;

        if (factor > 1) {
            // 精修用爬山法：从每个候选出发，比较 8 邻域，直到不再改善。
            // 比在 ±9px 窗口内做全量穷举快一个数量级（穷举要算 361 次，爬山一般 10~20 次）。
            int maxX = b.w - tw;
            int maxY = b.h - th;
            for (int[] cand : candidates) {
                int x = clamp(cand[0] * factor, 0, maxX);
                int y = clamp(cand[1] * factor, 0, maxY);
                double cur = ncc(gray, b.w, x, y, tplGray, tw, th, 1, tplStats);
                boolean improved = true;
                int guard = 0;
                while (improved && guard++ < 40) {
                    improved = false;
                    int bx = x;
                    int by = y;
                    double bs = cur;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            int nx = clamp(x + dx, 0, maxX);
                            int ny = clamp(y + dy, 0, maxY);
                            if (nx == x && ny == y) continue;
                            double sc = ncc(gray, b.w, nx, ny, tplGray, tw, th, 1, tplStats);
                            if (sc > bs) {
                                bs = sc;
                                bx = nx;
                                by = ny;
                            }
                        }
                    }
                    if (bs > cur) {
                        cur = bs;
                        x = bx;
                        y = by;
                        improved = true;
                    }
                }
                if (cur > bestScore) {
                    bestScore = cur;
                    bestX = x;
                    bestY = y;
                }
            }

            // 爬山容易在平台区停下（相邻分数相等不计为改善）：
            // 只对当前最优候选做一次 ±2 小穷举（25 次 NCC），而不是对每个候选都做。
            int cx0 = bestX;
            int cy0 = bestY;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int nx = clamp(cx0 + dx, 0, maxX);
                    int ny = clamp(cy0 + dy, 0, maxY);
                    double sc = ncc(gray, b.w, nx, ny, tplGray, tw, th, 1, tplStats);
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestX = nx;
                        bestY = ny;
                    }
                }
            }
        } else {
            for (int y = 0; y <= b.h - th; y++) {
                for (int x = 0; x <= b.w - tw; x++) {
                    double s = ncc(gray, b.w, x, y, tplGray, tw, th, 1, tplStats);
                    if (s > bestScore) {
                        bestScore = s;
                        bestX = x;
                        bestY = y;
                    }
                }
            }
        }

        if (bestX < 0 || bestScore < threshold) {
            return null;
        }
        return new Hit(bestScore, b.x + bestX, b.y + bestY, tw, th);
    }

    // -------------------------------------------------------------- 内部实现
    /** 粗筛：降采样图上按步长滑动，取 NCC 最高的若干候选（返回降采样坐标）。 */
    private static List<int[]> coarse(int[] img, int iw, int ih,
                                      int[] tpl, int tw, int th, int stride, int topK) {
        if (tw > iw || th > ih) {
            return new ArrayList<>();
        }
        TplStats stats = TplStats.of(tpl, tw, th, stride);
        double[] all = new double[((iw - tw) / stride + 1) * ((ih - th) / stride + 1)];
        int[] cx = new int[all.length];
        int[] cy = new int[all.length];
        int n = 0;
        for (int y = 0; y <= ih - th; y += stride) {
            for (int x = 0; x <= iw - tw; x += stride) {
                all[n] = ncc(img, iw, x, y, tpl, tw, th, 2, stats);
                cx[n] = x;
                cy[n] = y;
                n++;
            }
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        final double[] scores = all;
        Arrays.sort(order, (a, b) -> Double.compare(scores[b], scores[a]));

        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, n); i++) {
            out.add(new int[]{cx[order[i]], cy[order[i]]});
        }
        return out;
    }

    /**
     * 归一化互相关：{@code innerStride} 用于粗筛时跳点采样（更快，只用于排序）。
     */
    private static double ncc(int[] img, int iw, int x0, int y0,
                              int[] tpl, int tw, int th, int innerStride, TplStats stats) {
        long sumI = 0;
        long sumISq = 0;
        long sumIT = 0;
        int st = Math.max(1, innerStride);
        for (int y = 0; y < th; y += st) {
            int row = (y0 + y) * iw + x0;
            int trow = y * tw;
            for (int x = 0; x < tw; x += st) {
                int iv = img[row + x];
                int tv = tpl[trow + x];
                sumI += iv;
                sumISq += (long) iv * iv;
                sumIT += (long) iv * tv;
            }
        }
        double n = stats.count;
        if (n <= 0) return -1;
        double num = sumIT - sumI * (double) stats.sum / n;
        double varI = sumISq - (double) sumI * sumI / n;
        double varT = stats.sq - (double) stats.sum * stats.sum / n;
        if (varI <= 1e-9 || varT <= 1e-9) return -1;
        return num / Math.sqrt(varI * varT);
    }

    private static int[] toGray(Frame frame, Bounds b) {
        int[] gray = new int[b.w * b.h];
        for (int y = 0; y < b.h; y++) {
            int srcRow = (b.y + y - frame.originY) * frame.width + (b.x - frame.originX);
            int dstRow = y * b.w;
            for (int x = 0; x < b.w; x++) {
                gray[dstRow + x] = luma(frame.pixels[srcRow + x]);
            }
        }
        return gray;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int luma(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r * 77 + g * 150 + b * 29) >> 8;   // BT.601 整数近似
    }

    private static int[] downsample(int[] src, int w, int h, int factor) {
        int dw = w / factor;
        int dh = h / factor;
        int[] out = new int[dw * dh];
        int area = factor * factor;
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int acc = 0;
                for (int dy = 0; dy < factor; dy++) {
                    int row = (y * factor + dy) * w + x * factor;
                    for (int dx = 0; dx < factor; dx++) {
                        acc += src[row + dx];
                    }
                }
                out[y * dw + x] = acc / area;
            }
        }
        return out;
    }

    private static boolean match(Frame frame, int x, int y, int tr, int tg, int tb, int tolerance) {
        int lx = x - frame.originX;
        int ly = y - frame.originY;
        int p = frame.pixels[ly * frame.width + lx];
        if (diff(((p >> 16) & 0xFF) - tr, tolerance)) return false;
        if (diff(((p >> 8) & 0xFF) - tg, tolerance)) return false;
        return !diff((p & 0xFF) - tb, tolerance);
    }

    private static boolean diff(int delta, int tolerance) {
        return delta > tolerance || delta < -tolerance;
    }

    /** 计算搜索范围（屏幕坐标）并裁剪到帧内。 */
    private static Bounds bounds(Frame frame, int[] region) {
        int fx = frame.originX;
        int fy = frame.originY;
        int x = fx;
        int y = fy;
        int w = frame.width;
        int h = frame.height;
        if (region != null) {
            if (region.length != 4) {
                throw new IllegalArgumentException("region 必须是 [x, y, w, h]");
            }
            x = Math.max(fx, region[0]);
            y = Math.max(fy, region[1]);
            int ex = Math.min(fx + frame.width, region[0] + region[2]);
            int ey = Math.min(fy + frame.height, region[1] + region[3]);
            w = ex - x;
            h = ey - y;
            if (w <= 0 || h <= 0) {
                throw new IllegalArgumentException("region 与帧没有交集");
            }
        }
        return new Bounds(x, y, w, h);
    }

    private static final class Bounds {
        final int x;
        final int y;
        final int w;
        final int h;

        Bounds(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /**
     * 模板的灰度统计量。
     *
     * <p><b>关键</b>：统计必须与 NCC 的采样步长一致。粗筛用步长 2 采样，
     * 统计量也必须按步长 2 采（否则 ΣI×ΣT 与 ΣI² 的归一化对不上，
     * 相关性会被亮度差主导，排名直接失效 —— 这是实测踩到的坑）。
     */
    private static final class TplStats {
        final long sum;
        final long sq;
        final int count;

        private TplStats(long sum, long sq, int count) {
            this.sum = sum;
            this.sq = sq;
            this.count = count;
        }

        static TplStats of(int[] tpl, int tw, int th, int stride) {
            long s = 0;
            long q = 0;
            int c = 0;
            for (int y = 0; y < th; y += stride) {
                int row = y * tw;
                for (int x = 0; x < tw; x += stride) {
                    int v = tpl[row + x];
                    s += v;
                    q += (long) v * v;
                    c++;
                }
            }
            return new TplStats(s, q, c);
        }

        /** 灰度方差：过低说明模板是纯色块，匹配没有意义。 */
        double variance() {
            double mean = (double) sum / count;
            return (double) sq / count - mean * mean;
        }
    }

    // ============================================================ 模板裁剪
    /** 从帧里裁一块出来当模板（阶段三自检用；以后"框选截图"也用它）。 */
    public static int[] crop(Frame frame, int x, int y, int w, int h) {
        int lx = x - frame.originX;
        int ly = y - frame.originY;
        if (lx < 0 || ly < 0 || lx + w > frame.width || ly + h > frame.height) {
            throw new IllegalArgumentException("裁剪区域越界");
        }
        int[] out = new int[w * h];
        for (int row = 0; row < h; row++) {
            System.arraycopy(frame.pixels, (ly + row) * frame.width + lx, out, row * w, w);
        }
        return out;
    }

    private Vision() {
    }
}
