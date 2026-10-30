package com.whx.automation.helper;

/**
 * 屏幕坐标 → evdev 轴坐标的换算。
 *
 * <p><b>规则来自实测，不是猜的</b>：Android 的 InputReader 把**每个轴各自**按自己的
 * absMax 线性缩放到屏幕对应维度，**不做轴交换**：
 * <pre>
 *   注入 raw(X=463, Y=114) → 应用收到 (260, 154)
 *   260 = 463/1279×720       （X 轴 → 屏幕宽）
 *   154 = 114/719×1280 − 状态栏（Y 轴 → 屏幕高）
 * </pre>
 *
 * <p>早期版本根据「absMaxX≈屏幕高」推断"面板是横屏坐标、必须交换"，结论是错的：
 * 交换后坐标落到窗口外，表现为**只收到 ACTION_DOWN、收不到 ACTION_UP**，按钮无响应且不报错。
 * 这个类把换算抽成纯函数，就是为了能用单元测试把这条结论钉死。
 */
public final class AxisMapper {

    private final int screenW;
    private final int screenH;
    private final int absMaxX;
    private final int absMaxY;
    private final boolean swapAxes;

    public AxisMapper(int screenW, int screenH, int absMaxX, int absMaxY, boolean swapAxes) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.absMaxX = absMaxX;
        this.absMaxY = absMaxY;
        this.swapAxes = swapAxes;
    }

    /** 屏幕 x（或交换后的屏幕 y）→ 轴 X。 */
    public int axisX(int x, int y) {
        int v = swapAxes ? y : x;
        int range = swapAxes ? screenH : screenW;
        return scale(v, range, absMaxX);
    }

    /** 屏幕 y（或交换后的屏幕 x）→ 轴 Y。 */
    public int axisY(int x, int y) {
        int v = swapAxes ? x : y;
        int range = swapAxes ? screenW : screenH;
        return scale(v, range, absMaxY);
    }

    /** 线性映射并夹紧到 [0, absMax]。 */
    private static int scale(int value, int screenRange, int absMax) {
        if (screenRange <= 1) {
            return 0;
        }
        long v = (long) value * absMax / (screenRange - 1);
        if (v < 0) v = 0;
        if (v > absMax) v = absMax;
        return (int) v;
    }
}
