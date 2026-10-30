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

    /** 只抓一块区域（x,y,w,h），比全屏快；后续识别都在这一块上做。 */
    void refreshRegion(double x, double y, double w, double h);

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

    /** 用设备上的图片文件找图（相对路径默认取应用私有目录）。返回 "x,y" 或 null。 */
    String findImageFile(String path, double threshold);

    /** 用打包进 APK 的模板找图（路径相对 assets，例如 "templates/btn.png"）。 */
    String findImageAsset(String assetPath, double threshold);

    /** 在指定区域里用 assets 模板找图。 */
    String findImageAssetRegion(String assetPath, double threshold,
                               double rx, double ry, double rw, double rh);

    // 触控
    void click(double x, double y);

    void clickHold(double x, double y, int holdMs);

    void longClick(double x, double y, int durationMs);

    void swipe(double x1, double y1, double x2, double y2, int durationMs);

    void key(int keyCode);

    // 应用控制
    /** 应用是否已安装。 */
    boolean appInstalled(String pkg);

    /** 启动应用，返回启动后前台包名。 */
    String launchApp(String pkg);

    /** 强制停止应用（root），返回命令退出码。 */
    String killApp(String pkg);

    /** 当前前台应用包名。 */
    String currentPackage();

    // 界面文本（无障碍）
    /** 当前界面上所有可见文本，格式 "文本|x,y,w,h;文本2|..."。 */
    String texts();

    /** 找文本，返回控件中心 "x,y"；找不到 null。exact=true 为全等匹配。 */
    String findText(String text, boolean exact);

    /** 所有匹配的控件中心，格式 "x1,y1;x2,y2"。 */
    String findTextAll(String text, boolean exact);

    /** 按控件资源 id 定位，返回中心 "x,y" 或 null。 */
    String findId(String viewId);

    /** 滚动查找文本：没找到就往上滑，最多 maxSwipes 次。 */
    String scrollFindText(String text, boolean exact, int maxSwipes);

    /** 找文本并点击（用当前触控后端）。找到返回中心坐标，找不到返回 null。 */
    String clickText(String text, boolean exact);

    /** 无障碍服务是否已启用。 */
    boolean uiReady();

    // 工具
    void sleep(int ms);

    void log(String msg);

    void warn(String msg);

    String info();

    /** 读小精灵配置项（assets/bundle/config.txt 里声明的键）。 */
    String config(String key);

    /** 该配置项是否存在（区分没声明和声明了空值）。 */
    boolean hasConfig(String key);
}
