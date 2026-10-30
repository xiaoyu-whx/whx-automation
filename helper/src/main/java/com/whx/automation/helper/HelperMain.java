package com.whx.automation.helper;

import android.os.Build;

/**
 * Root Helper 的入口（阶段一仅占位）。
 *
 * 运行方式（阶段二接入）：
 * <pre>
 *   su -c "CLASSPATH=/data/local/tmp/whx-helper.jar app_process /data/local/tmp com.whx.automation.helper.HelperMain"
 * </pre>
 *
 * 职责边界（架构文档 §1.2）：只做截图、注入、root shell，不认识脚本与视觉。
 */
public final class HelperMain {

    public static final String VERSION = "0.1.0";

    public static void main(String[] args) {
        System.out.println("WHX Helper " + VERSION);
        System.out.println("uid=" + android.os.Process.myUid());
        System.out.println("sdk=" + Build.VERSION.SDK_INT
                + " release=" + Build.VERSION.RELEASE
                + " abi=" + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?"));
        System.out.println("阶段一：仅验证 app_process 可加载本类，截图与注入在阶段二接入");
    }

    private HelperMain() {
    }
}
