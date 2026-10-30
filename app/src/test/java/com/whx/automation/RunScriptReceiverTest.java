package com.whx.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/** PC 工具链传来的运行时配置解析。 */
public class RunScriptReceiverTest {

    @Test
    public void parsesKeyValues() {
        Map<String, String> m = RunScriptReceiver.parseConfig("x=100,y=640");
        assertEquals(2, m.size());
        assertEquals("100", m.get("x"));
        assertEquals("640", m.get("y"));
    }

    @Test
    public void trimsSpaces() {
        Map<String, String> m = RunScriptReceiver.parseConfig(" x = 1 , y = 2 ");
        assertEquals("1", m.get("x"));
        assertEquals("2", m.get("y"));
    }

    @Test
    public void keepsEqualsInsideValue() {
        Map<String, String> m = RunScriptReceiver.parseConfig("expr=a=b");
        assertEquals("a=b", m.get("expr"));
    }

    @Test
    public void ignoresMalformedPairs() {
        Map<String, String> m = RunScriptReceiver.parseConfig("novalue,=noKey,x=1");
        assertEquals(1, m.size());
        assertEquals("1", m.get("x"));
    }

    @Test
    public void emptyInputGivesEmptyMap() {
        assertTrue(RunScriptReceiver.parseConfig(null).isEmpty());
        assertTrue(RunScriptReceiver.parseConfig("").isEmpty());
    }
}
