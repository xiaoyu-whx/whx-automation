package com.whx.automation.helper;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Helper 客户端：与 root 进程通信（协议见 {@link Proto}）。
 *
 * <p>调用是同步的 —— 调用方（脚本线程 / 截图线程）本来就要等结果，
 * 且单次截图仅十几毫秒，异步化只会增加复杂度。
 */
public final class HelperClient implements Closeable {

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    public HelperClient(String host, int port, int connectTimeoutMs) throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(20000);
        in = new DataInputStream(new java.io.BufferedInputStream(socket.getInputStream(), 1 << 16));
        out = new DataOutputStream(new java.io.BufferedOutputStream(socket.getOutputStream(), 1 << 16));
    }

    /** 握手：返回 helper 自述信息。 */
    public String info() throws IOException {
        send(Proto.OP_INFO);
        byte[] data = readResponse();
        return new String(data, "UTF-8");
    }

    /** 往返延迟（毫秒）。 */
    public double pingMs() throws IOException {
        long t0 = System.nanoTime();
        send(Proto.OP_PING);
        readResponse();
        return (System.nanoTime() - t0) / 1e6;
    }

    /**
     * 抓一帧。
     *
     * @param region {@code [x,y,w,h]}，null 表示整屏
     */
    public Shot capture(int width, int height, int[] region) throws IOException {
        long t0 = System.nanoTime();
        ByteBuffer req = ByteBuffer.allocate(1 + 24);
        req.put(Proto.OP_CAPTURE);
        req.putInt(width);
        req.putInt(height);
        req.putInt(region != null ? region[0] : 0);
        req.putInt(region != null ? region[1] : 0);
        req.putInt(region != null ? region[2] : 0);
        req.putInt(region != null ? region[3] : 0);
        write(req.array());

        long t1 = System.nanoTime();
        byte[] resp = readFrame();
        long t2 = System.nanoTime();

        checkStatus(resp);
        ByteBuffer buf = ByteBuffer.wrap(resp, 1, resp.length - 1);
        int w = buf.getInt();
        int h = buf.getInt();
        boolean hardware = buf.get() != 0;
        double compose = buf.getDouble();
        double readback = buf.getDouble();
        double copy = buf.getDouble();

        int count = w * h;
        int[] argb = new int[count];
        buf.asIntBuffer().get(argb);

        return new Shot(w, h, argb, hardware, compose, readback, copy,
                (t1 - t0) / 1e6, (t2 - t1) / 1e6);
    }

    /** 点击 / 长按。返回 helper 侧耗时（毫秒）。 */
    public double tap(int x, int y, int holdMs, int screenW, int screenH) throws IOException {
        ByteBuffer req = ByteBuffer.allocate(1 + 20);
        req.put(Proto.OP_TAP);
        req.putInt(x);
        req.putInt(y);
        req.putInt(holdMs);
        req.putInt(screenW);
        req.putInt(screenH);
        return readMs(sendAndRead(req.array()));
    }

    /** 滑动。points 为屏幕坐标序列。 */
    public double swipe(int[][] points, int durationMs, int screenW, int screenH) throws IOException {
        ByteBuffer req = ByteBuffer.allocate(1 + 4 + points.length * 8 + 12);
        req.put(Proto.OP_SWIPE);
        req.putInt(points.length);
        for (int[] p : points) {
            req.putInt(p[0]);
            req.putInt(p[1]);
        }
        req.putInt(durationMs);
        req.putInt(screenW);
        req.putInt(screenH);
        return readMs(sendAndRead(req.array()));
    }

    /** 按键。 */
    public double key(int keyCode) throws IOException {
        ByteBuffer req = ByteBuffer.allocate(1 + 4);
        req.put(Proto.OP_KEY);
        req.putInt(keyCode);
        return readMs(sendAndRead(req.array()));
    }

    /**
     * 以 root 身份执行命令，返回 {exitCode, 输出}。
     *
     * <p>自动化里常见的"杀掉应用""改系统设置"都要 root 权限，
     * 但脚本层不该自己拼 su 命令（每次都要过一遍授权），所以走已授权的 helper。
     */
    public String[] shell(String cmd) throws IOException {
        byte[] raw = cmd.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.ByteBuffer req = java.nio.ByteBuffer.allocate(1 + raw.length);
        req.put(Proto.OP_SHELL);
        req.put(raw);
        String result = new String(sendAndRead(req.array()),
                java.nio.charset.StandardCharsets.UTF_8);
        int bar = result.indexOf('|');
        if (bar < 0) {
            return new String[]{"-1", result};
        }
        return new String[]{result.substring(0, bar), result.substring(bar + 1)};
    }

    private byte[] sendAndRead(byte[] payload) throws IOException {
        write(payload);
        return readResponse();
    }

    private static double readMs(byte[] data) {
        if (data.length >= 8) {
            return ByteBuffer.wrap(data).getDouble();
        }
        return -1;
    }

    // ------------------------------------------------------------- 内部实现
    private void send(byte op) throws IOException {
        write(new byte[]{op});
    }

    private void write(byte[] payload) throws IOException {
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    private byte[] readResponse() throws IOException {
        byte[] resp = readFrame();
        checkStatus(resp);
        byte[] data = new byte[resp.length - 1];
        System.arraycopy(resp, 1, data, 0, data.length);
        return data;
    }

    private byte[] readFrame() throws IOException {
        int len = in.readInt();
        if (len <= 0 || len > (8 << 20)) {
            throw new IOException("响应长度非法: " + len);
        }
        byte[] resp = new byte[len];
        in.readFully(resp);
        return resp;
    }

    private static void checkStatus(byte[] resp) throws IOException {
        if (resp.length == 0) {
            throw new IOException("空响应");
        }
        if (resp[0] != Proto.ERR_NONE) {
            String msg = resp.length > 1 ? new String(resp, 1, resp.length - 1,
                    java.nio.charset.StandardCharsets.UTF_8) : "";
            throw new IOException("helper 返回错误 " + resp[0] + ": " + msg);
        }
    }

    @Override
    public void close() {
        try {
            out.writeInt(0);   // 让对方读循环退出
            out.flush();
        } catch (IOException ignored) {
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /** 一帧结果 + 各段耗时（毫秒）。 */
    public static final class Shot {
        public final int width;
        public final int height;
        public final int[] argb;
        public final boolean hardwareBitmap;
        /** helper 侧：SurfaceFlinger 合成 */
        public final double composeMs;
        /** helper 侧：HARDWARE 位图回读 */
        public final double readbackMs;
        /** helper 侧：getPixels 拷贝 */
        public final double copyMs;
        /** App 侧：请求发出到开始读响应 */
        public final double requestMs;
        /** App 侧：读响应（含像素传输） */
        public final double transferMs;

        Shot(int width, int height, int[] argb, boolean hardwareBitmap,
             double composeMs, double readbackMs, double copyMs,
             double requestMs, double transferMs) {
            this.width = width;
            this.height = height;
            this.argb = argb;
            this.hardwareBitmap = hardwareBitmap;
            this.composeMs = composeMs;
            this.readbackMs = readbackMs;
            this.copyMs = copyMs;
            this.requestMs = requestMs;
            this.transferMs = transferMs;
        }

        public double helperMs() {
            return composeMs + readbackMs + copyMs;
        }

        public double totalMs() {
            return requestMs + transferMs;
        }
    }
}
