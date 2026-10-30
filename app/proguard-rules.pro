# ---------------------------------------------------------------- 反射与动态加载
# helper 通过 app_process + CLASSPATH 被外部加载，类名是硬编码字符串，不能被混淆
-keep class com.whx.automation.helper.** { *; }

# JS 绑定：QuickJS 用动态代理调用接口方法，接口名与方法名必须保留
-keep interface com.whx.automation.script.JsApi { *; }
-keep class com.whx.automation.script.ScriptApi { *; }
-dontwarn app.cash.quickjs.**

# SurfaceControl / DisplayManager 走反射调用系统隐藏 API
-keep class android.view.SurfaceControl { *; }
-keep class android.hardware.display.DisplayManagerGlobal { *; }
-dontwarn android.view.SurfaceControl

# 广播与组件由系统按类名实例化
-keep class com.whx.automation.AutomationService { *; }
-keep class com.whx.automation.TaskStopReceiver { *; }
-keep class com.whx.automation.schedule.** { *; }
-keep class com.whx.automation.MainActivity { *; }
