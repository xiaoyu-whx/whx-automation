package com.whx.automation.script;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** 运行历史的编码与解析。 */
public class RunHistoryTest {

    @Test
    public void parseMultipleRecords() {
        List<RunHistory.Record> rs = RunHistory.parse(
                "10-31 00:20:00|demo.js|1|720|\n10-31 00:21:00|bad.js|0|98|boom");
        assertEquals(2, rs.size());
        assertTrue(rs.get(0).ok);
        assertEquals("demo.js", rs.get(0).script);
        assertEquals(720, rs.get(0).costMs);
        assertEquals("", rs.get(0).error);
        assertEquals("boom", rs.get(1).error);
    }

    @Test
    public void parseSkipsMalformed() {
        List<RunHistory.Record> rs = RunHistory.parse("乱写\nx|y|1\nok.js|1|0|10|");
        assertEquals(1, rs.size());
    }

    @Test
    public void parseEmpty() {
        assertTrue(RunHistory.parse(null).isEmpty());
        assertTrue(RunHistory.parse("").isEmpty());
    }

    @Test
    public void errorWithPipeIsSanitized() {
        // 错误信息里可能含竖线，编码时必须转义，否则解析会错位
        RunHistory.Record r = new RunHistory.Record("t", "s.js", false, 5, "a|b");
        String encoded = r.encode();
        assertEquals("t|s.js|0|5|a/b", encoded);
        assertEquals(4, encoded.chars().filter(c -> c == '|').count());
    }

    @Test
    public void toStringReadable() {
        RunHistory.Record r = new RunHistory.Record("10-31 00:20:00", "demo.js", true, 720, "");
        assertTrue(r.toString().contains("成功"));
        assertTrue(r.toString().contains("720ms"));
    }
}
