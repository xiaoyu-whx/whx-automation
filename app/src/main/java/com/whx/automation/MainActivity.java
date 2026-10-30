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
import com.whx.automation.core.vision.Hit;
import com.whx.automation.core.vision.Point;
import com.whx.automation.core.vision.Vision;
import com.whx.automation.core.Frame;
import com.whx.automation.core.ScreenSource;
import com.whx.automation.helper.HelperClient;
import com.whx.automation.helper.HelperSession;

import java.util.Arrays;
import java.util.List;

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

        LinearLayout buttons2 = new LinearLayout(this);
        buttons2.setOrientation(LinearLayout.HORIZONTAL);
        buttons2.setPadding(0, 0, 0, dp(8));
        buttons2.addView(button("识别自检", v -> runVisionSelfTest()), weight());
        buttons2.addView(button("找图基准", v -> runImageBench()), weight());
        root.addView(buttons2);

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

    // ------------------------------------------------------------ 识别自检
    /**
     * 自检：取色 → 判断 → 找色 → 多点找色 → 找图，全部在同一帧上完成。
     * 找图的模板直接从这一帧裁出来，所以"能不能找回原位"是可判定的。
     */
    private void runVisionSelfTest() {
        new Thread(() -> {
            String LS = System.lineSeparator();
            StringBuilder sb = new StringBuilder("识别自检").append(LS);
            try {
                Frame f = source.capture(null);

                // 1) 取色 / 判断
                int c = Vision.colorAt(f, 360, 640);
                sb.append(String.format("  取色(360,640) = %s  判断= %s%n",
                        com.whx.automation.core.vision.Color.toHex(c), Vision.isColor(f, 360, 640, c, 0)));

                // 2) 取色吞吐
                long t0 = System.nanoTime();
                int n = 100_000;
                int sink = 0;
                for (int i = 0; i < n; i++) {
                    sink += Vision.colorAt(f, i % f.width, (i / f.width) % f.height);
                }
                double perCall = (System.nanoTime() - t0) / 1e6 / n;
                sb.append(String.format("  取色 %d 次：%.4f ms/次（sink=%d）%n", n, perCall, sink & 1));

                // 3) 找色（全屏 + 20 次）
                double[] find = new double[20];
                Point p = null;
                for (int i = 0; i < find.length; i++) {
                    long s0 = System.nanoTime();
                    p = Vision.findColor(f, c, 6, null, 1, Vision.Order.ROW_FIRST);
                    find[i] = (System.nanoTime() - s0) / 1e6;
                }
                Arrays.sort(find);
                List<Point> all = Vision.findColorAll(f, c, 6, null, 1, 20);
                sb.append(String.format("  找色(首命中) P50 %.2f ms  P99 %.2f ms  命中=%s  同色点=%d%n",
                        find[10], find[19], p, all.size()));

                // 最坏情况：颜色不存在 → 必须扫完整屏
                double[] miss = new double[10];
                for (int i = 0; i < miss.length; i++) {
                    long s0 = System.nanoTime();
                    Vision.findColor(f, 0xFF00FF00, 0, null, 1, Vision.Order.ROW_FIRST);
                    miss[i] = (System.nanoTime() - s0) / 1e6;
                }
                Arrays.sort(miss);
                sb.append(String.format("  找色(全屏扫完) P50 %.2f ms  P99 %.2f ms%n", miss[5], miss[9]));

                // 4) 多点找色（用真实像素构造多点特征）
                int ax = 40, ay = 40;
                int anchor = Vision.colorAt(f, ax, ay);
                int[][] offs = {
                        {200, 0, Vision.colorAt(f, ax + 200, ay)},
                        {0, 300, Vision.colorAt(f, ax, ay + 300)},
                };
                long m0 = System.nanoTime();
                Point mp = Vision.findMultiColor(f, anchor, offs, 6, null, 1);
                double multiMs = (System.nanoTime() - m0) / 1e6;
                sb.append(String.format("  多点找色 %.2f ms  命中=%s%n", multiMs, mp));

                // 5) 找图：从本帧裁模板，应当找回原位置
                int tw = 200, th = 150;
                int[] box = bestTemplateRegion(f, tw, th);
                int tx = box[0], ty = box[1];
                int[] tpl = Vision.crop(f, tx, ty, tw, th);
                double[] img = new double[8];
                Hit hit = null;
                for (int i = 0; i < img.length; i++) {
                    long s0 = System.nanoTime();
                    hit = Vision.findImage(f, tpl, tw, th, 0.0, null);
                    img[i] = (System.nanoTime() - s0) / 1e6;
                }
                Arrays.sort(img);
                boolean exact = hit != null && hit.x == tx && hit.y == ty;
                sb.append(String.format("  找图 P50 %.1f ms  P99 %.1f ms%n", img[4], img[7]));
                sb.append(String.format("  命中 %s  期望(%d,%d)  位置正确=%s%n",
                        hit, tx, ty, exact));
                if (hit != null && !exact) {
                    sb.append(String.format("  偏差 dx=%d dy=%d%n",
                            hit.x - tx, hit.y - ty));
                }

                ui.post(() -> status.setText(buildStatusText() + LS + LS + sb));
            } catch (Exception e) {
                Log.e(TAG, "识别自检失败", e);
                ui.post(() -> status.setText(buildStatusText() + LS + LS + "[识别自检失败] " + e));
            }
        }, "vision-test").start();
    }

    /** 找图性能基准：不同模板尺寸下各跑 10 次。 */
    private void runImageBench() {
        new Thread(() -> {
            String LS = System.lineSeparator();
            StringBuilder sb = new StringBuilder("找图基准（全屏 720×1280）").append(LS);
            try {
                Frame f = source.capture(null);
                int[][] sizes = {{40, 40}, {80, 60}, {160, 120}, {320, 240}};
                for (int[] s : sizes) {
                    int tw = s[0];
                    int th = s[1];
                    int[] box = bestTemplateRegion(f, tw, th);
                    int tx = box[0], ty = box[1];
                    int[] tpl = Vision.crop(f, tx, ty, tw, th);
                    double[] ms = new double[10];
                    Hit hit = null;
                    for (int i = 0; i < ms.length; i++) {
                        long t0 = System.nanoTime();
                        hit = Vision.findImage(f, tpl, tw, th, 0.8, null);
                        ms[i] = (System.nanoTime() - t0) / 1e6;
                    }
                    Arrays.sort(ms);
                    boolean ok = hit != null && hit.x == tx && hit.y == ty;
                    sb.append(String.format("  %3d×%3d  P50 %6.1f ms  P99 %6.1f ms  %s%s%n",
                            tw, th, ms[5], ms[9], ok ? "位置正确 " : "位置错误 ", hit));
                }
                ui.post(() -> status.setText(buildStatusText() + LS + LS + sb));
            } catch (Exception e) {
                Log.e(TAG, "找图基准失败", e);
                ui.post(() -> status.setText(buildStatusText() + LS + LS + "[找图基准失败] " + e));
            }
        }, "image-bench").start();
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
    /**
     * 在帧上按网格采样，挑出灰度方差最大的窗口当模板。
     * 纯色块（方差近 0）无法匹配，所以自检必须主动找有纹理的区域。
     */
    private static int[] bestTemplateRegion(Frame f, int tw, int th) {
        int bestX = 0;
        int bestY = 0;
        double bestVar = -1;
        int stepX = Math.max(1, (f.width - tw) / 6);
        int stepY = Math.max(1, (f.height - th) / 8);
        for (int y = 0; y + th <= f.height; y += stepY) {
            for (int x = 0; x + tw <= f.width; x += stepX) {
                int[] px = Vision.crop(f, x, y, tw, th);
                long sum = 0;
                long sq = 0;
                for (int v : px) {
                    int g = (((v >> 16) & 0xFF) * 77 + ((v >> 8) & 0xFF) * 150 + (v & 0xFF) * 29) >> 8;
                    sum += g;
                    sq += (long) g * g;
                }
                double mean = (double) sum / px.length;
                double var = (double) sq / px.length - mean * mean;
                if (var > bestVar) {
                    bestVar = var;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return new int[]{bestX, bestY};
    }

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
