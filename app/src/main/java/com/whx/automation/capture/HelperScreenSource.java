package com.whx.automation.capture;

import android.content.Context;
import android.util.DisplayMetrics;

import com.whx.automation.core.Frame;
import com.whx.automation.core.ScreenSource;
import com.whx.automation.helper.HelperClient;
import com.whx.automation.helper.HelperSession;

/**
 * 通过 Root Helper 抓帧的 {@link ScreenSource} 实现（v1 唯一实现）。
 *
 * <p>时序（实测分解）：SurfaceFlinger 合成 3.5ms → HARDWARE 位图回读 5.7ms →
 * getPixels 2.3ms → TCP 回环传输（像素 3.7MB）。
 */
public final class HelperScreenSource implements ScreenSource {

    private final Context context;
    private long nextFrameId = 1;
    private volatile HelperClient.Shot lastShot;

    public HelperScreenSource(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String name() {
        return "helper/surfacecontrol";
    }

    @Override
    public boolean available() {
        return HelperSession.get().isAlive();   // 缓存值，不发网络请求
    }

    @Override
    public Frame capture(int[] region) throws ScreenException {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        HelperClient.Shot shot;
        try {
            HelperClient client = HelperSession.get().ensureClient(context);
            shot = client.capture(dm.widthPixels, dm.heightPixels, region);
        } catch (Exception e) {
            throw new ScreenException("抓帧失败: " + e.getMessage(), e);
        }
        lastShot = shot;

        int originX = region != null ? region[0] : 0;
        int originY = region != null ? region[1] : 0;
        return new Frame(nextFrameId++, System.currentTimeMillis(),
                originX, originY, shot.width, shot.height, shot.argb);
    }

    /** 最近一次的耗时分解，供调试界面显示。 */
    public HelperClient.Shot lastShot() {
        return lastShot;
    }

    @Override
    public void release() {
        HelperSession.get().stop();
    }
}
