package com.whx.automation.script;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;

/** 小精灵配置项的解析与校验。 */
public class ScriptConfigTest {

    private ScriptConfig parse(String text) throws Exception {
        return ScriptConfig.parse(new BufferedReader(new StringReader(text)));
    }

    @Test
    public void parsesFieldsInFileOrder() throws Exception {
        ScriptConfig c = parse(String.join("\n",
                "# 注释行要忽略",
                "",
                "x | 点击 X 坐标 | number | 360",
                "y | 点击 Y 坐标 | number | 640",
                "flag | 开关 | switch | true"));
        assertEquals(3, c.fields().size());
        assertEquals("x", c.fields().get(0).key);
        assertEquals("点击 X 坐标", c.fields().get(0).label);
        assertEquals(ScriptConfig.Type.NUMBER, c.fields().get(0).type);
        assertEquals(ScriptConfig.Type.SWITCH, c.fields().get(2).type);
        assertEquals("640", c.get("y"));
    }

    @Test
    public void defaultsToTextTypeAndEmptyValue() throws Exception {
        ScriptConfig c = parse("name|名字");
        assertEquals(ScriptConfig.Type.TEXT, c.fields().get(0).type);
        assertEquals("", c.get("name"));
        assertEquals("名字", c.fields().get(0).label);
    }

    @Test
    public void missingLabelFallsBackToKey() throws Exception {
        ScriptConfig c = parse("k||text|v");
        assertEquals("k", c.fields().get(0).label);
    }

    @Test
    public void ignoresMalformedLines() throws Exception {
        ScriptConfig c = parse("没有竖线的行\nx|X|number|1");
        assertEquals(1, c.fields().size());
        assertEquals("x", c.fields().get(0).key);
    }

    @Test
    public void setAndGetRoundTrip() throws Exception {
        ScriptConfig c = parse("x|X|number|360");
        c.set("x", " 800 ");
        assertEquals("800", c.get("x"));
        assertEquals("800", c.snapshot().get("x"));
    }

    @Test
    public void settingUnknownKeyIsIgnored() throws Exception {
        ScriptConfig c = parse("x|X|number|1");
        c.set("nope", "1");
        assertFalse(c.snapshot().containsKey("nope"));
    }

    @Test
    public void validateRejectsNonNumber() throws Exception {
        ScriptConfig c = parse("x|点击 X|number|360");
        c.set("x", "abc");
        String err = c.validate();
        assertNotNull(err);
        assertTrue(err.contains("点击 X"));
    }

    @Test
    public void validateRejectsEmptyNumber() throws Exception {
        ScriptConfig c = parse("x|X|number|");
        assertNotNull(c.validate());
    }

    @Test
    public void validateAcceptsDecimalsAndNegatives() throws Exception {
        ScriptConfig c = parse("x|X|number|0");
        c.set("x", "-12.5");
        assertNull(c.validate());
    }

    @Test
    public void savedValueCanOverrideDefault() throws Exception {
        // 持久化本身依赖 SharedPreferences，这里验证"覆盖"语义：
        // 保存过的键必须盖掉 config.txt 里的默认值，未保存的保持默认
        ScriptConfig c = parse("x|X|number|360" + System.lineSeparator() + "y|Y|number|640");
        assertEquals("360", c.get("x"));
        c.set("x", "111");
        assertEquals("111", c.get("x"));
        assertEquals("640", c.get("y"));
    }

    @Test
    public void emptyConfigIsEmpty() throws Exception {
        ScriptConfig c = parse("# 只有注释\n");
        assertTrue(c.isEmpty());
        assertNull(c.validate());
    }
}
