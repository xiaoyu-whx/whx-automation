package com.whx.automation;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.whx.automation.core.CoreInfo;

/**
 * 阶段一的主界面：只做"看得见状态"这一件事。
 *
 * 按项目书 §2.2，手机端不提供代码编辑功能；这里只是运行载体的骨架，
 * 后续阶段会替换为任务列表 + 运行状态 + 日志。
 */
public class MainActivity extends Activity {

    private TextView status;
    private Button serviceButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(20));
        root.setBackgroundColor(Color.parseColor("#14161A"));

        TextView title = new TextView(this);
        title.setText("WHX Automation");
        title.setTextSize(24);
        title.setTextColor(Color.parseColor("#E6E8EB"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("阶段一 · 工程基础");
        sub.setTextSize(13);
        sub.setTextColor(Color.parseColor("#8B93A1"));
        sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(Color.parseColor("#9ECE6A"));
        status.setLineSpacing(0, 1.4f);
        status.setText(buildStatusText());
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        serviceButton = new Button(this);
        serviceButton.setText("启动后台服务");
        serviceButton.setOnClickListener(v -> toggleService());
        root.addView(serviceButton);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private String buildStatusText() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        StringBuilder sb = new StringBuilder();
        sb.append("核心版本  : ").append(CoreInfo.VERSION).append('\n');
        sb.append("阶段      : ").append(CoreInfo.STAGE).append('\n');
        sb.append("Android   : ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("机型      : ").append(Build.BRAND).append(' ').append(Build.MODEL).append('\n');
        sb.append("屏幕      : ").append(dm.widthPixels).append('×').append(dm.heightPixels).append('\n');
        sb.append("密度      : ").append(dm.densityDpi).append(" dpi\n");
        sb.append("ABI       : ").append(Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "?").append('\n');
        sb.append('\n');
        sb.append("helper 进程 : 未接入（阶段二）\n");
        sb.append("截图后端    : 未接入（阶段二）\n");
        sb.append("触控后端    : 未接入（阶段四）\n");
        sb.append("脚本引擎    : 未接入（阶段五）\n");
        return sb.toString();
    }

    private void toggleService() {
        Intent intent = new Intent(this, AutomationService.class);
        if (AutomationService.isRunning()) {
            stopService(intent);
            serviceButton.setText("启动后台服务");
            append("后台服务已停止\n");
        } else {
            startForegroundService(intent);
            serviceButton.setText("停止后台服务");
            append("后台服务已启动\n");
        }
    }

    private void append(String line) {
        status.setText(buildStatusText() + "\n" + line);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
