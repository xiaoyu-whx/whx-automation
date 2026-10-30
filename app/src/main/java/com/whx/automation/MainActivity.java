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
import android.widget.ScrollView;
import android.widget.TextView;

import com.whx.automation.core.CoreInfo;
import com.whx.automation.helper.HelperSession;
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
    private static final String TAG = "WHX/Main";
    private static final int LOG_LINES = 14;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.ArrayDeque<String> logLines = new java.util.ArrayDeque<>();

    private ScriptStore store;
    private TaskManager tasks;

    private TextView status;
    private TextView logView;
    private LinearLayout listBox;
    private Button runButton;
    private Button stopButton;
    private String selected = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new ScriptStore(this);
        tasks = TaskManager.get(this);
        tasks.addObserver(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(22), dp(16), dp(12));
        root.setBackgroundColor(Color.parseColor("#14161A"));

        TextView title = new TextView(this);
        title.setText("WHX Automation");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#E6E8EB"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("阶段六 · APK 整合");
        sub.setTextSize(12);
        sub.setTextColor(Color.parseColor("#8B93A1"));
        root.addView(sub);

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(Color.parseColor("#9ECE6A"));
        status.setLineSpacing(0, 1.3f);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        TextView listTitle = new TextView(this);
        listTitle.setText("脚本");
        listTitle.setTextColor(Color.parseColor("#C8CDD6"));
        listTitle.setTextSize(13);
        root.addView(listTitle);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

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

        refreshScripts();
        TaskManager.get(this).addObserver(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        for (ScriptStore.Script s : scripts) {
            Button b = new Button(this);
            b.setText((s.name.equals(selected) ? "▶ " : "   ") + s);
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

    private void runSelected() {
        if (selected.isEmpty()) {
            appendLog("[warn] 没有选中脚本");
            return;
        }
        if (tasks.state() != TaskManager.State.IDLE) {
            appendLog("[warn] 已有任务在运行");
            return;
        }
        ScriptStore.Script target = null;
        for (ScriptStore.Script s : store.list()) {
            if (s.name.equals(selected)) {
                target = s;
                break;
            }
        }
        if (target == null) {
            appendLog("[error] 脚本不存在: " + selected);
            return;
        }
        try {
            String code = store.load(target);
            // 前台服务：保证切后台/锁屏后任务继续
            Intent svc = new Intent(this, AutomationService.class);
            startForegroundService(svc);
            boolean started = tasks.start(target.name, code);
            appendLog(started ? "[run] 启动 " + target.name : "[warn] 启动失败");
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
