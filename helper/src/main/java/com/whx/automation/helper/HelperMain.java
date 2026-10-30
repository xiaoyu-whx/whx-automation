package com.whx.automation.helper;

import android.os.Build;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Root Helper：设备端唯一持有 root 能力的进程。
 *
 * <p>启动方式（由 App 通过 su 拉起，CLASSPATH 直接指向 APK，无需额外 dex）：
 * <pre>
 *   su -c "CLASSPATH=<base.apk> app_process / com.whx.automation.helper.HelperMain &lt;port&gt;"
 * </pre>
 *
 * <p>职责边界：只做截图 / 注入 / shell，不认识脚本与视觉。见 docs/architecture.md §1.2。
 */
public final class HelperMain {

    public static final String VERSION = "0.2.0";

    private static volatile boolean running = true;
    private static Injector injector;

    public static void main(String[] args) {
        int port = 0;
        try {
            port = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        } catch (NumberFormatException ignored) {
        }
        if (port <= 0) {
            System.out.println("ERR 缺少端口参数");
            System.exit(2);
        }

        System.out.println("WHX Helper " + VERSION + " uid=" + android.os.Process.myUid()
                + " sdk=" + Build.VERSION.SDK_INT + " port=" + port
                + " capture=" + Capture.apiName());
        System.out.flush();

        if (android.os.Process.myUid() != 0) {
            System.out.println("ERR 需要 root（当前 uid=" + android.os.Process.myUid() + "）");
            System.exit(3);
        }
        if (!Capture.available()) {
            System.out.println("ERR 本机没有可用的截图接口: " + Capture.apiName());
            System.exit(4);
        }

        try (ServerSocket server = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))) {
            server.setReuseAddress(true);
            // 客户端（App）连上之前最多等 60 秒：App 若在连接前就退了，
            // 本进程不能一直占着堆留着（设备上曾积到 200 个 root 进程）
            server.setSoTimeout(60000);
            while (running) {
                Socket socket;
                try {
                    socket = server.accept();
                } catch (java.net.SocketTimeoutException timeout) {
                    if (running) {
                        System.out.println("WARN 60 秒内没有客户端连接，退出");
                    }
                    break;
                }
                try {
                    socket.setTcpNoDelay(true);
                    serve(socket);
                } catch (IOException e) {
                    log("连接异常: " + e);
                } finally {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
                // 单客户端设计：HelperSession 每次会话都会重新拉起 helper，
                // 连接一断就退出，否则进程会一直挂着（实测会积出上百个）
                System.out.println("客户端已断开，helper 退出");
                break;
            }
        } catch (IOException e) {
            System.out.println("ERR 监听失败: " + e);
            System.exit(5);
        }
    }

    private static void serve(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1 << 16));

        while (running) {
            int len;
            try {
                len = in.readInt();
            } catch (EOFException eof) {
                return;
            }
            if (len <= 0 || len > (8 << 20)) {
                return;
            }
            byte[] payload = new byte[len];
            in.readFully(payload);

            byte op = payload[0];
            ByteBuffer args = ByteBuffer.wrap(payload, 1, len - 1);

            switch (op) {
                case Proto.OP_PING:
                    writeFrame(out, Proto.ERR_NONE, ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array());
                    break;

                case Proto.OP_INFO:
                    writeFrame(out, Proto.ERR_NONE, info().getBytes("UTF-8"));
                    break;

                case Proto.OP_CAPTURE:
                    handleCapture(out, args);
                    break;

                case Proto.OP_TAP:
                    handleTap(out, args);
                    break;

                case Proto.OP_SWIPE:
                    handleSwipe(out, args);
                    break;

                case Proto.OP_KEY:
                    handleKey(out, args);
                    break;

                case Proto.OP_SHELL:
                    handleShell(out, args);
                    break;

                case Proto.OP_SHUTDOWN:
                    writeFrame(out, Proto.ERR_NONE, new byte[]{1});
                    running = false;
                    return;

                default:
                    writeFrame(out, Proto.ERR_UNSUPPORTED, ("不支持的操作: " + op).getBytes("UTF-8"));
                    break;
            }
        }
    }

    /**
     * 以 root 身份执行一条命令（脚本用它做 kill 应用、改设置等系统操作）。
     *
     * <p>helper 本身已经是 uid=0，这里直接用 Runtime.exec 即可；
     * 返回 "exitCode|输出" 让调用方能区分"命令跑成功"和"命令执行失败"。
     */
    private static void handleShell(DataOutputStream out, ByteBuffer args) throws IOException {
        byte[] raw = new byte[args.remaining()];
        args.get(raw);
        String cmd = new String(raw, "UTF-8");
        int code = -1;
        String output = "";
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            readFully(proc.getInputStream(), bos);
            readFully(proc.getErrorStream(), bos);
            code = proc.waitFor();
            output = new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            output = "执行异常: " + e;
        }
        String result = code + "|" + output;
        writeFrame(out, Proto.ERR_NONE, result.getBytes("UTF-8"));
    }

    private static void readFully(java.io.InputStream in, java.io.ByteArrayOutputStream out)
            throws IOException {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static void handleCapture(DataOutputStream out, ByteBuffer args) throws IOException {
        int w = args.getInt();
        int h = args.getInt();
        int rx = args.getInt();
        int ry = args.getInt();
        int rw = args.getInt();
        int rh = args.getInt();
        int[] region = (rw > 0 && rh > 0) ? new int[]{rx, ry, rw, rh} : null;

        Capture.Shot shot;
        try {
            shot = Capture.shot(w, h, region);
        } catch (Throwable t) {
            writeFrame(out, Proto.ERR_IO, ("截图失败: " + t).getBytes("UTF-8"));
            return;
        }

        // 响应：status(1) + w(4) + h(4) + hardware(1) + 三段耗时(8×3) + 像素(w*h*4)
        int headerLen = 1 + 4 + 4 + 1 + 24;
        int pixelBytes = shot.argb.length * 4;

        ByteBuffer header = ByteBuffer.allocate(headerLen);
        header.put(Proto.ERR_NONE);
        header.putInt(shot.width);
        header.putInt(shot.height);
        header.put((byte) (shot.hardwareBitmap ? 1 : 0));
        header.putDouble(shot.composeMs);
        header.putDouble(shot.readbackMs);
        header.putDouble(shot.copyMs);

        ByteBuffer pixels = ByteBuffer.allocate(pixelBytes);
        pixels.asIntBuffer().put(shot.argb);   // 大端，与 App 侧 DataInputStream 一致

        byte[] payload = new byte[headerLen + pixelBytes];
        System.arraycopy(header.array(), 0, payload, 0, headerLen);
        System.arraycopy(pixels.array(), 0, payload, headerLen, pixelBytes);

        writeFrame(out, payload);
    }

    private static void handleTap(DataOutputStream out, ByteBuffer a) throws IOException {
        int x = a.getInt();
        int y = a.getInt();
        int hold = a.getInt();
        int sw = a.getInt();
        int sh = a.getInt();
        long t0 = System.nanoTime();
        try {
            injector(sw, sh).tap(x, y, hold);
        } catch (Throwable t) {
            writeFrame(out, Proto.ERR_IO, ("点击失败: " + t).getBytes("UTF-8"));
            return;
        }
        replyMs(out, t0);
    }

    private static void handleSwipe(DataOutputStream out, ByteBuffer a) throws IOException {
        int n = a.getInt();
        if (n < 2 || n > 64) {
            writeFrame(out, Proto.ERR_BAD_REQUEST, "点数非法".getBytes("UTF-8"));
            return;
        }
        int[][] pts = new int[n][2];
        for (int i = 0; i < n; i++) {
            pts[i][0] = a.getInt();
            pts[i][1] = a.getInt();
        }
        int duration = a.getInt();
        int sw = a.getInt();
        int sh = a.getInt();
        long t0 = System.nanoTime();
        try {
            injector(sw, sh).swipe(pts, duration);
        } catch (Throwable t) {
            writeFrame(out, Proto.ERR_IO, ("滑动失败: " + t).getBytes("UTF-8"));
            return;
        }
        replyMs(out, t0);
    }

    private static void handleKey(DataOutputStream out, ByteBuffer a) throws IOException {
        int code = a.getInt();
        long t0 = System.nanoTime();
        try {
            ensureInjector();
            injector.key(code);
        } catch (Throwable t) {
            writeFrame(out, Proto.ERR_IO, ("按键失败: " + t).getBytes("UTF-8"));
            return;
        }
        replyMs(out, t0);
    }

    private static void replyMs(DataOutputStream out, long t0) throws IOException {
        double ms = (System.nanoTime() - t0) / 1e6;
        writeFrame(out, Proto.ERR_NONE, ByteBuffer.allocate(8).putDouble(ms).array());
    }

    /** 按屏幕尺寸惰性创建注入器；尺寸变化（旋转/改分辨率）时重建。 */
    private static synchronized Injector injector(int screenW, int screenH) throws Exception {
        if (injector != null
                && injector.probe() != null
                && sameScreen(screenW, screenH)) {
            return injector;
        }
        if (injector != null) {
            injector.close();
            injector = null;
        }
        InputProbe probe = InputProbe.detect(screenW, screenH);
        log("input probe: " + probe);
        injector = new Injector(probe, screenW, screenH);
        lastScreenW = screenW;
        lastScreenH = screenH;
        return injector;
    }

    private static void ensureInjector() throws Exception {
        if (injector == null) {
            throw new IllegalStateException("尚未初始化（先发一次 tap 带上屏幕尺寸）");
        }
    }

    private static int lastScreenW;
    private static int lastScreenH;

    private static boolean sameScreen(int w, int h) {
        return w == lastScreenW && h == lastScreenH;
    }

    private static String info() {
        return "name=whx-helper"
                + " version=" + VERSION
                + " uid=" + android.os.Process.myUid()
                + " sdk=" + Build.VERSION.SDK_INT
                + " release=" + Build.VERSION.RELEASE
                + " abi=" + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                + " capture=" + Capture.apiName()
                + " input=" + (injector != null ? injector.probe().devicePath : "unprobed");
    }

    /** 整帧写出（长度前缀 + 负载）。 */
    private static void writeFrame(DataOutputStream out, byte status, byte[] data) throws IOException {
        byte[] payload = new byte[1 + data.length];
        payload[0] = status;
        System.arraycopy(data, 0, payload, 1, data.length);
        writeFrame(out, payload);
    }

    private static void writeFrame(DataOutputStream out, byte[] payload) throws IOException {
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    private static void log(String msg) {
        System.out.println(msg);
        System.out.flush();
    }

    private HelperMain() {
    }
}
