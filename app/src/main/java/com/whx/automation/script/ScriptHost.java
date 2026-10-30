package com.whx.automation.script;

import android.util.Log;

import com.whx.automation.core.InputBackend;
import com.whx.automation.core.ScreenSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.cash.quickjs.QuickJs;

/**
 * 脚本宿主：在独立线程上跑 QuickJS，绑定同步 API，管理停止与日志。
 *
 * <p>线程模型：QuickJS 实例非线程安全，**创建、执行、关闭都在同一条脚本线程上**；
 * 停止通过协作式标志实现（每个 API 调用点检查一次），不跨线程 close。
 *
 * <p>为什么同步：脚本本身就该串行操作设备；实测单次操作 20~35ms，阻塞代价可接受。
 */
public final class ScriptHost {

    private static final String TAG = "WHX/Script";

    /** 把 api 的方法包装成全局函数，脚本里可以直接 log('x') / click(1,2)。 */
    private static final String BOOTSTRAP = String.join(System.lineSeparator(),
            "function refresh(){return api.refresh()}",
            "function screenWidth(){return api.screenWidth()}",
            "function screenHeight(){return api.screenHeight()}",
            "function frameAge(){return api.frameAge()}",
            "function getColor(x,y){return api.getColor(x,y)}",
            "function isColor(x,y,hex,tol){return api.isColor(x,y,hex,tol)}",
            "function findColor(hex,tol){return api.findColor(hex,tol)}",
            "function findColorRegion(hex,tol,x,y,w,h){return api.findColorRegion(hex,tol,x,y,w,h)}",
            "function findColorAll(hex,tol,max){return api.findColorAll(hex,tol,max)}",
            "function findImage(name,th){return api.findImage(name,th)}",
            "function click(x,y){return api.click(x,y)}",
            "function clickHold(x,y,ms){return api.clickHold(x,y,ms)}",
            "function longClick(x,y,ms){return api.longClick(x,y,ms)}",
            "function swipe(x1,y1,x2,y2,ms){return api.swipe(x1,y1,x2,y2,ms)}",
            "function key(code){return api.key(code)}",
            "function sleep(ms){return api.sleep(ms)}",
            "function log(m){return api.log(m)}",
            "function warn(m){return api.warn(m)}",
            "function info(){return api.info()}",
            "function config(k){return api.config(k)}",
            "function hasConfig(k){return api.hasConfig(k)}");

    /** 脚本结束回调。 */
    public interface Listener {
        void onLog(String level, String message);

        void onDone(boolean ok, String error, long costMs);
    }

    private final ScreenSource screen;
    private final InputBackend input;
    private final Map<String, int[]> templates = new HashMap<>();
    private final Map<String, int[]> templateSizes = new HashMap<>();

    private volatile boolean stopRequested;
    private volatile Thread thread;
    private volatile boolean running;
    private Listener listener;
    private java.util.Map<String, String> config =
            java.util.Collections.<String, String>emptyMap();

    public ScriptHost(ScreenSource screen, InputBackend input) {
        this.screen = screen;
        this.input = input;
    }

    public boolean isRunning() {
        return running;
    }

    /** 注册模板（阶段六会改成从脚本包 assets 加载）。 */
    public void putTemplate(String name, int[] pixels, int w, int h) {
        templates.put(name, pixels);
        templateSizes.put(name, new int[]{w, h});
    }

    int[] template(String name) {
        return templates.get(name);
    }

    int[] templateSize(String name) {
        return templateSizes.get(name);
    }

    void log(String level, String msg) {
        Log.i(TAG, "[" + level + "] " + msg);
        Listener l = listener;
        if (l != null) {
            l.onLog(level, msg);
        }
    }

    void checkStop() {
        if (stopRequested) {
            throw new ScriptStopped();
        }
    }

    /** 启动脚本。重复调用会被拒绝（同一时刻只跑一个脚本）。 */
    public synchronized boolean run(String script, String name, Listener listener) {
        return run(script, name, listener, java.util.Collections.<String, String>emptyMap());
    }

    /** 带配置运行（小精灵把用户填的参数传进来）。 */
    public synchronized boolean run(String script, String name, Listener listener,
                                    java.util.Map<String, String> config) {
        if (running) {
            return false;
        }
        this.listener = listener;
        this.config = config == null ? java.util.Collections.<String, String>emptyMap() : config;
        this.stopRequested = false;
        this.running = true;

        Thread t = new Thread(() -> execute(script, name), "script-" + name);
        t.setDaemon(true);
        thread = t;
        t.start();
        return true;
    }

    /** 请求停止：置标志，脚本会在下一次 API 调用处退出。 */
    public void stop() {
        stopRequested = true;
    }

    private void execute(String script, String name) {
        QuickJs js = null;
        long t0 = System.currentTimeMillis();
        String error = null;
        try {
            js = QuickJs.create();
            ScriptApi api = new ScriptApi(screen, input, this, config);
            // QuickJS 只能绑定接口，用动态代理转发到实现类
            js.set("api", JsApi.class, api);
            // 把 api 的常用方法提到全局，脚本里直接写 click(1,2) 而不是 api.click(1,2)
            // 生成全局包装函数：必须通过 api.xxx(...) 调用，直接把方法取出来会丢代理
            js.evaluate(BOOTSTRAP);
            Object result = js.evaluate(script);
            log("info", "脚本返回: " + (result == null ? "null" : result.toString()));
        } catch (ScriptStopped stopped) {
            error = "已停止";
            log("warn", error);
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "脚本异常", t);
            log("error", error);
        } finally {
            if (js != null) {
                try {
                    js.close();
                } catch (Throwable ignored) {
                }
            }
            running = false;
            thread = null;
            long cost = System.currentTimeMillis() - t0;
            Listener l = listener;
            if (l != null) {
                l.onDone(error == null, error, cost);
            }
        }
    }

    /** 脚本被停止时抛出，用于跳出执行。 */
    public static final class ScriptStopped extends RuntimeException {
        ScriptStopped() {
            super("脚本已停止");
        }
    }

    // ---------------------------------------------------------------- 模板工具
    /** 把一段像素登记成模板（阶段三的裁图能力复用）。 */
    public void registerTemplateFromFrame(com.whx.automation.core.Frame frame,
                                         String name, int x, int y, int w, int h) {
        int[] px = com.whx.automation.core.vision.Vision.crop(frame, x, y, w, h);
        putTemplate(name, px, w, h);
    }

    /** 已注册模板名列表。 */
    public List<String> templateNames() {
        return new ArrayList<>(templates.keySet());
    }
}
