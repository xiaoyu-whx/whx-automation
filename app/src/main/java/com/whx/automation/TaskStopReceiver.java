package com.whx.automation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.whx.automation.script.TaskManager;

/** 通知栏「停止」按钮的回调。 */
public class TaskStopReceiver extends BroadcastReceiver {

    public static final String ACTION_STOP = "com.whx.automation.STOP_TASK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_STOP.equals(intent.getAction())) {
            TaskManager.get(context).stop();
        }
    }
}
