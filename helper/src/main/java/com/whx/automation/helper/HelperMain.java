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
            while (running) {
                Socket socket = server.accept();
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

    private static String info() {
        return "name=whx-helper"
                + " version=" + VERSION
                + " uid=" + android.os.Process.myUid()
                + " sdk=" + Build.VERSION.SDK_INT
                + " release=" + Build.VERSION.RELEASE
                + " abi=" + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                + " capture=" + Capture.apiName();
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
