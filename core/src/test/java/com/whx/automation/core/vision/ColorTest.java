package com.whx.automation.core.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 颜色解析与容差判断。 */
public class ColorTest {

    @Test
    public void parseHexSixDigits() {
        assertEquals(0xFFFF5722, Color.parse("#FF5722"));
        assertEquals(0xFFFF5722, Color.parse("FF5722"));
    }

    @Test
    public void parseHexEightDigitsKeepsAlpha() {
        assertEquals(0x80FF5722, Color.parse("#80FF5722"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseRejectsBadLength() {
        Color.parse("#FFF");
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseRejectsNull() {
        Color.parse(null);
    }

    @Test
    public void ofValidatesChannels() {
        assertEquals(0xFF112233, Color.of(0x11, 0x22, 0x33));
        try {
            Color.of(256, 0, 0);
            throw new AssertionError("应当抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void channelAccessors() {
        int c = Color.of(10, 20, 30);
        assertEquals(10, Color.red(c));
        assertEquals(20, Color.green(c));
        assertEquals(30, Color.blue(c));
        assertEquals("#0A141E", Color.toHex(c));
    }

    @Test
    public void similarRespectsPerChannelTolerance() {
        int a = Color.parse("#808080");
        int b = Color.parse("#888888");   // 每通道差 8
        assertTrue(Color.similar(a, b, 8));
        assertFalse(Color.similar(a, b, 7));
        assertTrue(Color.similar(a, a, 0));
        assertFalse(Color.similar(Color.parse("#FF0000"), Color.parse("#00FF00"), 100));
    }

    @Test
    public void similarZeroToleranceIsExact() {
        assertTrue(Color.similar(Color.parse("#123456"), Color.parse("#123456"), 0));
        assertFalse(Color.similar(Color.parse("#123456"), Color.parse("#123457"), 0));
    }

    @Test
    public void alphaIsIgnoredInSimilarity() {
        // 相似度只看 RGB，不透明度不参与比较
        assertTrue(Color.similar(0x00FF0000, 0xFFFF0000, 0));
    }
}
