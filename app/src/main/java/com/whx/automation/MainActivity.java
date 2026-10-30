package com.whx.automation;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.whx.automation.capture.HelperScreenSource;
import com.whx.automation.core.CoreInfo;
import com.whx.automation.core.Frame;
import com.whx.automation.core.ScreenSource;
import com.whx.automation.helper.HelperClient;
import com.whx.automation.helper.HelperSession;

import java.util.Arrays;

/**
 * 阶段二的主界面：验证截图链路（helper 进程 → SurfaceControl → 像素回传）。
 *
 * <p>按项目书 §2.2，手机端不提供代码编辑；这里只是运行载体与自检面板。
 */
public class MainActivity extends Activity {

    private static final String TAG = "WHX/Main";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private ImageView preview;
    private HelperScreenSource source;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        source = new HelperScreenSource(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));
        root.setBackgroundColor(Color.parseColor("#14161A"));

        TextView title = new TextView(this);
        title.setText("WHX Automation");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#E6E8EB"));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("阶段二 · 截图引擎");
        sub.setTextSize(12);
        sub.setTextColor(Color.parseColor("#8B93A1"));
        sub.setPadding(0, dp(2), 0, dp(12));
        root.addView(sub);

        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(Color.parseColor("#9ECE6A"));
        status.setLineSpacing(0, 1.35f);
        status.setText(buildStatusText());
        root.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(10), 0, dp(10));

        buttons.addView(button("启动服务", v -> toggleService()), weight());
        buttons.addView(button("截图一次", v -> runCapture(1)), weight());
        buttons.addView(button("基准 100", v -> runCapture(100)), weight());
        buttons.addView(button("区域", v -> runRegion()), weight());
        root.addView(buttons);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setBackgroundColor(Color.parseColor("#0D0F12"));
        root.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 探活必须离开 UI 线程
        new Thread(() -> {
            HelperSession.get().probe();
            ui.post(() -> status.setText(buildStatusText()));
        }, "probe").start();
    }

    @Override
    protected void onDestroy() {
        HelperSession.get().stop();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 动作
    private void runCapture(int rounds) {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                HelperSession session = HelperSession.get();
                long t0 = System.nanoTime();
                HelperClient client = session.ensureClient(this);
                long startupMs = (System.nanoTime() - t0) / 1_000_000;
                sb.append(String.format("helper 启动+连接: %d ms%n", startupMs));
                sb.append("helper: ").append(session.info()).append('\n');
                sb.append(String.format("首次 ping: %.2f ms%n", client.pingMs()));

                Frame frame = null;
                double[] totals = new double[rounds];
                double[] helpers = new double[rounds];
                double[] transfers = new double[rounds];

                for (int i = 0; i < rounds; i++) {
                    long s0 = System.nanoTime();
                    frame = source.capture(null);
                    totals[i] = (System.nanoTime() - s0) / 1e6;
                    HelperClient.Shot shot = source.lastShot();
                    if (shot != null) {
                        helpers[i] = shot.helperMs();
                        transfers[i] = shot.transferMs;
                    }
                }

                HelperClient.Shot shot = source.lastShot();
                sb.append('\n');
                if (shot != null) {
                    sb.append(String.format("帧: %d×%d  hw位图=%s%n",
                            shot.width, shot.height, shot.hardwareBitmap));
                    sb.append(String.format("  SurfaceFlinger 合成 : %.1f ms%n", shot.composeMs));
                    sb.append(String.format("  硬件位图回读        : %.1f ms%n", shot.readbackMs));
                    sb.append(String.format("  getPixels 拷贝      : %.1f ms%n", shot.copyMs));
                    sb.append(String.format("  像素传输(LocalTCP)  : %.1f ms  (%.1f MB)%n",
                            shot.transferMs, shot.width * shot.height * 4 / 1024.0 / 1024.0));
                }
                if (rounds > 1) {
                    sb.append('\n').append(String.format("连续 %d 次:%n", rounds));
                    sb.append(String.format("  总耗时  P50 %.1f  P99 %.1f ms%n",
                            pct(totals, 50), pct(totals, 99)));
                    sb.append(String.format("  helper  P50 %.1f  P99 %.1f ms%n",
                            pct(helpers, 50), pct(helpers, 99)));
                    sb.append(String.format("  传输    P50 %.1f  P99 %.1f ms%n",
                            pct(transfers, 50), pct(transfers, 99)));
                }
                sb.append('\n').append("已接后端: ").append(source.name())
                        .append("  可用=").append(source.available());

                final Frame last = frame;
                ui.post(() -> {
                    status.setText(buildStatusText() + "\n" + sb);
                    if (last != null) {
                        preview.setImageBitmap(toBitmap(last));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "截图失败", e);
                ui.post(() -> status.setText(buildStatusText() + "\n\n[失败] " + e));
            }
        }, "capture-test").start();
    }

    /** 区域截图：验证裁剪在 helper 侧完成、传输量随之下降。 */
    private void runRegion() {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder("区域截图 [100,200,360,320]" + System.lineSeparator());
            try {
                int[] region = {100, 200, 360, 320};
                double[] ms = new double[20];
                Frame frame = null;
                for (int i = 0; i < ms.length; i++) {
                    long t0 = System.nanoTime();
                    frame = source.capture(region);
                    ms[i] = (System.nanoTime() - t0) / 1e6;
                }
                Arrays.sort(ms);
                HelperClient.Shot shot = source.lastShot();
                sb.append(String.format("  尺寸 %d×%d（应为 360×320）%n", frame.width, frame.height));
                sb.append(String.format("  P50 %.1f ms  P99 %.1f ms%n", ms[10], ms[19]));
                if (shot != null) {
                    sb.append(String.format("  传输 %.1f ms（%.2f MB）%n",
                            shot.transferMs, frame.width * frame.height * 4 / 1024.0 / 1024.0));
                }
                sb.append(String.format("  首像素 ARGB=%08X%n", frame.pixels[0]));

                final Frame last = frame;
                ui.post(() -> {
                    status.setText(buildStatusText() + System.lineSeparator() + System.lineSeparator() + sb);
                    preview.setImageBitmap(toBitmap(last));
                });
            } catch (Exception e) {
                ui.post(() -> status.setText(buildStatusText() + System.lineSeparator() + System.lineSeparator() + "[区域截图失败] " + e));
            }
        }, "region-test").start();
    }

    private void toggleService() {
        Intent intent = new Intent(this, AutomationService.class);
        if (AutomationService.isRunning()) {
            stopService(intent);
        } else {
            startForegroundService(intent);
        }
    }

    // ------------------------------------------------------------------ 工具
    private static Bitmap toBitmap(Frame f) {
        return Bitmap.createBitmap(f.pixels, f.width, f.height, Bitmap.Config.ARGB_8888);
    }

    private static double pct(double[] values, int p) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        int idx = (int) Math.min(copy.length - 1, Math.round((p / 100.0) * (copy.length - 1)));
        return copy[idx];
    }

    private Button button(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private String buildStatusText() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        StringBuilder sb = new StringBuilder();
        sb.append("核心 ").append(CoreInfo.VERSION).append("  ·  ").append(CoreInfo.STAGE).append('\n');
        sb.append("Android ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")  ")
          .append(dm.widthPixels).append('×').append(dm.heightPixels).append('\n');
        sb.append("helper : ").append(HelperSession.get().isAlive() ? "在线" : "未启动").append('\n');
        sb.append("服务   : ").append(AutomationService.isRunning() ? "运行中" : "已停止");
        return sb.toString();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
