package com.whx.automation.script;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 定时任务存储：每天某个时刻运行某个脚本。
 *
 * <p>用 SharedPreferences 而不是数据库：条目少、结构简单、跨版本兼容，
 * 而且掉电重启后还在。格式是 {@code 名字|小时|分钟} 一行为一条。
 */
public final class ScheduleStore {

    private static final String PREF = "whx_schedules";
    private static final String KEY = "entries";

    public static final class Entry {
        public final String script;
        public final int hour;
        public final int minute;

        public Entry(String script, int hour, int minute) {
            this.script = script;
            this.hour = hour;
            this.minute = minute;
        }

        public String encode() {
            return script + "|" + hour + "|" + minute;
        }

        @Override
        public String toString() {
            return String.format("%02d:%02d  %s", hour, minute, script);
        }
    }

    private final SharedPreferences prefs;

    public ScheduleStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public List<Entry> list() {
        return parse(prefs.getString(KEY, ""));
    }

    /** 解析存储串（抽成静态方法便于单元测试）。 */
    static List<Entry> parse(String raw) {
        List<Entry> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length != 3) {
                continue;
            }
            try {
                String script = parts[0].trim();
                int hour = Integer.parseInt(parts[1].trim());
                int minute = Integer.parseInt(parts[2].trim());
                // 存储串可能是旧版本或被人手改过：越界条目直接丢弃，
                // 否则会算出非法闹钟时间（宁可少跑一条，不可乱跑）
                if (script.isEmpty() || hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                    continue;
                }
                out.add(new Entry(script, hour, minute));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** 添加或更新（同名同时间的去重）。返回是否成功。 */
    public boolean add(String script, int hour, int minute) {
        if (script == null || script.isEmpty() || hour < 0 || hour > 23
                || minute < 0 || minute > 59) {
            return false;
        }
        List<Entry> entries = list();
        for (Entry e : entries) {
            if (e.script.equals(script) && e.hour == hour && e.minute == minute) {
                return true;     // 已存在
            }
        }
        entries.add(new Entry(script, hour, minute));
        save(entries);
        return true;
    }

    public boolean remove(String script) {
        List<Entry> entries = list();
        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.script.equals(script)) {
                kept.add(e);
            }
        }
        if (kept.size() == entries.size()) {
            return false;
        }
        save(kept);
        return true;
    }

    public void clear() {
        prefs.edit().remove(KEY).apply();
    }

    private void save(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.encode());
        }
        prefs.edit().putString(KEY, sb.toString()).apply();
    }
}
