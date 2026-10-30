package com.whx.automation.core.vision;

/** 图像识别的命中结果（找图用）。 */
public final class Hit {
    public final double score;
    public final int x;
    public final int y;
    public final int width;
    public final int height;

    public Hit(double score, int x, int y, int width, int height) {
        this.score = score;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public Point center() {
        return new Point(x + width / 2, y + height / 2);
    }

    @Override
    public String toString() {
        return String.format("%.3f@(%d,%d,%d×%d)", score, x, y, width, height);
    }
}
