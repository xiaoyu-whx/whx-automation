package com.whx.automation.helper;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helper 进程的启动、连接与自愈。
 *
 * <p>拉起方式（实测可行）：helper 的类就在本 APK 的 dex 里，直接把 CLASSPATH 指向
 * base.apk 即可，不需要额外打包一份 dex：
 * <pre>
 *   su -c "CLASSPATH=<base.apk> app_process / com.whx.automation.helper.HelperMain <port>"
 * </pre>
 *
 * <p>端口用内核分配的临时端口（127.0.0.1），只在本机可达。
 */
public final class HelperSession {

    private static final String TAG = "WHX/Helper";
    private static final HelperSession INSTANCE = new HelperSession();

    private final AtomicBoolean starting = new AtomicBoolean(false);
    private Process process;
    private HelperClient client;
    private int port = -1;
    private String info;
    /** 缓存状态：UI 线程只读它，绝不在这里发网络请求（会触发 NetworkOnMainThreadException）。 */
    private volatile boolean alive = false;

    public static HelperSession get() {
        return INSTANCE;
    }

    /** 只读缓存状态，可在任意线程（含 UI 线程）调用。 */
    public boolean isAlive() {
        return alive;
    }

    /** 真正探活（会发网络请求），只能在后台线程调用。 */
    public synchronized boolean probe() {
        if (client == null) {
            alive = false;
            return false;
        }
        try {
            client.pingMs();
            alive = true;
            return true;
        } catch (IOException e) {
            alive = false;
            client = null;
            return false;
        }
    }

    public synchronized String info() {
        return info;
    }

    public synchronized int port() {
        return port;
    }

    /** 确保 helper 可用；失败抛 IOException（不降级、不静默）。 */
    /** 取可用的 client（必要时拉起 helper）。 */
    public synchronized HelperClient client(android.content.Context context) throws IOException {
        return ensureClient(context);
    }

    public synchronized HelperClient ensureClient(Context context) throws IOException {
        if (client != null) {
            try {
                client.pingMs();
                return client;
            } catch (IOException e) {
                Log.w(TAG, "helper 无响应，准备重启: " + e.getMessage());
                client = null;
            }
        }
        if (!starting.compareAndSet(false, true)) {
            throw new IOException("helper 正在启动中");
        }
        try {
            stopLocked();
            launch(context);
            client = connect();
            info = client.info();
            alive = true;
            Log.i(TAG, "helper 就绪: " + info);
            return client;
        } finally {
            starting.set(false);
        }
    }

    private void launch(Context context) throws IOException {
        port = findFreePort();
        String apk = context.getPackageCodePath();
        String cmd = "CLASSPATH=" + apk + " app_process / "
                + "com.whx.automation.helper.HelperMain " + port;
        Log.i(TAG, "拉起 helper: " + cmd);
        ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
        pb.redirectErrorStream(true);
        process = pb.start();

        // 持续读 helper 的 stdout/stderr，避免管道写满把它卡死
        final Process p = process;
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Log.i(TAG + "/out", line);
                }
            } catch (IOException ignored) {
            }
        }, "helper-drain");
        drain.setDaemon(true);
        drain.start();
    }

    private HelperClient connect() throws IOException {
        IOException last = null;
        // 30 秒：首次启动时 su 会弹授权框，必须给用户留出点「允许」的时间
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            Process p = process;
            if (p != null && !p.isAlive()) {
                // 进程都没了，再等端口就是白等：直接把可行动的原因抛出去
                int code = -1;
                try {
                    code = p.exitValue();
                } catch (IllegalThreadStateException ignored) {
                }
                stopLocked();
                throw new IOException("helper 进程已退出（exit=" + code
                        + "）：多半是 root 未授权或 su 拒绝了本次请求。"
                        + "请在授权弹窗里选择「永久记住」并点「允许」，或先跑一次"
                        + " python tools/whx.py run app/src/main/assets/scripts/grant_root.js");
            }
            try {
                return new HelperClient("127.0.0.1", port, 800);
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        final int failedPort = port;
        stopLocked();
        throw new IOException("连接 helper 失败（端口 " + failedPort + "）: "
                + (last == null ? "超时（可能卡在 root 授权弹窗）" : last.getMessage()));
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    public synchronized void stop() {
        stopLocked();
    }

    private void stopLocked() {
        if (client != null) {
            try {
                client.close();
            } catch (Throwable ignored) {
            }
            client = null;
        }
        if (process != null) {
            process.destroy();
            process = null;
        }
        port = -1;
        info = null;
        alive = false;
    }

    private HelperSession() {
    }
}
