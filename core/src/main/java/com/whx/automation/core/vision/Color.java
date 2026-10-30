package com.whx.automation.core.vision;

/**
 * 颜色工具：解析、相似度判断。
 *
 * <p>内部统一用 Android 的 ARGB 打包 int（{@code 0xAARRGGBB}），与 {@code Bitmap.getPixels}
 * 的格式一致，避免逐像素拆包再打包。
 */
public final class Color {

    /** 默认容差：单通道最大差值。 */
    public static final int DEFAULT_TOLERANCE = 8;

    /** 解析 "#RRGGBB" / "RRGGBB" / "#AARRGGBB"，返回 0xFFRRGGBB。 */
    public static int parse(String spec) {
        if (spec == null) {
            throw new IllegalArgumentException("颜色不能为 null");
        }
        String s = spec.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 6) {
            return 0xFF000000 | (int) Long.parseLong(s, 16);
        }
        if (s.length() == 8) {
            return (int) Long.parseLong(s, 16);
        }
        throw new IllegalArgumentException("颜色格式应为 #RRGGBB 或 #AARRGGBB: " + spec);
    }

    /** [r, g, b] 三元组转 ARGB。 */
    public static int of(int r, int g, int b) {
        check(r);
        check(g);
        check(b);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    public static int green(int argb) {
        return (argb >> 8) & 0xFF;
    }

    public static int blue(int argb) {
        return argb & 0xFF;
    }

    public static String toHex(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    /**
     * 判断两个颜色是否在容差内。
     *
     * <p>用「逐通道绝对差 ≤ tolerance」而不是欧氏距离：更符合"肉眼看起来一样"的直觉，
     * 也比开方便宜。容差 0 时等价于严格相等。
     */
    public static boolean similar(int a, int b, int tolerance) {
        if (tolerance <= 0) {
            return (a & 0xFFFFFF) == (b & 0xFFFFFF);
        }
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        if (dr > tolerance) return false;
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        if (dg > tolerance) return false;
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return db <= tolerance;
    }

    private static void check(int v) {
        if (v < 0 || v > 255) {
            throw new IllegalArgumentException("通道值应在 0~255: " + v);
        }
    }

    private Color() {
    }
}
