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
    private final java.util.Map<String, String> config;

    public ScriptApi(ScreenSource screen, InputBackend input, ScriptHost host,
                     java.util.Map<String, String> config) {
        this.screen = screen;
        this.input = input;
        this.host = host;
        this.config = config == null ? java.util.Collections.<String, String>emptyMap() : config;
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

    @Override
    public void refreshRegion(double x, double y, double w, double h) {
        host.checkStop();
        int[] region = {(int) x, (int) y, (int) w, (int) h};
        try {
            frame = screen.capture(region);
        } catch (ScreenSource.ScreenException e) {
            throw new IllegalStateException("区域截图失败: " + e.getMessage(), e);
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

    @Override
    public String findImageAssetRegion(String assetPath, double threshold,
                                       double rx, double ry, double rw, double rh) {
        host.checkStop();
        int[] region = {(int) rx, (int) ry, (int) rw, (int) rh};
        try (java.io.InputStream in = host.context().getAssets().open(assetPath)) {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in);
            if (bmp == null) {
                host.log("error", "模板资源不是有效图片: " + assetPath);
                throw new IllegalArgumentException("模板资源不是有效图片: " + assetPath);
            }
            try {
                int w = bmp.getWidth();
                int h = bmp.getHeight();
                int[] pixels = new int[w * h];
                bmp.getPixels(pixels, 0, w, 0, 0, w, h);
                com.whx.automation.core.vision.Hit hit =
                        Vision.findImage(frame(), pixels, w, h, threshold, region);
                return hit == null ? null : hit.center().x + "," + hit.center().y;
            } finally {
                bmp.recycle();
            }
        } catch (java.io.IOException e) {
            host.log("error", "模板资源不存在: " + assetPath);
            throw new IllegalArgumentException("模板资源不存在: " + assetPath, e);
        }
    }

    @Override
    public String findImageFile(String path, double threshold) {
        host.checkStop();
        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(path);
        if (bmp == null) {
            // QuickJS 会把 Java 异常折叠成 "InternalError: Java Exception"，
            // 真正原因先自己打出来，否则用户根本不知道哪里错了
            host.log("error", "模板文件读不出来（路径不对或不是图片）: " + path);
            throw new IllegalArgumentException("模板文件读不出来: " + path);
        }
        try {
            return matchBitmap(bmp, threshold, path);
        } finally {
            bmp.recycle();
        }
    }

    @Override
    public String findImageAsset(String assetPath, double threshold) {
        host.checkStop();
        try (java.io.InputStream in = host.context().getAssets().open(assetPath)) {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in);
            if (bmp == null) {
                throw new IllegalArgumentException("模板资源不是有效图片: " + assetPath);
            }
            try {
                return matchBitmap(bmp, threshold, assetPath);
            } finally {
                bmp.recycle();
            }
        } catch (java.io.IOException e) {
            host.log("error", "模板资源不存在: " + assetPath);
            throw new IllegalArgumentException("模板资源不存在: " + assetPath, e);
        }
    }

    /** 把 Bitmap 转成 ARGB 数组后交给识别引擎。 */
    private String matchBitmap(android.graphics.Bitmap bmp, double threshold, String name) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        com.whx.automation.core.vision.Hit hit =
                Vision.findImage(frame(), pixels, w, h, threshold, null);
        if (hit == null) {
            return null;
        }
        host.log("info", "找图命中 " + name + " 得分 " + String.format(java.util.Locale.US, "%.3f", hit.score)
                + " 位置 " + hit.x + "," + hit.y);
        return hit.center().x + "," + hit.center().y;
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

    // ------------------------------------------------------------ 应用控制
    @Override
    public boolean appInstalled(String pkg) {
        host.checkStop();
        try {
            host.context().getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String launchApp(String pkg) {
        host.checkStop();
        android.content.pm.PackageManager pm = host.context().getPackageManager();
        android.content.Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) {
            host.log("error", "应用不存在或没有启动入口: " + pkg);
            throw new IllegalArgumentException("无法启动应用: " + pkg);
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            host.context().startActivity(intent);
        } catch (Exception e) {
            host.log("error", "启动应用失败: " + pkg + " (" + e.getMessage() + ")");
            throw new IllegalStateException("启动应用失败: " + pkg, e);
        }
        sleep(600);
        return currentPackage();
    }

    @Override
    public String killApp(String pkg) {
        host.checkStop();
        String[] result = shell("am force-stop " + pkg);
        host.log("info", "killApp " + pkg + " exit=" + result[0]);
        return result[0];
    }

    @Override
    public String currentPackage() {
        host.checkStop();
        com.whx.automation.access.WhxAccessibilityService svc =
                com.whx.automation.access.WhxAccessibilityService.get();
        if (svc != null) {
            String pkg = svc.currentPackage();
            if (pkg != null) {
                return pkg;
            }
        }
        // 没有无障碍就退回到 dumpsys（慢但可用）
        String[] result = shell("dumpsys window | grep mCurrentFocus");
        String out = result.length > 1 ? result[1] : "";
        int at = out.indexOf("u0 ");
        if (at >= 0) {
            String rest = out.substring(at + 3).trim();
            int slash = rest.indexOf("/");
            return slash > 0 ? rest.substring(0, slash) : rest;
        }
        return "";
    }

    /** 走 helper 执行 root 命令，返回 {exitCode, 输出}。 */
    private String[] shell(String cmd) {
        try {
            return com.whx.automation.helper.HelperSession.get()
                    .client(host.context()).shell(cmd);
        } catch (Exception e) {
            throw new IllegalStateException("执行命令失败: " + cmd, e);
        }
    }

    // ------------------------------------------------------------ 界面文本
    private com.whx.automation.access.WhxAccessibilityService a11y() {
        com.whx.automation.access.WhxAccessibilityService svc =
                com.whx.automation.access.WhxAccessibilityService.get();
        if (svc == null) {
            host.log("error", "无障碍服务未启用：设置 → 无障碍 → WHX Automation");
            throw new IllegalStateException("无障碍服务未启用");
        }
        return svc;
    }

    @Override
    public boolean uiReady() {
        return com.whx.automation.access.WhxAccessibilityService.isReady();
    }

    @Override
    public String texts() {
        host.checkStop();
        StringBuilder sb = new StringBuilder();
        for (com.whx.automation.access.WhxAccessibilityService.Node n : a11y().nodes()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(n.text).append('|').append(n.x).append(',').append(n.y)
              .append(',').append(n.w).append(',').append(n.h);
        }
        return sb.toString();
    }

    @Override
    public String findText(String text, boolean exact) {
        host.checkStop();
        com.whx.automation.access.WhxAccessibilityService.Node n = a11y().findText(text, exact);
        // 用可点击目标中心：文本本身常常不可点，点它没反应
        return n == null ? null : n.clickX + "," + n.clickY;
    }

    @Override
    public String findTextAll(String text, boolean exact) {
        host.checkStop();
        StringBuilder sb = new StringBuilder();
        for (com.whx.automation.access.WhxAccessibilityService.Node n : a11y().findAllText(text, exact)) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(n.clickX).append(',').append(n.clickY);
        }
        return sb.toString();
    }

    @Override
    public String findId(String viewId) {
        host.checkStop();
        com.whx.automation.access.WhxAccessibilityService.Node n = a11y().findId(viewId);
        return n == null ? null : n.clickX + "," + n.clickY;
    }

    /**
     * 滚动查找：列表类界面一屏放不下，先滑再找。
     *
     * <p>每次向上滑动屏幕高度的 45%（太小容易卡住，太大容易滑过头），
     * 滑完等 300ms 再看节点树 —— 太早读到的还是旧界面。
     */
    @Override
    public String scrollFindText(String text, boolean exact, int maxSwipes) {
        String hit = findText(text, exact);
        if (hit != null) {
            return hit;
        }
        int w = screenWidth();
        int h = screenHeight();
        int x = w / 2;
        for (int i = 0; i < Math.max(0, maxSwipes); i++) {
            host.checkStop();
            try {
                input.swipe(new int[][]{{x, (int) (h * 0.7)}, {x, (int) (h * 0.25)}}, 300);
            } catch (InputBackend.InputException e) {
                throw new IllegalStateException("滚动失败: " + e.getMessage(), e);
            }
            sleep(300);
            hit = findText(text, exact);
            if (hit != null) {
                host.log("info", "滚动 " + (i + 1) + " 次后找到「" + text + "」");
                return hit;
            }
        }
        host.log("warn", "滚动 " + maxSwipes + " 次仍未找到「" + text + "」");
        return null;
    }

    @Override
    public String clickText(String text, boolean exact) {
        String pt = findText(text, exact);
        if (pt == null) {
            host.log("warn", "界面上没有找到文本: " + text);
            return null;
        }
        String[] xy = pt.split(",");
        click(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
        host.log("info", "已点击文本「" + text + "」@ " + pt);
        return pt;
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

    @Override
    public String config(String key) {
        String v = config.get(key);
        if (v == null) {
            // 失败要吵：键拼错不能悄悄返回空串，否则脚本带着错误参数跑偏
            host.log("error", "配置项不存在: " + key + "（可用: " + config.keySet() + "）");
            throw new IllegalArgumentException("配置项不存在: " + key);
        }
        return v;
    }

    @Override
    public boolean hasConfig(String key) {
        return config.containsKey(key);
    }

    /** 环境信息，调试用。 */
    public String info() {
        return "frame=" + (frame == null ? "none" : frame.width + "x" + frame.height)
                + " screen=" + screen.name()
                + " input=" + input.name();
    }
}
