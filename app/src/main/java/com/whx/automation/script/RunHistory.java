package com.whx.automation.script;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 运行历史：最近 N 次任务的结果。
 *
 * <p>无人值守场景（定时任务、小精灵）出问题时人不在现场，事后必须能回答三个问题：
 * 什么时候跑的、成没成功、失败原因是什么。所以这里只记这四样，不记日志全文
 * （全文在 logcat 里，会被系统回卷覆盖）。
 *
 * <p>格式：一行一条 {@code 时间|脚本|结果|耗时ms|错误}，最多保留 {@link #MAX} 条。
 */
public final class RunHistory {

    private static final String PREF = "whx_history";
    private static final String KEY = "entries";
    public static final int MAX = 20;

    public static final class Record {
        public final String time;
        public final String script;
        public final boolean ok;
        public final long costMs;
        public final String error;

        Record(String time, String script, boolean ok, long costMs, String error) {
            this.time = time;
            this.script = script;
            this.ok = ok;
            this.costMs = costMs;
            this.error = error;
        }

        String encode() {
            return time + "|" + script + "|" + (ok ? "1" : "0") + "|" + costMs + "|"
                    + (error == null ? "" : error.replace('|', '/'));
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s  %s  %s  %dms%s", time, ok ? "成功" : "失败",
                    script, costMs, ok || error.isEmpty() ? "" : "  " + error);
        }
    }

    private final SharedPreferences prefs;

    public RunHistory(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 记一条（新的在前，超过上限丢弃最旧的）。 */
    public void add(String script, boolean ok, long costMs, String error) {
        String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        List<Record> records = list();
        records.add(0, new Record(time, script == null ? "" : script, ok, costMs, error));
        while (records.size() > MAX) {
            records.remove(records.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (Record r : records) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(r.encode());
        }
        prefs.edit().putString(KEY, sb.toString()).commit();
    }

    public List<Record> list() {
        return parse(prefs.getString(KEY, ""));
    }

    /** 解析存储串（抽成静态方法便于单元测试）。 */
    static List<Record> parse(String raw) {
        List<Record> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String[] parts = line.split("\\|", -1);
            if (parts.length < 4) {
                continue;
            }
            try {
                out.add(new Record(parts[0].trim(), parts[1].trim(), "1".equals(parts[2].trim()),
                        Long.parseLong(parts[3].trim()),
                        parts.length > 4 ? parts[4] : ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
