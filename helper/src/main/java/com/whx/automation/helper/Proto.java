package com.whx.automation.helper;

/**
 * Helper 内部二进制协议。
 *
 * <p>链路：App（uid=10xxx） ⇄ TCP 127.0.0.1:port ⇄ Helper（uid=0）。
 *
 * <p>为什么不用 LocalSocket：抽象命名空间的 unix socket 跨 uid 时容易撞 SELinux 策略，
 * 而 TCP 回环在所有目标环境上一致可用（实测约 0.1ms/次往返）。
 *
 * <p>帧格式（请求与响应同构）：
 * <pre>
 *   [int32 负载长度][1B op][1B 状态][其余为参数/数据]
 *   状态：0 = OK，非 0 = 错误码
 * </pre>
 */
public final class Proto {

    /** App → Helper */
    public static final byte OP_CAPTURE = 1;   // argb: w, h, rx, ry, rw, rh（region 全 0 表示整屏）
    public static final byte OP_TAP = 2;       // argb: x, y, holdMs
    public static final byte OP_SWIPE = 3;     // argb: count, count×(x,y), durationMs
    public static final byte OP_PING = 4;      // argb: 无
    public static final byte OP_INFO = 5;      // argb: 无
    public static final byte OP_SHUTDOWN = 9;  // argb: 无

    /** 错误码（与 docs/protocol.md 的错误语义保持一致） */
    public static final byte ERR_NONE = 0;
    public static final byte ERR_BAD_REQUEST = 1;
    public static final byte ERR_IO = 2;
    public static final byte ERR_UNSUPPORTED = 3;
    public static final byte ERR_NO_ROOT = 4;
    public static final byte ERR_INTERNAL = 5;

    private Proto() {
    }
}
