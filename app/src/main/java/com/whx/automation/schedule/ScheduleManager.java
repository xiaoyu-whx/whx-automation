package com.whx.automation.schedule;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.whx.automation.script.ScheduleStore;

import java.util.Calendar;
import java.util.List;

/**
 * 定时任务调度：给每条计划挂一个 AlarmManager 一次性闹钟，到点触发后自动续下一天。
 *
 * <p>为什么用一次性闹钟而不是重复闹钟：每天下一次触发时间要按"现在几点"重算，
 * 一次性 + 触发后重排，逻辑最简单也最不容易错（重复闹钟在夏令时/改时间时会漂）。
 */
public final class ScheduleManager {

    private static final String TAG = "WHX/Schedule";
    public static final String ACTION_RUN = "com.whx.automation.SCHEDULE_RUN";

    private ScheduleManager() {
    }

    /** 重新挂载全部闹钟（开机、修改计划后调用）。 */
    public static void rescheduleAll(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        ScheduleStore store = new ScheduleStore(app);
        List<ScheduleStore.Entry> entries = store.list();
        for (ScheduleStore.Entry e : entries) {
            schedule(app, am, e);
        }
        Log.i(TAG, "已挂载 " + entries.size() + " 条定时任务");
    }

    private static void schedule(Context app, AlarmManager am, ScheduleStore.Entry entry) {
        long triggerAt = nextTrigger(entry.hour, entry.minute);
        PendingIntent pi = pendingIntent(app, entry);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 没申请精确闹钟权限时退化为不精确（可能晚几分钟），不抛异常
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
        Log.i(TAG, String.format("%s 下次触发 %s", entry,
                new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                        .format(new java.util.Date(triggerAt))));
    }

    /** 计算下一次触发时间（今天还没到就今天，否则明天）。 */
    public static long nextTrigger(int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (!target.after(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1);
        }
        return target.getTimeInMillis();
    }

    private static PendingIntent pendingIntent(Context app, ScheduleStore.Entry entry) {
        Intent intent = new Intent(app, ScheduleReceiver.class);
        intent.setAction(ACTION_RUN);
        intent.putExtra(ScheduleReceiver.EXTRA_SCRIPT, entry.script);
        int requestCode = (entry.script + entry.hour + ":" + entry.minute).hashCode();
        return PendingIntent.getBroadcast(app, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static void cancel(Context context, ScheduleStore.Entry entry) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        am.cancel(pendingIntent(context.getApplicationContext(), entry));
    }
}
