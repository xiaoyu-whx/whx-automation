package com.whx.automation;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import com.whx.automation.core.CoreInfo;
import com.whx.automation.helper.HelperSession;
import com.whx.automation.script.ScriptConfig;
import com.whx.automation.script.ScriptStore;
import com.whx.automation.script.TaskManager;

import java.io.InputStream;
import java.util.List;

/**
 * 阶段六主界面：脚本列表 + 运行/停止 + 实时日志 + 状态面板。
 *
 * <p>按项目书 §2.2，设备端只负责**导入、选择、运行、看日志**，不提供代码编辑。
 */
public class MainActivity extends Activity implements TaskManager.Observer {

    private static final int REQ_PICK_SCRIPT = 1001;
    /** PC 工具链用：指定要跑哪个脚本。 */
    public static final String EXTRA_SCRIPT = "script";
    /** PC 工具链用：启动后立即运行。 */
    public static final String EXTRA_AUTORUN = "run";
    private static final String TAG = "WHX/Main";
    private static final int LOG_LINES = 14;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.ArrayDeque<String> logLines = new java.util.ArrayDeque<>();

    private ScriptStore store;
    private ScriptStore.Bundle bundle;
    private ScriptConfig scriptConfig;
    private final java.util.LinkedHashMap<String, android.view.View> configInputs =
            new java.util.LinkedHashMap<>();
    private TaskManager tasks;

    private TextView status;
    private TextView logView;
    private LinearLayout listBox;
    private TextView listTitle;
    private LinearLayout configBox;
    private Button runButton;
    private Button stopButton;
    private String selected = "";
    /** 待运行请求：由 PC 工具链的 intent 设置，在 onResume 里真正执行。
     *  放在 onResume 而不是 onCreate/onNewIntent，是因为热启动时 onNewIntent
     *  实测会静默不执行；onResume 一定会被调用，行为稳定。 */
    private String pendingScript;
    private boolean pendingAutoRun;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new ScriptStore(this);
        bundle = store.bundle();
        tasks = TaskManager.get(this);
        tasks.addObserver(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(22), dp(16), dp(12));
        root.setBackgroundColor(Color.parseColor("#14161A"));

        TextView title = new TextView(this);
        title.setText(bundle != null ? bundle.appName : "WHX Automation");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#E6E8EB"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(bundle != null ? "小精灵模式 · 绑定脚本 " + bundle.scriptName
                : "阶段六 · APK 整合");
        sub.setTextSize(12);
        sub.setTextColor(Color.parseColor("#8B93A1"));
        root.addView(sub);

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(Color.parseColor("#9ECE6A"));
        status.setLineSpacing(0, 1.3f);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        listTitle = new TextView(this);
        listTitle.setText("脚本");
        listTitle.setTextColor(Color.parseColor("#C8CDD6"));
        listTitle.setTextSize(13);
        root.addView(listTitle);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        // 小精灵的配置项：打包者声明参数，这里自动生成输入框，脚本用 config('键') 读
        configBox = new LinearLayout(this);
        configBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(configBox);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, dp(8));
        runButton = button("运行", v -> runSelected());
        stopButton = button("停止", v -> tasks.stop());
        actions.addView(runButton, weight());
        actions.addView(stopButton, weight());
        actions.addView(button("导入", v -> pickScript()), weight());
        actions.addView(button("刷新", v -> {
            refreshScripts();
            refreshStatus();
        }), weight());
        root.addView(actions);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(Color.parseColor("#9ECE6A"));
        logView.setBackgroundColor(Color.parseColor("#0D0F12"));
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 直接用 LinearLayout：ScrollView 会让子视图的 layout_weight 失效，
        // 日志区会塌成一条缝（实测）。
        setContentView(root);

        if (bundle != null) {
            selected = bundle.scriptName;
            scriptConfig = ScriptConfig.load(this);
            buildConfigUi();
            com.whx.automation.access.SpriteIcon.activate(this, bundle.icon);
            listBox.setVisibility(android.view.View.GONE);
            listTitle.setVisibility(android.view.View.GONE);
        } else {
            refreshScripts();
        }
        tasks.addObserver(this);

        handleIntentExtras(getIntent());

        if (bundle != null && bundle.autoStart) {
            // 小精灵：打开即跑
            ui.postDelayed(this::runSelected, 600);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentExtras(intent);
    }

    /**
     * 处理 PC 工具链传入的参数：{@code --es script xxx.js --ez run true}。
     *
     * <p>这样 `whx run demo.js` 就能"推脚本 → 选中 → 立即运行"，不需要在手机上点。
     */
    private void handleIntentExtras(Intent intent) {
        if (intent == null) {
            return;
        }
        String name = intent.getStringExtra(EXTRA_SCRIPT);
        Log.i(TAG, "handleIntentExtras: script=" + name
                + " autorun=" + intent.getBooleanExtra(EXTRA_AUTORUN, false));
        if (name == null || name.isEmpty()) {
            return;
        }
        if (bundle != null) {
            appendLog("[warn] 小精灵模式不接受外部脚本参数: " + name);
            return;
        }
        selected = name;
        refreshScripts();
        appendLog("[intent] 指定脚本 " + name);
        pendingScript = name;
        pendingAutoRun = intent.getBooleanExtra(EXTRA_AUTORUN, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingScript != null && pendingAutoRun) {
            boolean run = pendingAutoRun;
            pendingScript = null;
            pendingAutoRun = false;
            if (run) {
                ui.postDelayed(this::runSelected, 500);
            }
        }
        new Thread(() -> {
            // 首次会触发一次 su 授权（已授权则瞬间完成），让"root 状态"如实反映
            try {
                HelperSession.get().ensureClient(this);
            } catch (Exception e) {
                Log.w(TAG, "helper 未就绪: " + e.getMessage());
            }
            ui.post(this::refreshStatus);
        }, "probe").start();
    }

    @Override
    protected void onDestroy() {
        tasks.removeObserver(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 脚本
    private void refreshScripts() {
        listBox.removeAllViews();
        List<ScriptStore.Script> scripts = store.list();
        if (scripts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有脚本（assets/scripts 为空）");
            empty.setTextColor(Color.parseColor("#8B93A1"));
            listBox.addView(empty);
            return;
        }
        if (selected.isEmpty()) {
            selected = scripts.get(0).name;
        }
        // 内置与导入可能有同名脚本：只给第一个匹配的加选中标记，
        // 否则列表里会出现两个 ▶（实测），运行逻辑取的也是第一个，保持一致。
        boolean marked = false;
        for (ScriptStore.Script s : scripts) {
            boolean isSelected = !marked && s.name.equals(selected);
            if (isSelected) {
                marked = true;
            }
            Button b = new Button(this);
            b.setText((isSelected ? "▶ " : "   ") + s);
            b.setTextSize(13);
            b.setAllCaps(false);
            b.setOnClickListener(v -> {
                selected = s.name;
                refreshScripts();
            });
            listBox.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** 依据 assets/bundle/config.txt 生成输入框。 */
    private void buildConfigUi() {
        configInputs.clear();
        configBox.removeAllViews();
        if (scriptConfig == null || scriptConfig.isEmpty()) {
            return;
        }
        TextView title = new TextView(this);
        title.setText("参数");
        title.setTextColor(Color.parseColor("#C8CDD6"));
        title.setTextSize(13);
        configBox.addView(title);

        for (ScriptConfig.Field f : scriptConfig.fields()) {
            TextView label = new TextView(this);
            label.setText(f.label);
            label.setTextColor(Color.parseColor("#9AA3B0"));
            label.setTextSize(12);
            configBox.addView(label);

            if (f.type == ScriptConfig.Type.SWITCH) {
                Switch sw = new Switch(this);
                // 用当前值（可能来自上次保存），不是 config.txt 的默认值
                sw.setChecked("true".equalsIgnoreCase(scriptConfig.get(f.key)));
                configInputs.put(f.key, sw);
                configBox.addView(sw);
            } else {
                EditText et = new EditText(this);
                et.setText(scriptConfig.get(f.key));   // 同上：优先显示上次保存的值
                et.setTextSize(13);
                et.setSingleLine(true);
                if (f.type == ScriptConfig.Type.NUMBER) {
                    et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                            | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                            | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
                }
                configInputs.put(f.key, et);
                configBox.addView(et);
            }
        }
    }

    /** 把界面上的输入收集进配置对象；返回错误信息或 null。 */
    private String collectConfig() {
        for (java.util.Map.Entry<String, android.view.View> e : configInputs.entrySet()) {
            android.view.View v = e.getValue();
            if (v instanceof EditText) {
                scriptConfig.set(e.getKey(), ((EditText) v).getText().toString());
            } else if (v instanceof Switch) {
                scriptConfig.set(e.getKey(), ((Switch) v).isChecked() ? "true" : "false");
            }
        }
        return scriptConfig.validate();
    }

    private void runSelected() {
        if (selected.isEmpty()) {
            appendLog("[warn] 没有选中脚本");
            return;
        }
        if (tasks.state() != TaskManager.State.IDLE) {
            Log.w(TAG, "拒绝运行：当前状态 " + tasks.state());
            appendLog("[warn] 已有任务在运行（" + tasks.state() + "）");
            return;
        }
        try {
            String code;
            String name;
            if (bundle != null) {
                name = bundle.scriptName;
                code = store.loadBundleScript(name);
            } else {
                ScriptStore.Script target = null;
                for (ScriptStore.Script s : store.list()) {
                    if (s.name.equals(selected)) {
                        target = s;
                        break;
                    }
                }
                if (target == null) {
                    Log.w(TAG, "脚本不存在: " + selected);
                    appendLog("[error] 脚本不存在: " + selected);
                    return;
                }
                name = target.name;
                code = store.load(target);
            }
            // 前台服务：保证切后台/锁屏后任务继续
            Intent svc = new Intent(this, AutomationService.class);
            startForegroundService(svc);
            java.util.Map<String, String> cfg = null;
            if (scriptConfig != null && !scriptConfig.isEmpty()) {
                String invalid = collectConfig();
                if (invalid != null) {
                    Log.w(TAG, "配置不合法: " + invalid);
                    appendLog("[error] " + invalid);
                    return;
                }
                scriptConfig.save();     // 记住这次的参数，下次打开沿用
                cfg = scriptConfig.snapshot();
                Log.i(TAG, "配置: " + cfg);
            }
            int retries = 0;
            if (cfg != null && cfg.containsKey("retry")) {
                try {
                    retries = Integer.parseInt(cfg.get("retry").trim());
                } catch (NumberFormatException ignored) {
                }
            }
            boolean started = tasks.start(name, code, cfg, retries);
            appendLog(started ? "[run] 启动 " + name : "[warn] 启动失败");
            refreshStatus();
        } catch (Exception e) {
            Log.e(TAG, "读取脚本失败", e);
            appendLog("[error] 读取脚本失败: " + e);
        }
    }

    private void pickScript() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_PICK_SCRIPT);
        } catch (Exception e) {
            appendLog("[error] 无法打开文件选择器: " + e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_SCRIPT || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            String name = uri.getLastPathSegment();
            java.io.File saved = store.importFrom(in, name == null ? "imported.js" : name);
            selected = saved.getName();
            appendLog("[import] 已导入 " + saved.getName() + "（" + saved.length() + " 字节）");
            refreshScripts();
        } catch (Exception e) {
            Log.e(TAG, "导入失败", e);
            appendLog("[error] 导入失败: " + e);
        }
    }

    // ------------------------------------------------------------------ 状态
    private void refreshStatus() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        StringBuilder sb = new StringBuilder();
        sb.append("核心 ").append(CoreInfo.VERSION).append(" · ").append(CoreInfo.STAGE)
          .append(System.lineSeparator());
        sb.append("Android ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")  ")
          .append(dm.widthPixels).append("×").append(dm.heightPixels)
          .append("  ").append(Build.SUPPORTED_ABIS[0]).append(System.lineSeparator());
        sb.append("root   : ").append(HelperSession.get().isAlive() ? "已就绪(uid=0)" : "未就绪")
          .append(System.lineSeparator());
        sb.append("后端   : 截图 helper/surfacecontrol · 触控 helper/evdev")
          .append(System.lineSeparator());
        sb.append("任务   : ").append(stateText());
        status.setText(sb);

        boolean idle = tasks.state() == TaskManager.State.IDLE;
        runButton.setEnabled(idle);
        stopButton.setEnabled(!idle);
    }

    private String stateText() {
        switch (tasks.state()) {
            case RUNNING:
                return "运行中 · " + tasks.scriptName() + " · " + (tasks.elapsedMs() / 1000) + "s";
            case STOPPING:
                return "停止中 · " + tasks.scriptName();
            default:
                return "空闲";
        }
    }

    // ------------------------------------------------------- TaskManager 回调
    @Override
    public void onTaskLog(String level, String message) {
        ui.post(() -> appendLog("[" + level + "] " + message));
    }

    @Override
    public void onTaskStateChanged(TaskManager.State state, String scriptName) {
        ui.post(this::refreshStatus);
    }

    @Override
    public void onTaskDone(boolean ok, String error, long costMs) {
        ui.post(() -> {
            appendLog(String.format("[done] %s  用时 %d ms",
                    ok ? "成功" : ("失败: " + error), costMs));
            refreshStatus();
            if (tasks.state() == TaskManager.State.IDLE) {
                stopService(new Intent(this, AutomationService.class));
            }
        });
    }

    // ------------------------------------------------------------------ 工具
    private void appendLog(String line) {
        logLines.addLast(line);
        while (logLines.size() > LOG_LINES) {
            logLines.removeFirst();
        }
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) {
            sb.append(l).append(System.lineSeparator());
        }
        logView.setText(sb);
    }

    private Button button(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
