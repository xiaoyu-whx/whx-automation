package com.whx.automation.helper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 触摸设备探测：找出真正的触摸屏节点，并解析轴范围。
 *
 * <p>为什么必须探测而不能写死：
 * <ul>
 *   <li>一台设备上 `getevent` 能看到一堆节点（触摸屏、虚拟按键、传感器、手柄），
 *       必须按 `ABS_MT_POSITION_X` 挑；</li>
 *   <li>节点号重启后会变（event2 → event3）；</li>
 *   <li>轴值不是屏幕像素（常见 0~32767），要按 absmax 做比例映射；</li>
 *   <li>实测雷电 9：轴是**横屏坐标系**（X 最大 1279 = 屏幕高），必须交换。</li>
 * </ul>
 */
public final class InputProbe {

    public final String devicePath;
    public final String deviceName;
    public final int absMaxX;
    public final int absMaxY;
    public final int maxSlot;
    /** 轴是否需要交换（轴X ↔ 屏幕Y）。 */
    public final boolean swapped;

    private InputProbe(String devicePath, String deviceName, int absMaxX, int absMaxY,
                       int maxSlot, boolean swapped) {
        this.devicePath = devicePath;
        this.deviceName = deviceName;
        this.absMaxX = absMaxX;
        this.absMaxY = absMaxY;
        this.maxSlot = maxSlot;
        this.swapped = swapped;
    }

    /**
     * 探测。
     *
     * @param screenW 屏幕宽（像素），用于判定轴是否需要交换
     * @param screenH 屏幕高（像素）
     */
    public static InputProbe detect(int screenW, int screenH) throws Exception {
        String out = exec("getevent -pl");
        if (out == null || out.isEmpty()) {
            throw new IllegalStateException("getevent -pl 无输出");
        }
        return parseGetevent(out);
    }

    /**
     * 解析 {@code getevent -pl} 的输出（抽成静态方法便于单元测试）。
     *
     * <p>设备差异全在这一层：不同机型设备数、顺序、是否带 ABS_MT_SLOT 都不一样，
     * 用真实输出样本做回归比在设备上试错划算得多。
     */
    public static InputProbe parseGetevent(String out) {
        if (out == null || out.isEmpty()) {
            throw new IllegalStateException("getevent -pl 无输出");
        }

        String current = null;
        String name = "";
        int maxX = -1;
        int maxY = -1;
        int maxSlot = 0;

        int foundX = -1;
        int foundY = -1;
        int foundSlot = 0;
        String foundName = null;
        String foundPath = null;

        Pattern devPat = Pattern.compile("add device \\d+: (/dev/input/event\\d+)");
        for (String line : out.split("\n")) {
            Matcher m = devPat.matcher(line);
            if (m.find()) {
                current = m.group(1);
                name = "";
                maxX = -1;
                maxY = -1;
                maxSlot = 0;
                continue;
            }
            String t = line.trim();
            if (t.startsWith("name:")) {
                name = t.substring(5).trim().replace("\"", "");
            } else if (t.contains("ABS_MT_POSITION_X")) {
                maxX = parseMax(t);
            } else if (t.contains("ABS_MT_POSITION_Y")) {
                maxY = parseMax(t);
            } else if (t.contains("ABS_MT_SLOT")) {
                maxSlot = parseMax(t);
            }

            if (current != null && maxX > 0 && maxY > 0) {
                foundPath = current;
                foundName = name;
                foundX = maxX;
                foundY = maxY;
                foundSlot = maxSlot;
                break;   // 第一个具备多点轴的设备就是触摸屏
            }
        }

        if (foundPath == null) {
            throw new IllegalStateException("找不到带 ABS_MT_POSITION_X/Y 的输入设备");
        }

        // 注：不能靠 absmax 与屏幕尺寸的比例去猜"轴是否交换" —— 实测证明框架是
        // 每轴各自按 absMax 缩放到屏幕对应维度，与面板物理方向无关。
        // 这里把"是否交换"留给运行时开关（默认不交换），见 Injector.axisX 的注释。
        return new InputProbe(foundPath, foundName, foundX, foundY, foundSlot, false);
    }

    private static int parseMax(String line) {
        // 形如： ABS_MT_POSITION_X : value 0, min 0, max 1279, fuzz 0, ...
        Matcher m = Pattern.compile("max\\s+(-?\\d+)").matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String exec(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        p.waitFor();
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("%s (%s) absX=%d absY=%d slot=%d swapped=%s",
                devicePath, deviceName, absMaxX, absMaxY, maxSlot, swapped);
    }
}
