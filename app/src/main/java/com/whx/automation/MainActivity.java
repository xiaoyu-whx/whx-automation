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
import com.whx.automation.core.FrameDiff;
import com.whx.automation.input.HelperInput;
import com.whx.automation.script.ScriptHost;
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
    private TextView logView;
    private Button serviceButton;
    private ScriptHost scriptHost;
    private final java.util.ArrayDeque<String> scriptLog = new java.util.ArrayDeque<>();
    private HelperScreenSource source;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        source = new HelperScreenSource(this);
        scriptHost = new ScriptHost(source, new HelperInput(this));

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

        serviceButton = button("启动服务", v -> toggleService());
        buttons.addView(serviceButton, weight());
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

        LinearLayout buttons3 = new LinearLayout(this);
        buttons3.setOrientation(LinearLayout.HORIZONTAL);
        buttons3.setPadding(0, 0, 0, dp(8));
        buttons3.addView(button("点击自检", v -> runTapSelfTest()), weight());
        buttons3.addView(button("连点基准", v -> runTapBench()), weight());
        buttons3.addView(button("滑动", v -> runSwipeTest()), weight());
        root.addView(buttons3);

        LinearLayout buttons4 = new LinearLayout(this);
        buttons4.setOrientation(LinearLayout.HORIZONTAL);
        buttons4.setPadding(0, 0, 0, dp(8));
        buttons4.addView(button("运行脚本", v -> runDemoScript()), weight());
        buttons4.addView(button("长脚本", v -> runLongScript()), weight());
        buttons4.addView(button("停止脚本", v -> {
            scriptHost.stop();
            appendScriptLog("[stop] 已请求停止");
        }), weight());
        root.addView(buttons4);

        // 日志区固定放在按钮下方：之前的写法把日志写进顶部的 status，
        // 长脚本运行时把按钮一路顶出屏幕，"停止脚本"直接点不到。
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextColor(Color.parseColor("#9ECE6A"));
        logView.setPadding(0, dp(8), 0, dp(8));
        logView.setBackgroundColor(Color.parseColor("#0D0F12"));
        root.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f));

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setBackgroundColor(Color.parseColor("#0D0F12"));
        root.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f));

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
                    setOutput(sb);
                    preview.setImageBitmap(toBitmap(last));
                });
            } catch (Exception e) {
                ui.post(() -> setOutput("[区域截图失败] " + e));
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

    // ------------------------------------------------------------ 触控自检
    /** 点击自检：点自己的「启动服务」按钮，用"屏幕是否变化 + 服务状态"双重验证。 */
    private void runTapSelfTest() {
        new Thread(() -> {
            String LS = System.lineSeparator();
            StringBuilder sb = new StringBuilder("点击自检").append(LS);
            try {
                HelperInput input = new HelperInput(this);
                Frame before = source.capture(null);

                int[] xy = centerOf(serviceButton);
                long t0 = System.nanoTime();
                input.tap(xy[0], xy[1], 40);
                double tapMs = (System.nanoTime() - t0) / 1e6;

                sleepMs(600);
                Frame after = source.capture(null);
                FrameDiff.Result diff = FrameDiff.compare(before, after);
                boolean running = AutomationService.isRunning();

                sb.append(String.format("  点击 (%d,%d) 耗时 %.1f ms%n", xy[0], xy[1], tapMs));
                sb.append(String.format("  屏幕变化 %s%n", diff));
                sb.append(String.format("  服务状态 = %s  点击生效 = %s%n",
                        running ? "运行中" : "已停止", running));

                // 还原
                input.tap(xy[0], xy[1], 40);
                sleepMs(400);
                ui.post(() -> status.setText(buildStatusText() + LS + LS + sb));
            } catch (Exception e) {
                Log.e(TAG, "点击自检失败", e);
                ui.post(() -> status.setText(buildStatusText() + LS + LS + "[点击自检失败] " + e));
            }
        }, "tap-test").start();
    }

    /** 连点基准：在空白处连点 10 次，测单次点击延迟与吞吐。 */
    private void runTapBench() {
        new Thread(() -> {
            String LS = System.lineSeparator();
            StringBuilder sb = new StringBuilder("连点基准（10 次，间隔 50ms）").append(LS);
            try {
                HelperInput input = new HelperInput(this);
                double[] ms = new double[10];
                long start = System.nanoTime();
                for (int i = 0; i < ms.length; i++) {
                    long t0 = System.nanoTime();
                    input.tap(360, 300, 30);
                    ms[i] = (System.nanoTime() - t0) / 1e6;
                    sleepMs(50);
                }
                double total = (System.nanoTime() - start) / 1e6;
                Arrays.sort(ms);
                sb.append(String.format("  单次 P50 %.1f ms  P99 %.1f ms  min %.1f%n",
                        ms[5], ms[9], ms[0]));
                sb.append(String.format("  10 次总耗时 %.0f ms（%.1f 次/秒）%n",
                        total, ms.length * 1000.0 / total));
                ui.post(() -> status.setText(buildStatusText() + LS + LS + sb));
            } catch (Exception e) {
                Log.e(TAG, "连点基准失败", e);
                ui.post(() -> status.setText(buildStatusText() + LS + LS + "[连点基准失败] " + e));
            }
        }, "tap-bench").start();
    }

    /** 滑动：在列表中上下滑，报告耗时与屏幕变化。 */
    private void runSwipeTest() {
        new Thread(() -> {
            String LS = System.lineSeparator();
            StringBuilder sb = new StringBuilder("滑动测试").append(LS);
            try {
                HelperInput input = new HelperInput(this);
                Frame before = source.capture(null);

                int[][] path = {{360, 900}, {360, 600}, {360, 300}};
                long t0 = System.nanoTime();
                input.swipe(path, 300);
                double swipeMs = (System.nanoTime() - t0) / 1e6;

                sleepMs(500);
                Frame after = source.capture(null);
                FrameDiff.Result diff = FrameDiff.compare(before, after);

                sb.append(String.format("  滑动 (360,900)→(360,300) 用时 %.0f ms%n", swipeMs));
                sb.append(String.format("  屏幕变化 %s%n", diff));
                ui.post(() -> status.setText(buildStatusText() + LS + LS + sb));
            } catch (Exception e) {
                Log.e(TAG, "滑动测试失败", e);
                ui.post(() -> status.setText(buildStatusText() + LS + LS + "[滑动测试失败] " + e));
            }
        }, "swipe-test").start();
    }

    private int[] centerOf(android.view.View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return new int[]{loc[0] + v.getWidth() / 2, loc[1] + v.getHeight() / 2};
    }

    private static void sleepMs(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ 脚本执行
    /** 示例脚本：验证桥接、截图、取色、找色、点击、休眠全链路。 */
    private static final String DEMO_SCRIPT = String.join(System.lineSeparator(),
            "log('脚本开始');",
            "refresh();",
            "var w = screenWidth(), h = screenHeight();",
            "log('屏幕 ' + w + 'x' + h);",
            "var t0 = Date.now();",
            "for (var i = 0; i < 1000; i++) { getColor(360, 640); }",
            "log('1000 次 getColor 桥接耗时 ' + (Date.now() - t0) + ' ms');",
            "var c = getColor(360, 640);",
            "log('中心像素 ' + c);",
            "var pt = findColor(c, 6);",
            "log('找色结果 ' + pt);",
            "var xy = pt.split(',');",
            "click(Number(xy[0]), Number(xy[1]));",
            "sleep(100);",
            "log('点击完成');",
            "'返回值: ' + info()");

    /** 长脚本：用于验证"运行中可停止"。 */
    private static final String LONG_SCRIPT = String.join(System.lineSeparator(),
            "refresh();",
            "log('长脚本开始');",
            "for (var i = 0; i < 100; i++) {",
            "  sleep(100);",
            "  if (i % 10 === 0) { log('进度 ' + i + '/100'); }",
            "}",
            "log('长脚本结束');",
            "'ok'");

    private void runLongScript() {
        scriptLog.clear();
        appendScriptLog("[run] 启动长脚本（10 秒）");
        scriptHost.run(LONG_SCRIPT, "long", new ScriptHost.Listener() {
            @Override
            public void onLog(String level, String message) {
                ui.post(() -> appendScriptLog("[" + level + "] " + message));
            }

            @Override
            public void onDone(boolean ok, String error, long costMs) {
                ui.post(() -> appendScriptLog(String.format("[done] %s  用时 %d ms",
                        ok ? "成功" : ("失败: " + error), costMs)));
            }
        });
    }

    private void runDemoScript() {
        scriptLog.clear();
        appendScriptLog("[run] 启动脚本");
        boolean started = scriptHost.run(DEMO_SCRIPT, "demo", new ScriptHost.Listener() {
            @Override
            public void onLog(String level, String message) {
                ui.post(() -> appendScriptLog("[" + level + "] " + message));
            }

            @Override
            public void onDone(boolean ok, String error, long costMs) {
                ui.post(() -> appendScriptLog(String.format("[done] %s  用时 %d ms",
                        ok ? "成功" : ("失败: " + error), costMs)));
            }
        });
        if (!started) {
            appendScriptLog("[warn] 已有脚本在运行");
        }
    }

    /**
     * 追加脚本日志。
     *
     * <p>只保留最近 8 行：日志区不能无限长，否则按钮会被顶出屏幕（实测长脚本时
     * "停止脚本"按钮直接滚没了，等于跑起来就停不掉）。
     */
    /** 把测试/脚本输出显示到固定日志区（不动顶部状态行，按钮位置就不会跳）。 */
    private void setOutput(CharSequence text) {
        logView.setText(text);
    }

    private void appendScriptLog(String line) {
        scriptLog.addLast(line);
        while (scriptLog.size() > 8) {
            scriptLog.removeFirst();
        }
        StringBuilder sb = new StringBuilder();
        for (String l : scriptLog) {
            sb.append(l).append(System.lineSeparator());
        }
        setOutput(sb);
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
