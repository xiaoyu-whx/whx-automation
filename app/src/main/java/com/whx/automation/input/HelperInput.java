package com.whx.automation.input;

import android.content.Context;
import android.util.DisplayMetrics;

import com.whx.automation.core.InputBackend;
import com.whx.automation.helper.HelperClient;
import com.whx.automation.helper.HelperSession;

/**
 * 触控后端 v1 实现：经 Root Helper 直接写 evdev。
 *
 * <p>实测（雷电 9）：9 条事件写入 0.033ms，完整 tap 受按住时长支配（hold 30ms → 31.3ms）。
 * 对照 {@code input tap} 的 134.6ms。
 *
 * <p>坐标：本类只接受**屏幕坐标**，轴交换与 absmax 映射由 helper 内部完成。
 */
public final class HelperInput implements InputBackend {

    private final Context context;

    public HelperInput(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String name() {
        return "helper/evdev";
    }

    @Override
    public boolean available() {
        return HelperSession.get().isAlive();
    }

    @Override
    public void tap(int x, int y, int holdMs) throws InputException {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        try {
            HelperClient c = HelperSession.get().ensureClient(context);
            c.tap(x, y, holdMs, dm.widthPixels, dm.heightPixels);
        } catch (Exception e) {
            throw new InputException("点击失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void swipe(int[][] points, int durationMs) throws InputException {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        try {
            HelperClient c = HelperSession.get().ensureClient(context);
            c.swipe(points, durationMs, dm.widthPixels, dm.heightPixels);
        } catch (Exception e) {
            throw new InputException("滑动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void key(int keyCode) throws InputException {
        try {
            HelperClient c = HelperSession.get().ensureClient(context);
            c.key(keyCode);
        } catch (Exception e) {
            throw new InputException("按键失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void release() {
        HelperSession.get().stop();
    }
}
