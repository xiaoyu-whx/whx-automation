package com.whx.automation.schedule;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机后重新挂载定时任务（AlarmManager 里的闹钟重启后会丢）。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Log.i("WHX/Boot", "开机完成，重新挂载定时任务");
            ScheduleManager.rescheduleAll(context);
        }
    }
}
