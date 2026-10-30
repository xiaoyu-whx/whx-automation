package com.whx.automation.script;

import android.content.Context;
import android.util.Log;

import com.whx.automation.capture.HelperScreenSource;
import com.whx.automation.input.HelperInput;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务管理：同一时刻只允许一个任务，负责状态、时长、停止与结果通知。
 *
 * <p>把「脚本执行」包装成「任务」，是为了让界面、通知栏、日志都只依赖一个状态源，
 * 避免各自记录一套状态最后对不上。
 */
public final class TaskManager {

    private static final String TAG = "WHX/Task";
    private static TaskManager instance;

    public enum State {
        IDLE, RUNNING, STOPPING
    }

    /** 任务事件回调（界面与通知栏各自实现）。 */
    public interface Observer {
        void onTaskLog(String level, String message);

        void onTaskStateChanged(State state, String scriptName);

        void onTaskDone(boolean ok, String error, long costMs);
    }

    private final ScriptHost host;
    private final List<Observer> observers = new CopyOnWriteArrayList<>();
    private volatile State state = State.IDLE;
    private volatile String scriptName = "";
    private volatile long startedAtMs;

    private TaskManager(Context context) {
        host = new ScriptHost(new HelperScreenSource(context), new HelperInput(context));
    }

    public static synchronized TaskManager get(Context context) {
        if (instance == null) {
            instance = new TaskManager(context.getApplicationContext());
        }
        return instance;
    }

    public void addObserver(Observer o) {
        if (!observers.contains(o)) {
            observers.add(o);
        }
    }

    public void removeObserver(Observer o) {
        observers.remove(o);
    }

    public State state() {
        return state;
    }

    public String scriptName() {
        return scriptName;
    }

    public long startedAtMs() {
        return startedAtMs;
    }

    public long elapsedMs() {
        return state == State.IDLE ? 0 : System.currentTimeMillis() - startedAtMs;
    }

    /** 启动任务；已在运行时返回 false（不排队，先保持简单）。 */
    public synchronized boolean start(String name, String code) {
        return start(name, code, java.util.Collections.<String, String>emptyMap());
    }

    /** 带配置启动（小精灵用）。 */
    public synchronized boolean start(String name, String code,
                                      java.util.Map<String, String> config) {
        if (state != State.IDLE) {
            return false;
        }
        scriptName = name;
        startedAtMs = System.currentTimeMillis();
        state = State.RUNNING;
        notifyState();
        Log.i(TAG, "start " + name);

        boolean ok = host.run(code, name, new ScriptHost.Listener() {
            @Override
            public void onLog(String level, String message) {
                for (Observer o : observers) {
                    o.onTaskLog(level, message);
                }
            }

            @Override
            public void onDone(boolean success, String error, long costMs) {
                state = State.IDLE;
                notifyState();
                for (Observer o : observers) {
                    o.onTaskDone(success, error, costMs);
                }
                Log.i(TAG, "done ok=" + success + " cost=" + costMs + "ms");
            }
        }, config);
        if (!ok) {
            state = State.IDLE;
            notifyState();
        }
        return ok;
    }

    public synchronized void stop() {
        if (state != State.RUNNING) {
            return;
        }
        state = State.STOPPING;
        notifyState();
        host.stop();
    }

    private void notifyState() {
        for (Observer o : observers) {
            o.onTaskStateChanged(state, scriptName);
        }
    }
}
