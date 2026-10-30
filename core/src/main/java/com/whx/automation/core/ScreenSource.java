package com.whx.automation.core;

/**
 * 截图后端抽象（项目书 §4.1：支持不同截图后端，按设备环境替换）。
 *
 * 实测结论（2026-10-30 雷电 9 / Android 9）：
 * <ul>
 *   <li>设备无任何 framebuffer 节点，fb0 直读不可行；</li>
 *   <li>{@code SurfaceControl.screenshot} 在 shell/root 身份下可用，约 11.4ms/帧；</li>
 *   <li>返回的位图常为 {@code Config.HARDWARE}，必须回读成 ARGB_8888 才能取像素。</li>
 * </ul>
 *
 * 因此 v1 只实现 SurfaceControl 后端；其它后端（MediaProjection 等）留接口不实现。
 */
public interface ScreenSource {

    /** 后端名字，用于日志与 {@code deviceInfo()} 上报。 */
    String name();

    /** 当前环境下是否可用（例如 helper 未启动时返回 false）。 */
    boolean available();

    /**
     * 抓一帧。
     *
     * @param region 抓取区域 {@code [x, y, w, h]}；传 null 表示整屏
     * @return 帧对象；失败抛 {@link ScreenException}
     */
    Frame capture(int[] region) throws ScreenException;

    /** 释放后端持有的资源（如常驻的 VirtualDisplay）。 */
    void release();

    /** 截图失败。不做静默返回 null。 */
    class ScreenException extends Exception {
        public ScreenException(String message) {
            super(message);
        }

        public ScreenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
