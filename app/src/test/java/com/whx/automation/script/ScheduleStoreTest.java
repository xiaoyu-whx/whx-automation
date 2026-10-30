package com.whx.automation.script;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.whx.automation.schedule.ScheduleManager;

import org.junit.Test;

import java.util.Calendar;
import java.util.List;

/** 定时任务的存储解析与下次触发时间计算。 */
public class ScheduleStoreTest {

    @Test
    public void parseMultipleEntries() {
        List<ScheduleStore.Entry> entries = ScheduleStore.parse("a.js|8|0\nb.js|21|30");
        assertEquals(2, entries.size());
        assertEquals("a.js", entries.get(0).script);
        assertEquals(8, entries.get(0).hour);
        assertEquals(30, entries.get(1).minute);
    }

    @Test
    public void parseSkipsMalformedLines() {
        List<ScheduleStore.Entry> entries = ScheduleStore.parse("坏行\nx.js|1|2\n|3|4\nx.js|99|99");
        assertEquals(1, entries.size());
        assertEquals("x.js", entries.get(0).script);
    }

    @Test
    public void parseEmptyReturnsEmptyList() {
        assertTrue(ScheduleStore.parse(null).isEmpty());
        assertTrue(ScheduleStore.parse("").isEmpty());
    }

    @Test
    public void entryEncodeRoundTrip() {
        ScheduleStore.Entry e = new ScheduleStore.Entry("t.js", 7, 5);
        assertEquals("t.js|7|5", e.encode());
        assertEquals("07:05  t.js", e.toString());
    }

    @Test
    public void nextTriggerIsInTheFuture() {
        long now = System.currentTimeMillis();
        long next = ScheduleManager.nextTrigger(3, 30);
        assertTrue("下次触发必须在未来", next > now);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(next);
        assertEquals(3, c.get(Calendar.HOUR_OF_DAY));
        assertEquals(30, c.get(Calendar.MINUTE));
        assertEquals(0, c.get(Calendar.SECOND));
    }

    @Test
    public void nextTriggerIsWithinOneDay() {
        long now = System.currentTimeMillis();
        long next = ScheduleManager.nextTrigger(0, 0);
        long dayMs = 24L * 3600 * 1000;
        assertTrue(next - now <= dayMs);
        assertTrue(next - now > 0);
    }
}
