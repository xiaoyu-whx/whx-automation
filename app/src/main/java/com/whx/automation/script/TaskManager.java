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
    private final RunHistory history;
    private final List<Observer> observers = new CopyOnWriteArrayList<>();
    private volatile State state = State.IDLE;
    private volatile String scriptName = "";
    private volatile long startedAtMs;
    private volatile String retryCode;
    private volatile String retryName;
    private volatile java.util.Map<String, String> retryConfig;
    private volatile int retriesLeft;
    private volatile int attemptsMade;
    /** 任务链队列：每项是 {脚本名, 源码}。空表示不是链式执行。 */
    private final java.util.ArrayDeque<String[]> chain = new java.util.ArrayDeque<>();
    /** 当前这批是不是链式执行（用于在最后一个脚本结束后打一个明确的收尾标记）。 */
    private volatile boolean chainActive;

    private TaskManager(Context context) {
        host = new ScriptHost(new HelperScreenSource(context), new HelperInput(context));
        history = new RunHistory(context);
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
        return start(name, code, config, 0);
    }

    /**
     * 带配置与重试次数启动。
     *
     * <p>无人值守场景（定时任务、小精灵）里，脚本偶尔会因界面还没加载完而失败，
     * 自动重试几次比人工干预现实得多。重试是同一条脚本重新跑，不是断点续跑。
     */
    public synchronized boolean start(String name, String code,
                                      java.util.Map<String, String> config,
                                      int maxRetries) {
        return startInternal(name, code, config, maxRetries, 0);
    }

    /**
     * 内部启动：{@code attemptsSoFar} 用于在重试时保留累计次数。
     *
     * <p>踩过的坑：重试时如果调回公共 start 并传 0，会把"剩余次数"重置成 0，
     * 结果只重试一次就停（实测 retry=2 只跑了 2 次）。所以单独留一个内部入口。
     */
    private synchronized boolean startInternal(String name, String code,
                                               java.util.Map<String, String> config,
                                               int maxRetries, int attemptsSoFar) {
        if (state != State.IDLE) {
            return false;
        }
        this.retryName = name;
        this.retryCode = code;
        this.retryConfig = config;
        this.retriesLeft = Math.max(0, maxRetries);
        this.attemptsMade = attemptsSoFar;
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
                // 无人值守时人不在现场，事后要能查：什么时候跑的、成没成、为什么失败
                history.add(name, success, costMs, error);

                // 链式执行：上一个成功就接着跑下一个；失败则停链（避免带错往下走）
                if (success && !chain.isEmpty()) {
                    String[] next = chain.poll();
                    Log.i(TAG, "链式执行下一个: " + next[0] + "（剩余 " + chain.size() + "）");
                    startInternal(next[0], next[1],
                            java.util.Collections.<String, String>emptyMap(), 0, 0);
                    return;
                }
                if (!success && !chain.isEmpty()) {
                    int skipped = chain.size();
                    chain.clear();
                    chainActive = false;
                    Log.w(TAG, "链式执行中止：本次失败，跳过后续 " + skipped + " 个脚本");
                    for (Observer o : observers) {
                        o.onTaskLog("warn", "链中止，跳过后续 " + skipped + " 个脚本");
                    }
                }

                if (chainActive && chain.isEmpty()) {
                    // 给 CLI 一个明确的收尾标记：单个脚本的 done 不足以说明整条链跑完了
                    chainActive = false;
                    Log.i(TAG, "chain finished");
                }

                if (!success && retriesLeft > 0) {
                    retriesLeft--;
                    attemptsMade++;
                    final int attemptNo = attemptsMade + 1;
                    Log.w(TAG, "第 " + attemptsMade + " 次失败，2 秒后重试（剩余 " + retriesLeft + " 次）");
                    for (Observer o : observers) {
                        o.onTaskLog("warn", "失败，2 秒后重试（第 " + attemptNo + " 次尝试）");
                    }
                    final int remaining = retriesLeft;
                    final int attemptsSoFar = attemptsMade;
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            () -> startInternal(retryName, retryCode, retryConfig,
                                    remaining, attemptsSoFar), 2000);
                }
            }
        }, config);
        if (!ok) {
            state = State.IDLE;
            notifyState();
        }
        return ok;
    }

    /**
     * 链式运行多个脚本（顺序执行，前一个结束后再跑下一个）。
     *
     * <p>中途失败就停下 —— 后续脚本通常依赖前一个的结果（比如先登录再下单），
     * 失败还硬着头皮跑下去只会造成更乱的现场。
     */
    public synchronized boolean startChain(java.util.List<String[]> items) {
        if (state != State.IDLE) {
            return false;
        }
        if (items == null || items.isEmpty()) {
            return false;
        }
        chain.clear();
        chain.addAll(items);
        chainActive = true;
        String[] first = chain.poll();
        Log.i(TAG, "链式执行：共 " + items.size() + " 个脚本，开始 " + first[0]);
        return startInternal(first[0], first[1], java.util.Collections.<String, String>emptyMap(),
                0, 0);
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
