package com.whx.automation.script;

import com.whx.automation.core.Frame;
import com.whx.automation.core.InputBackend;
import com.whx.automation.core.ScreenSource;
import com.whx.automation.core.vision.Color;
import com.whx.automation.core.vision.Point;
import com.whx.automation.core.vision.Vision;

import java.util.List;

/**
 * 暴露给 JavaScript 的同步 API。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>同步</b>：脚本在独立线程上跑，方法直接阻塞到操作完成。实测截图 19.6ms、
 *       点击 32.1ms，同步的体验成本可以接受，换来脚本写法与项目书 §4.5 一致。</li>
 *   <li><b>只收基础类型</b>：参数一律用 double / int / String，不收 JS 对象，
 *       避免 QuickJS ↔ Java 的对象编组开销与类型歧义。</li>
 *   <li><b>帧缓存</b>：`refresh()` 抓一帧后，后续取色/找色都在这帧上做，
 *       同一帧内多次查询结果一致（不会出现"查到的是上一帧"）。</li>
 * </ul>
 */
public final class ScriptApi implements JsApi {

    private final ScreenSource screen;
    private final InputBackend input;
    private final ScriptHost host;

    private Frame frame;

    public ScriptApi(ScreenSource screen, InputBackend input, ScriptHost host) {
        this.screen = screen;
        this.input = input;
        this.host = host;
    }

    // ------------------------------------------------------------------ 帧
    /** 抓一帧（后续识别都基于这一帧）。 */
    public void refresh() {
        host.checkStop();
        try {
            frame = screen.capture(null);
        } catch (ScreenSource.ScreenException e) {
            throw new IllegalStateException("截图失败: " + e.getMessage(), e);
        }
    }

    private Frame frame() {
        if (frame == null) {
            refresh();
        }
        return frame;
    }

    public int screenWidth() {
        return frame().width;
    }

    public int screenHeight() {
        return frame().height;
    }

    /** 当前帧已存在多久（毫秒）—— 太旧应 refresh。 */
    public int frameAge() {
        return frame == null ? -1 : frame.ageMs();
    }

    // ------------------------------------------------------------------ 取色
    /** 取某点颜色，返回 "#RRGGBB"。 */
    public String getColor(double x, double y) {
        host.checkStop();
        return Color.toHex(Vision.colorAt(frame(), (int) x, (int) y));
    }

    /** 颜色判断。 */
    public boolean isColor(double x, double y, String hex, int tolerance) {
        host.checkStop();
        return Vision.isColor(frame(), (int) x, (int) y, Color.parse(hex), tolerance);
    }

    // ------------------------------------------------------------------ 找色
    /** 找色，返回 "x,y"；找不到返回 null。 */
    public String findColor(String hex, int tolerance) {
        host.checkStop();
        Point p = Vision.findColor(frame(), Color.parse(hex), tolerance, null, 1,
                Vision.Order.ROW_FIRST);
        return p == null ? null : p.x + "," + p.y;
    }

    /** 区域找色：返回 "x,y"；找不到返回 null。 */
    public String findColorRegion(String hex, int tolerance, double rx, double ry,
                                  double rw, double rh) {
        host.checkStop();
        int[] region = {(int) rx, (int) ry, (int) rw, (int) rh};
        Point p = Vision.findColor(frame(), Color.parse(hex), tolerance, region, 1,
                Vision.Order.ROW_FIRST);
        return p == null ? null : p.x + "," + p.y;
    }

    /** 找所有该颜色的点，返回 "x1,y1;x2,y2;..."（最多 max 个）。 */
    public String findColorAll(String hex, int tolerance, int max) {
        host.checkStop();
        List<Point> pts = Vision.findColorAll(frame(), Color.parse(hex), tolerance, null, 1, max);
        StringBuilder sb = new StringBuilder();
        for (Point p : pts) {
            if (sb.length() > 0) sb.append(';');
            sb.append(p.x).append(',').append(p.y);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 找图
    /** 找图（模板从 assets 或文件加载见 ScriptHost#loadTemplate）。返回 "x,y" 或 null。 */
    public String findImage(String templateName, double threshold) {
        host.checkStop();
        int[] tpl = host.template(templateName);
        int[] size = host.templateSize(templateName);
        if (tpl == null) {
            throw new IllegalArgumentException("模板不存在: " + templateName);
        }
        com.whx.automation.core.vision.Hit hit =
                Vision.findImage(frame(), tpl, size[0], size[1], threshold, null);
        return hit == null ? null : hit.center().x + "," + hit.center().y;
    }

    // ------------------------------------------------------------------ 触控
    public void click(double x, double y) {
        host.checkStop();
        try {
            // 按住时长是点击延迟的下限：30ms 实测可被系统接受，40ms 更稳
            input.tap((int) x, (int) y, 30);
        } catch (InputBackend.InputException e) {
            throw new IllegalStateException("点击失败: " + e.getMessage(), e);
        }
    }

    public void clickHold(double x, double y, int holdMs) {
        host.checkStop();
        try {
            input.tap((int) x, (int) y, holdMs);
        } catch (InputBackend.InputException e) {
            throw new IllegalStateException("点击失败: " + e.getMessage(), e);
        }
    }

    public void longClick(double x, double y, int durationMs) {
        clickHold(x, y, durationMs);
    }

    public void swipe(double x1, double y1, double x2, double y2, int durationMs) {
        host.checkStop();
        try {
            input.swipe(new int[][]{{(int) x1, (int) y1}, {(int) x2, (int) y2}}, durationMs);
        } catch (InputBackend.InputException e) {
            throw new IllegalStateException("滑动失败: " + e.getMessage(), e);
        }
    }

    public void key(int keyCode) {
        host.checkStop();
        try {
            input.key(keyCode);
        } catch (InputBackend.InputException e) {
            throw new IllegalStateException("按键失败: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 工具
    /** 休眠（可被 stop 打断：每 50ms 检查一次）。 */
    public void sleep(int ms) {
        long deadline = System.currentTimeMillis() + Math.max(0, ms);
        while (System.currentTimeMillis() < deadline) {
            host.checkStop();
            long left = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(50, Math.max(1, left)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void log(String msg) {
        host.log("info", msg);
    }

    public void warn(String msg) {
        host.log("warn", msg);
    }

    /** 环境信息，调试用。 */
    public String info() {
        return "frame=" + (frame == null ? "none" : frame.width + "x" + frame.height)
                + " screen=" + screen.name()
                + " input=" + input.name();
    }
}
