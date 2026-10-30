package com.whx.automation.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * {@code getevent -pl} 输出解析。
 *
 * <p>用真实样本做回归：不同机型的设备数量、顺序、是否带 ABS_MT_SLOT 都不同，
 * 这一层出错的后果是"能跑但点偏"或"sendevent 写到错误的节点"，很难在设备上排查。
 */
public class InputProbeTest {

    /** 雷电 9 的真实输出（节选）。 */
    private static final String LTE9 =
            "add device 1: /dev/input/event0\n"
            + "  name:     \"Power Button\"\n"
            + "  events:\n"
            + "    KEY (0001): KEY_POWER\n"
            + "add device 2: /dev/input/event1\n"
            + "  name:     \"virtio_input_device\"\n"
            + "add device 3: /dev/input/event2\n"
            + "  name:     \"virtio_input_multi_touch_1\"\n"
            + "  events:\n"
            + "    ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 15, fuzz 0, flat 0, resolution 0\n"
            + "                ABS_MT_POSITION_X     : value 0, min 0, max 1279, fuzz 0, flat 0, resolution 0\n"
            + "                ABS_MT_POSITION_Y     : value 0, min 0, max 719, fuzz 0, flat 0, resolution 0\n"
            + "                ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0\n";

    @Test
    public void parsesRealDevice() {
        InputProbe p = InputProbe.parseGetevent(LTE9);
        assertEquals("/dev/input/event2", p.devicePath);
        assertEquals("virtio_input_multi_touch_1", p.deviceName);
        assertEquals(1279, p.absMaxX);
        assertEquals(719, p.absMaxY);
        assertEquals(15, p.maxSlot);
        assertFalse("实测结论：不做轴交换", p.swapped);
    }

    @Test
    public void skipsKeyboardBeforeTouch() {
        // 触摸设备排在按键设备之后，必须跳过没有 ABS_MT 的设备
        InputProbe p = InputProbe.parseGetevent(LTE9);
        assertEquals("/dev/input/event2", p.devicePath);
    }

    @Test
    public void worksWithoutSlotAxis() {
        String single = "add device 1: /dev/input/event1\n"
                + "  name:     \"synaptics\"\n"
                + "    ABS_MT_POSITION_X : value 0, min 0, max 1079, fuzz 0\n"
                + "    ABS_MT_POSITION_Y : value 0, min 0, max 1919, fuzz 0\n";
        InputProbe p = InputProbe.parseGetevent(single);
        assertEquals("/dev/input/event1", p.devicePath);
        // absMax 是"最大轴值"，等于宽度-1（样本里就是 1079/1919）
        assertEquals(1079, p.absMaxX);
        assertEquals(1919, p.absMaxY);
        assertEquals("没有 SLOT 轴时默认 0", 0, p.maxSlot);
    }

    @Test
    public void picksFirstDeviceWithBothAxes() {
        String two = "add device 1: /dev/input/event3\n"
                + "  name:     \"touch_a\"\n"
                + "    ABS_MT_POSITION_X : value 0, min 0, max 999, fuzz 0\n"
                + "    ABS_MT_POSITION_Y : value 0, min 0, max 1999, fuzz 0\n"
                + "add device 2: /dev/input/event4\n"
                + "  name:     \"touch_b\"\n"
                + "    ABS_MT_POSITION_X : value 0, min 0, max 1111, fuzz 0\n"
                + "    ABS_MT_POSITION_Y : value 0, min 0, max 2222, fuzz 0\n";
        InputProbe p = InputProbe.parseGetevent(two);
        assertEquals("/dev/input/event3", p.devicePath);
        assertEquals(999, p.absMaxX);
    }

    @Test
    public void ignoresHalfSpecifiedDevice() {
        // 只有 X 没有 Y 的设备不能被当成触摸屏
        String half = "add device 1: /dev/input/event5\n"
                + "  name:     \"half\"\n"
                + "    ABS_MT_POSITION_X : value 0, min 0, max 500, fuzz 0\n";
        try {
            InputProbe.parseGetevent(half);
            fail("应抛异常");
        } catch (IllegalStateException expected) {
            assertEquals(true, expected.getMessage().contains("ABS_MT_POSITION_X"));
        }
    }

    @Test(expected = IllegalStateException.class)
    public void emptyOutputThrows() {
        InputProbe.parseGetevent("");
    }

    @Test(expected = IllegalStateException.class)
    public void nullOutputThrows() {
        InputProbe.parseGetevent(null);
    }
}
