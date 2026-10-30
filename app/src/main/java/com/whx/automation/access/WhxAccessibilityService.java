package com.whx.automation.access;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 无障碍服务：把界面上可见的文本与位置暴露给脚本。
 *
 * <p>为什么需要它：找色/找图只能按像素办事，而真实脚本里更常见的是"找到『确定』并点击"。
 * 有了节点树，文字、控件类型、可点击性都是白给的，也不受分辨率影响。
 *
 * <p>取数据是**按需遍历**（调用时现取 `getRootInActiveWindow`），
 * 不做事件缓存 —— 少一层状态，就不会出现"缓存过期导致点到旧位置"。
 */
public class WhxAccessibilityService extends AccessibilityService {

    private static final String TAG = "WHX/A11y";
    private static volatile WhxAccessibilityService instance;

    /** 无障碍未启用时脚本要给明确提示，而不是静默失败。 */
    public static WhxAccessibilityService get() {
        return instance;
    }

    public static boolean isReady() {
        return instance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "无障碍服务已连接");
    }

    @Override
    public void onDestroy() {
        instance = null;
        Log.i(TAG, "无障碍服务已断开");
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不缓存事件：脚本调用时才遍历，避免点到过期位置
    }

    @Override
    public void onInterrupt() {
    }

    /** 界面上的一条文本。 */
    public static final class Node {
        public final String text;
        public final int x;
        public final int y;
        public final int w;
        public final int h;
        public final boolean clickable;
        public final String viewId;

        Node(String text, int x, int y, int w, int h, boolean clickable, String viewId) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.clickable = clickable;
            this.viewId = viewId;
        }

        public int centerX() {
            return x + w / 2;
        }

        public int centerY() {
            return y + h / 2;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "%s @(%d,%d,%d×%d)%s",
                    text, x, y, w, h, clickable ? " 可点" : "");
        }
    }

    /** 遍历当前界面，按文档顺序返回带文本的节点。 */
    public List<Node> nodes() {
        List<Node> out = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return out;
        }
        try {
            collect(root, out, 0);
        } finally {
            root.recycle();
        }
        return out;
    }

    private void collect(AccessibilityNodeInfo node, List<Node> out, int depth) {
        if (node == null || depth > 40 || out.size() > 500) {
            return;
        }
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String label = text != null && text.length() > 0 ? text.toString()
                : (desc != null ? desc.toString() : "");
        if (!label.isEmpty()) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.width() > 0 && r.height() > 0) {
                out.add(new Node(label.trim(), r.left, r.top, r.width(), r.height(),
                        node.isClickable(), node.getViewIdResourceName()));
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                collect(child, out, depth + 1);
            } finally {
                if (child != null) {
                    child.recycle();
                }
            }
        }
    }

    /** 找到第一条匹配文本的节点；exact=false 时按包含匹配。 */
    public Node findText(String text, boolean exact) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (Node n : nodes()) {
            boolean hit = exact ? n.text.equals(text) : n.text.contains(text);
            if (hit) {
                return n;
            }
        }
        return null;
    }
}
