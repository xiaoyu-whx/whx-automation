package com.whx.automation.schedule;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.whx.automation.AutomationService;
import com.whx.automation.script.ScheduleStore;
import com.whx.automation.script.ScriptStore;
import com.whx.automation.script.TaskManager;

/**
 * 定时触发与计划管理广播入口。
 *
 * <p>三个动作：
 * <ul>
 *   <li>{@code SCHEDULE_RUN}：闹钟到点 → 拉起服务并运行脚本，然后续下一天；</li>
 *   <li>{@code SCHEDULE_ADD} / {@code SCHEDULE_REMOVE}：PC 工具链（whx schedule）用它管理计划。</li>
 * </ul>
 *
 * <p><b>安全说明</b>：这个 receiver 必须 exported，否则 adb/PC 无法添加计划。
 * 代价是本机任意应用都能添加/删除计划 —— 但能添加的只能是**已经存在设备上的脚本**，
 * 且设备本就已 root，威胁面没有实质扩大。
 */
public class ScheduleReceiver extends BroadcastReceiver {

    private static final String TAG = "WHX/Schedule";
    public static final String ACTION_ADD = "com.whx.automation.SCHEDULE_ADD";
    public static final String ACTION_REMOVE = "com.whx.automation.SCHEDULE_REMOVE";
    public static final String ACTION_LIST = "com.whx.automation.SCHEDULE_LIST";
    public static final String EXTRA_SCRIPT = "script";
    public static final String EXTRA_HOUR = "hour";
    public static final String EXTRA_MINUTE = "minute";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        Context app = context.getApplicationContext();
        switch (action) {
            case ScheduleManager.ACTION_RUN:
                runScheduled(app, intent.getStringExtra(EXTRA_SCRIPT));
                break;
            case ACTION_ADD:
                addSchedule(app, intent);
                break;
            case ACTION_REMOVE:
                removeSchedule(app, intent.getStringExtra(EXTRA_SCRIPT));
                break;
            case ACTION_LIST:
                for (ScheduleStore.Entry e : new ScheduleStore(app).list()) {
                    Log.i(TAG, "计划: " + e);
                }
                break;
            default:
                break;
        }
    }

    private void runScheduled(Context app, String script) {
        if (script == null || script.isEmpty()) {
            return;
        }
        Log.i(TAG, "闹钟触发: " + script);
        // 无论成败都要续下一天，否则这条计划只跑一次
        try {
            ScriptStore store = new ScriptStore(app);
            // 小精灵包只有绑定脚本，没有导入目录；两种来源都要能跑
            String code;
            ScriptStore.Bundle bundle = store.bundle();
            if (bundle != null && bundle.scriptName.equals(script)) {
                code = store.loadBundleScript(script);
            } else {
                code = store.load(new ScriptStore.Script(
                        script, ScriptStore.Source.IMPORTED, 0));
            }
            app.startForegroundService(new Intent(app, AutomationService.class));
            TaskManager.get(app).start(script, code);
        } catch (Exception e) {
            Log.e(TAG, "定时任务失败: " + script, e);
        } finally {
            ScheduleManager.rescheduleAll(app);
        }
    }

    private void addSchedule(Context app, Intent intent) {
        String script = intent.getStringExtra(EXTRA_SCRIPT);
        int hour = intent.getIntExtra(EXTRA_HOUR, -1);
        int minute = intent.getIntExtra(EXTRA_MINUTE, -1);
        boolean ok = new ScheduleStore(app).add(script, hour, minute);
        Log.i(TAG, "添加计划 " + script + " " + hour + ":" + minute + " → " + ok);
        ScheduleManager.rescheduleAll(app);
    }

    private void removeSchedule(Context app, String script) {
        boolean ok = new ScheduleStore(app).remove(script);
        Log.i(TAG, "删除计划 " + script + " → " + ok);
        ScheduleManager.rescheduleAll(app);
    }
}
