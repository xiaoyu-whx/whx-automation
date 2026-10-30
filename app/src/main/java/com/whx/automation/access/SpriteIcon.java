package com.whx.automation.access;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * 小精灵图标切换。
 *
 * <p>为什么用 activity-alias 而不是改 resources.arsc：`android:icon` 在二进制清单里存的是
 * **资源 id（int）**，不是字符串，改它要重写资源表；而 activity-alias 让"同一份代码、
 * 多个启动入口、各自不同图标"，运行时用 PackageManager 启用其中一个即可。
 *
 * <p>清单里有 5 个入口：{@code .Launcher}（默认，普通模式用）+ {@code .Sprite1..4}。
 * 同一时刻只启用一个，保证桌面只出现一个图标。
 */
public final class SpriteIcon {

    private static final String TAG = "WHX/Icon";
    public static final int MAX = 4;

    private SpriteIcon() {
    }

    /** 启用指定图标（1..4）；index<=0 表示回归默认图标。 */
    public static void activate(Context context, int index) {
        int target = (index < 1 || index > MAX) ? 0 : index;
        for (int i = 0; i <= MAX; i++) {
            setEnabled(context, i, i == target);
        }
        Log.i(TAG, "图标切换为 " + (target == 0 ? "默认" : "sprite" + target));
    }

    /**
     * 包名会被打包器改掉，但清单里的组件类名是 AGP 按 **namespace** 展开的绝对名，
     * 所以这里必须用固定前缀 com.whx.automation，不能拼 getPackageName()
     * （实测踩过：拼包名得到 com.whx.sprite.icon.Sprite4，系统说类不存在）。
     */
    private static final String CLASS_PREFIX = "com.whx.automation.";

    private static void setEnabled(Context context, int index, boolean enabled) {
        String cls = (index == 0 ? "Launcher" : "Sprite" + index);
        ComponentName component = new ComponentName(context.getPackageName(), CLASS_PREFIX + cls);
        PackageManager pm = context.getPackageManager();
        int desired = enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        try {
            int current = pm.getComponentEnabledSetting(component);
            if (current != desired) {
                pm.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP);
            }
        } catch (Exception e) {
            // 早期签名的包可能没有这些 alias，忽略即可，不影响主流程
            Log.w(TAG, "切换入口失败 " + cls + ": " + e.getMessage());
        }
    }
}
