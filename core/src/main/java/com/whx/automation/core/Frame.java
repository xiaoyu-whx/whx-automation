package com.whx.automation.core;

/**
 * 一帧屏幕快照。
 *
 * 设计要点（项目书 §4.1：减少 Bitmap 和像素数组复制）：
 * <ul>
 *   <li>像素以 {@code int[]} 持有（ARGB_8888），识别算法直接读它，不再经过 Bitmap.getPixel() 的 JNI 开销；</li>
 *   <li>同一帧内多次识别共享这一份像素，结果一致；</li>
 *   <li>帧带 id 与抓取时间，跨帧使用会被日志标注出来。</li>
 * </ul>
 *
 * 阶段一只有数据结构，抓帧由阶段二的 {@code ScreenSource} 实现。
 */
public final class Frame {

    public final long id;
    public final long capturedAtMs;
    /** 帧左上角在整屏中的坐标；整屏截图时为 (0,0)。 */
    public final int originX;
    public final int originY;
    public final int width;
    public final int height;
    /** 长度 = width * height，行优先，ARGB_8888。 */
    public final int[] pixels;

    public Frame(long id, long capturedAtMs, int originX, int originY,
                 int width, int height, int[] pixels) {
        if (pixels == null || pixels.length != width * height) {
            throw new IllegalArgumentException(
                    "像素数组长度不匹配: 期望 " + (width * height) + "，实际 "
                            + (pixels == null ? -1 : pixels.length));
        }
        this.id = id;
        this.capturedAtMs = capturedAtMs;
        this.originX = originX;
        this.originY = originY;
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    public int ageMs() {
        return (int) (System.currentTimeMillis() - capturedAtMs);
    }

    /** 取帧内像素（相对帧左上角）。越界返回 0，调用方需自行保证范围。 */
    public int colorAt(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return 0;
        }
        return pixels[y * width + x];
    }
}
