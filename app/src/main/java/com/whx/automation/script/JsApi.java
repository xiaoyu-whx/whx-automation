package com.whx.automation.script;

/**
 * 暴露给 JavaScript 的方法集合。
 *
 * <p>必须定义成**接口**：QuickJS 的 `set(name, type, instance)` 只接受接口，
 * 运行时用动态代理把 JS 调用转到实现类（实测报错：Only interfaces can be bound）。
 *
 * <p>参数只用 double / int / String / boolean，不收 JS 对象，避免对象编组开销。
 */
public interface JsApi {

    // 帧
    void refresh();

    int screenWidth();

    int screenHeight();

    int frameAge();

    // 取色
    String getColor(double x, double y);

    boolean isColor(double x, double y, String hex, int tolerance);

    // 找色
    String findColor(String hex, int tolerance);

    String findColorRegion(String hex, int tolerance, double rx, double ry, double rw, double rh);

    String findColorAll(String hex, int tolerance, int max);

    // 找图
    String findImage(String templateName, double threshold);

    // 触控
    void click(double x, double y);

    void clickHold(double x, double y, int holdMs);

    void longClick(double x, double y, int durationMs);

    void swipe(double x1, double y1, double x2, double y2, int durationMs);

    void key(int keyCode);

    // 工具
    void sleep(int ms);

    void log(String msg);

    void warn(String msg);

    String info();
}
