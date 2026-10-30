package com.whx.automation.core;

/**
 * 触控后端抽象（项目书 §4.3：触控后端采用可替换设计）。
 *
 * 实测结论（2026-10-30 雷电 9）：
 * <ul>
 *   <li>root 直写 evdev：9 条事件 0.033ms，完整 tap（按住 30ms）31.3ms；</li>
 *   <li>{@code input tap} 命令：134.6ms（进程 + JVM 开销）；</li>
 *   <li>触摸轴为横屏坐标：轴X=屏幕y、轴Y=屏幕x（max 1279/719），不交换则静默点偏。</li>
 * </ul>
 *
 * 坐标约定：本接口**只接受屏幕坐标**（左上原点），轴换算由实现内部完成。
 */
public interface InputBackend {

    String name();

    boolean available();

    /** 点击。(x, y) 为屏幕坐标，holdMs 为按住时长（建议 30~40ms）。 */
    void tap(int x, int y, int holdMs) throws InputException;

    /** 滑动。points 为屏幕坐标序列，durationMs 为总时长。 */
    void swipe(int[][] points, int durationMs) throws InputException;

    /** 按键，传 Android KeyEvent keycode。 */
    void key(int keyCode) throws InputException;

    void release();

    /** 触控失败。不静默忽略。 */
    class InputException extends Exception {
        public InputException(String message) {
            super(message);
        }

        public InputException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
