package com.whx.automation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.whx.automation.script.ScriptStore;
import com.whx.automation.script.TaskManager;

/**
 * PC 工具链的运行入口：`am broadcast -a …RUN_SCRIPT --es script x.js`。
 *
 * <p>为什么不用 Activity 的 intent：实测当界面不在前台/已在栈顶时，
 * `am start --es script … ` 只是"投递意图"，`onNewIntent` 可能在很晚才被调用，
 * 甚至根本收不到 —— 表现为**脚本没跑、也没有任何日志**。
 * 广播不依赖界面生命周期，投递即执行，稳定得多。
 *
 * <p>脚本执行本身也不需要界面：任务由 TaskManager 在后台跑，前台服务负责保活，
 * 界面只是"看状态"的窗口。
 */
public class RunScriptReceiver extends BroadcastReceiver {

    private static final String TAG = "WHX/RunScript";
    public static final String ACTION_RUN = "com.whx.automation.RUN_SCRIPT";
    public static final String EXTRA_SCRIPT = "script";
    /** 可选：运行时配置，格式 "key=value,key2=value2"（PC 调试用）。 */
    public static final String EXTRA_CONFIG = "config";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_RUN.equals(intent.getAction())) {
            return;
        }
        String script = intent.getStringExtra(EXTRA_SCRIPT);
        if (script == null || script.isEmpty()) {
            Log.w(TAG, "缺少 script 参数");
            return;
        }
        Context app = context.getApplicationContext();
        ScriptStore store = new ScriptStore(app);
        java.util.Map<String, String> config = parseConfig(intent.getStringExtra(EXTRA_CONFIG));
        try {
            app.startForegroundService(new Intent(app, AutomationService.class));

            if (script.contains(",")) {
                // 逗号分隔 = 任务链：顺序执行，前一个结束再跑下一个
                java.util.List<String[]> items = new java.util.ArrayList<>();
                for (String name : script.split(",")) {
                    String trimmed = name.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    items.add(new String[]{trimmed, loadCode(store, trimmed)});
                }
                boolean started = TaskManager.get(app).startChain(items);
                Log.i(TAG, "请求运行任务链（" + items.size() + " 个）→ "
                        + (started ? "已启动" : "被拒绝（已有任务）"));
            } else {
                String code = loadCode(store, script);
                boolean started = TaskManager.get(app).start(script, code, config);
                Log.i(TAG, "请求运行 " + script + " → " + (started ? "已启动" : "被拒绝（已有任务）"));
            }
        } catch (Exception e) {
            // 失败要吵：脚本不存在等错误必须落到日志，不能让调用方以为跑起来了
            Log.e(TAG, "运行失败: " + script, e);
        }
    }

    /**
     * 取脚本源码：小精灵只有绑定脚本（在 assets/bundle 里），没有导入目录，
     * 所以先看是不是绑定脚本，否则按导入脚本处理。
     */
    private static String loadCode(ScriptStore store, String name) throws java.io.IOException {
        ScriptStore.Bundle bundle = store.bundle();
        if (bundle != null && bundle.scriptName.equals(name)) {
            return store.loadBundleScript(name);
        }
        return store.load(new ScriptStore.Script(name, ScriptStore.Source.IMPORTED, 0));
    }

    /** "x=100,y=640" → map。格式错误直接忽略该项，不阻断运行。 */
    static java.util.Map<String, String> parseConfig(String raw) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return map;
        }
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            map.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return map;
    }
}
