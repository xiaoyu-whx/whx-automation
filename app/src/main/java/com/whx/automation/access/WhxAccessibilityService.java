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
        /** 可点击目标中心：优先自己（可点击时），否则取最近的可点击祖先。
         *  很多列表项的文本本身不可点，点它没反应，必须点整行。 */
        public final int clickX;
        public final int clickY;

        Node(String text, int x, int y, int w, int h, boolean clickable, String viewId,
             int clickX, int clickY) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.clickable = clickable;
            this.viewId = viewId;
            this.clickX = clickX;
            this.clickY = clickY;
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
            collect(root, out, 0, null);
        } finally {
            root.recycle();
        }
        return out;
    }

    private void collect(AccessibilityNodeInfo node, List<Node> out, int depth,
                         Rect clickableAncestor) {
        if (node == null || depth > 40 || out.size() > 500) {
            return;
        }
        Rect self = new Rect();
        node.getBoundsInScreen(self);
        Rect clickTarget = node.isClickable() ? self : clickableAncestor;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String label = text != null && text.length() > 0 ? text.toString()
                : (desc != null ? desc.toString() : "");
        if (!label.isEmpty() && self.width() > 0 && self.height() > 0) {
            Rect target = clickTarget != null ? clickTarget : self;
            out.add(new Node(label.trim(), self.left, self.top, self.width(), self.height(),
                    node.isClickable(), node.getViewIdResourceName(),
                    target.centerX(), target.centerY()));
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                collect(child, out, depth + 1, clickTarget);
            } finally {
                if (child != null) {
                    child.recycle();
                }
            }
        }
    }

    /**
     * 按资源 id 查找（例如 com.android.settings:id/search），比文字更稳。
     *
     * <p>单独走一遍遍历：{@link #nodes()} 只收集"有文本"的节点（容器类没有文本），
     * 但按 id 定位时容器恰恰是常见目标，所以这里不受那个过滤限制。
     */
    public Node findId(String viewId) {
        if (viewId == null || viewId.isEmpty()) {
            return null;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        try {
            return findIdIn(root, viewId, 0, null);
        } finally {
            root.recycle();
        }
    }

    private Node findIdIn(AccessibilityNodeInfo node, String viewId, int depth,
                          Rect clickableAncestor) {
        if (node == null || depth > 40) {
            return null;
        }
        Rect self = new Rect();
        node.getBoundsInScreen(self);
        Rect clickTarget = node.isClickable() ? self : clickableAncestor;

        String id = node.getViewIdResourceName();
        if (viewId.equals(id) && self.width() > 0 && self.height() > 0) {
            Rect target = clickTarget != null ? clickTarget : self;
            CharSequence label = node.getText() != null && node.getText().length() > 0
                    ? node.getText() : node.getContentDescription();
            return new Node(label == null ? "" : label.toString().trim(),
                    self.left, self.top, self.width(), self.height(),
                    node.isClickable(), id, target.centerX(), target.centerY());
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                Node hit = findIdIn(child, viewId, depth + 1, clickTarget);
                if (hit != null) {
                    return hit;
                }
            } finally {
                if (child != null) {
                    child.recycle();
                }
            }
        }
        return null;
    }

    /** 找到所有匹配文本的节点（页面上可能有多个「确定」）。 */
    public List<Node> findAllText(String text, boolean exact) {
        List<Node> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return hits;
        }
        for (Node n : nodes()) {
            boolean hit = exact ? n.text.equals(text) : n.text.contains(text);
            if (hit) {
                hits.add(n);
            }
        }
        return hits;
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
