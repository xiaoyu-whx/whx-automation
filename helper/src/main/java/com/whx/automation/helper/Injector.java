package com.whx.automation.helper;

import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * evdev 触摸注入。
 *
 * <p>对比（实测，雷电 9）：
 * <ul>
 *   <li>本实现（进程内 write）：9 条事件 <b>0.033ms</b>，完整 tap 只受按住时长支配（31ms / hold 30ms）</li>
 *   <li>shell 里逐条 sendevent：每条一个进程，约 17ms，9 条 = 210ms</li>
 *   <li>{@code input tap}：134.6ms</li>
 * </ul>
 *
 * <p>坐标：上层永远给**屏幕坐标**，轴换算（含交换与 absmax 比例映射）在这里完成。
 */
public final class Injector {

    private static final int EV_SYN = 0;
    private static final int EV_KEY = 1;
    private static final int EV_ABS = 3;
    private static final int SYN_REPORT = 0;
    private static final int ABS_MT_SLOT = 0x2f;
    private static final int ABS_MT_TRACKING_ID = 0x39;
    private static final int ABS_MT_POSITION_X = 0x35;
    private static final int ABS_MT_POSITION_Y = 0x36;
    private static final int BTN_TOUCH = 0x14a;

    private final FileOutputStream out;
    private final ByteBuffer buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
    private final InputProbe probe;
    private final int screenW;
    private final int screenH;
    private int nextTrackingId = 1;

    public Injector(InputProbe probe, int screenW, int screenH) throws Exception {
        this.probe = probe;
        this.screenW = screenW;
        this.screenH = screenH;
        this.out = new FileOutputStream(probe.devicePath);
    }

    public InputProbe probe() {
        return probe;
    }

    /** 点击 / 长按。 */
    public void tap(int x, int y, int holdMs) throws Exception {
        int ax = axisX(x, y);
        int ay = axisY(x, y);
        int id = nextTrackingId++;
        if (id > 60000) nextTrackingId = 1;

        write(EV_ABS, ABS_MT_SLOT, 0);
        write(EV_ABS, ABS_MT_TRACKING_ID, id);
        write(EV_ABS, ABS_MT_POSITION_X, ax);
        write(EV_ABS, ABS_MT_POSITION_Y, ay);
        write(EV_KEY, BTN_TOUCH, 1);
        write(EV_SYN, SYN_REPORT, 0);

        sleep(Math.max(0, holdMs));

        write(EV_ABS, ABS_MT_TRACKING_ID, -1);
        write(EV_KEY, BTN_TOUCH, 0);
        write(EV_SYN, SYN_REPORT, 0);
    }

    /** 滑动：按时间等分插值，默认 10ms 一帧。 */
    public void swipe(int[][] points, int durationMs) throws Exception {
        if (points.length < 2) {
            throw new IllegalArgumentException("滑动至少需要 2 个点");
        }
        int id = nextTrackingId++;
        if (id > 60000) nextTrackingId = 1;

        write(EV_ABS, ABS_MT_SLOT, 0);
        write(EV_ABS, ABS_MT_TRACKING_ID, id);
        write(EV_ABS, ABS_MT_POSITION_X, axisX(points[0][0], points[0][1]));
        write(EV_ABS, ABS_MT_POSITION_Y, axisY(points[0][0], points[0][1]));
        write(EV_KEY, BTN_TOUCH, 1);
        write(EV_SYN, SYN_REPORT, 0);

        int frameMs = 10;
        int frames = Math.max(2, durationMs / frameMs);
        long start = System.nanoTime();
        for (int f = 1; f <= frames; f++) {
            double t = (double) f / frames;
            double[] p = pointOnPath(points, t);
            write(EV_ABS, ABS_MT_POSITION_X, axisX((int) p[0], (int) p[1]));
            write(EV_ABS, ABS_MT_POSITION_Y, axisY((int) p[0], (int) p[1]));
            write(EV_SYN, SYN_REPORT, 0);

            long due = start + (long) f * frameMs * 1_000_000L;
            long wait = (due - System.nanoTime()) / 1_000_000L;
            if (wait > 0) {
                sleep((int) wait);
            }
        }

        write(EV_ABS, ABS_MT_TRACKING_ID, -1);
        write(EV_KEY, BTN_TOUCH, 0);
        write(EV_SYN, SYN_REPORT, 0);
    }

    /** 按键：先用 input keyevent（简单可靠），后续再优化成直接写事件节点。 */
    public void key(int keyCode) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"input", "keyevent",
                String.valueOf(keyCode)});
        p.waitFor();
    }

    // --------------------------------------------------------------- 坐标映射
    /**
     * 屏幕坐标 → 轴坐标。
     *
     * <p><b>结论来自实测（用应用内触摸监听器验证）</b>：框架对**每个轴各自**按自己的
     * absMax 缩放到屏幕对应维度，不做轴交换：
     * <pre>
     *   注入 raw(X=463, Y=114) → 应用收到 (260, 154)
     *   260 = 463/1279×720    （X 轴 → 屏幕宽）
     *   154 = 114/719×1280 − 状态栏（Y 轴 → 屏幕高）
     * </pre>
     * 所以映射就是恒等 + 比例缩放。之前"必须交换"的判断是被一次巧合误导的结论，
     * 真正的失败现象是：只收到 ACTION_DOWN、收不到 ACTION_UP —— 因为坐标偏到了窗口外。
     *
     * <p>{@code swapAxes} 保留为开关：个别设备的触点面板方向不同才需要打开。
     */
    private int axisX(int x, int y) {
        int v = swapAxes ? y : x;
        int range = swapAxes ? screenH : screenW;
        return scale(v, range, probe.absMaxX);
    }

    private int axisY(int x, int y) {
        int v = swapAxes ? x : y;
        int range = swapAxes ? screenW : screenH;
        return scale(v, range, probe.absMaxY);
    }

    private static final boolean swapAxes = false;

    private static int scale(int value, int screenRange, int absMax) {
        if (screenRange <= 1) return 0;
        long v = (long) value * absMax / (screenRange - 1);
        if (v < 0) v = 0;
        if (v > absMax) v = absMax;
        return (int) v;
    }

    private static double[] pointOnPath(int[][] pts, double t) {
        int segs = pts.length - 1;
        double seg = Math.min(segs - 1e-9, t * segs);
        int i = (int) seg;
        double local = seg - i;
        double x = pts[i][0] + (pts[i + 1][0] - pts[i][0]) * local;
        double y = pts[i][1] + (pts[i + 1][1] - pts[i][1]) * local;
        return new double[]{x, y};
    }

    private void write(int type, int code, int value) throws Exception {
        buf.clear();
        buf.putLong(0);            // tv_sec：交给内核补时间戳
        buf.putLong(0);            // tv_usec
        buf.putShort((short) type);
        buf.putShort((short) code);
        buf.putInt(value);
        out.write(buf.array());
        out.flush();
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void close() {
        try {
            out.close();
        } catch (Exception ignored) {
        }
    }
}
