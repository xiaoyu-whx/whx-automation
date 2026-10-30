package com.whx.automation.helper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 坐标换算的回归测试。
 *
 * <p>这些用例钉住的是一个**踩过的坑**：早期判断"触摸轴是横屏坐标、需要交换"是错的，
 * 正确规则是「每轴各自按 absMax 缩放到屏幕对应维度」。如果以后有人"顺手"把交换逻辑加回来，
 * 这里会立刻红灯。
 */
public class AxisMapperTest {

    /** 雷电 9 实测参数：屏幕 720×1280，触摸轴 absMax 1279 / 719。 */
    private AxisMapper lte() {
        return new AxisMapper(720, 1280, 1279, 719, false);
    }

    @Test
    public void identityMapping_matchesDeviceMeasurement() {
        AxisMapper m = lte();
        // 实测：注入 raw(X=463, Y=114) 时应用收到屏幕坐标 (114, 463) 附近；
        // 反过来，屏幕 (114, 463) 应对应 raw(X≈202, Y≈260)
        assertEquals(202, m.axisX(114, 463));
        assertEquals(260, m.axisY(114, 463));
    }

    @Test
    public void cornersMapToAxisExtremes() {
        AxisMapper m = lte();
        assertEquals(0, m.axisX(0, 0));
        assertEquals(0, m.axisY(0, 0));
        assertEquals(1279, m.axisX(719, 0));
        assertEquals(719, m.axisY(0, 1279));
    }

    @Test
    public void centerMapsToRoughlyCenter() {
        AxisMapper m = lte();
        // 屏幕中心 (360, 640) → 轴中点附近（允许 1 个单位的取整误差）
        assertEquals(640, m.axisX(360, 640), 1);
        assertEquals(360, m.axisY(360, 640), 1);
    }

    @Test
    public void swapMode_isAvailableButOffByDefault() {
        AxisMapper swapped = new AxisMapper(720, 1280, 1279, 719, true);
        // 交换后：轴 X 取屏幕 y，按屏幕高缩放
        assertEquals(200, swapped.axisX(100, 200));
        // 轴 Y 取屏幕 x，按屏幕宽缩放
        assertEquals(100, swapped.axisY(100, 200));
    }

    @Test
    public void worksForDifferentResolution() {
        // 1080×1920 下同一套 absMax（实测切换 wm size 后 absMax 不变）
        AxisMapper m = new AxisMapper(1080, 1920, 1279, 719, false);
        assertEquals(640, m.axisX(540, 0), 1);
        assertEquals(359, m.axisY(0, 960), 1);
        assertEquals(1279, m.axisX(1079, 0));
        assertEquals(719, m.axisY(0, 1919));
    }

    @Test
    public void outOfRangeIsClamped() {
        AxisMapper m = lte();
        assertEquals(0, m.axisX(-50, 0));
        assertEquals(1279, m.axisX(5000, 0));
        assertEquals(0, m.axisY(0, -10));
        assertEquals(719, m.axisY(0, 5000));
    }

    @Test
    public void zeroRangeDoesNotDivideByZero() {
        AxisMapper degenerate = new AxisMapper(1, 1, 100, 100, false);
        assertEquals(0, degenerate.axisX(0, 0));
        assertEquals(0, degenerate.axisY(0, 0));
    }
}
