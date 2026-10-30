package com.whx.automation.script;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 脚本仓库：管理两种脚本来源（对应项目书第六节）。
 *
 * <ul>
 *   <li><b>内置</b>：打包进 APK 的 {@code assets/scripts/*.js}，只读，随 APK 分发；</li>
 *   <li><b>导入</b>：用户通过文件选择器或 adb 放进设备的外部脚本，存于应用私有目录。</li>
 * </ul>
 */
public final class ScriptStore {

    public enum Source {
        BUILTIN, IMPORTED,
        /** 打包进小精灵的绑定脚本（assets/bundle）。 */
        BUNDLE
    }

    /** 小精灵模式下的绑定信息。 */
    public static final class Bundle {
        public final String scriptName;
        public final String appName;
        public final String packageName;
        public final boolean autoStart;

        Bundle(String scriptName, String appName, String packageName, boolean autoStart) {
            this.scriptName = scriptName;
            this.appName = appName;
            this.packageName = packageName;
            this.autoStart = autoStart;
        }
    }

    public static final class Script {
        public final String name;
        public final Source source;
        public final long size;

        public Script(String name, Source source, long size) {
            this.name = name;
            this.source = source;
            this.size = size;
        }

        @Override
        public String toString() {
            return name + (source == Source.BUILTIN ? "（内置）" : "（导入）");
        }
    }

    private final Context context;

    public ScriptStore(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 外部脚本目录：内部存储私有目录，adb 也能推（需要 run-as 或 root）。 */
    public File importDir() {
        File dir = new File(context.getFilesDir(), "scripts");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建脚本目录: " + dir);
        }
        return dir;
    }

    /**
     * 读小精灵的绑定信息。
     *
     * <p>打包器会在 APK 的 {@code assets/bundle/bundle.json} 写入绑定配置，
     * 存在即表示"这是一个小精灵"，界面应当直接跑这一个脚本，不显示脚本列表。
     */
    public Bundle bundle() {
        try (InputStream in = context.getAssets().open("bundle/bundle.json")) {
            String json = readAll(in);
            String script = jsonString(json, "script", "main.js");
            String appName = jsonString(json, "appName", "WHX 小精灵");
            String pkg = jsonString(json, "package", "");
            boolean auto = json.contains("\"autoStart\":true") || json.contains("\"autoStart\": true");
            return new Bundle(script, appName, pkg, auto);
        } catch (IOException e) {
            return null;
        }
    }

    /** 读绑定脚本源码。 */
    public String loadBundleScript(String name) throws IOException {
        try (InputStream in = context.getAssets().open("bundle/" + name)) {
            return readAll(in);
        }
    }

    /** 极简 JSON 取值：只处理扁平的 {"k":"v"}，够用且不引依赖。 */
    private static String jsonString(String json, String key, String fallback) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\s*:\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : fallback;
    }

    /** 列出全部脚本：内置在前，导入在后。 */
    public List<Script> list() {
        List<Script> out = new ArrayList<>();
        try {
            String[] assets = context.getAssets().list("scripts");
            if (assets != null) {
                Arrays.sort(assets);
                for (String name : assets) {
                    if (!name.endsWith(".js")) continue;
                    long size = 0;
                    try (InputStream in = context.getAssets().open("scripts/" + name)) {
                        size = in.available();
                    } catch (IOException ignored) {
                    }
                    out.add(new Script(name, Source.BUILTIN, size));
                }
            }
        } catch (IOException ignored) {
        }
        File[] files = importDir().listFiles((d, n) -> n.endsWith(".js"));
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                out.add(new Script(f.getName(), Source.IMPORTED, f.length()));
            }
        }
        return out;
    }

    /** 读取脚本源码。 */
    public String load(Script script) throws IOException {
        if (script.source == Source.BUILTIN) {
            try (InputStream in = context.getAssets().open("scripts/" + script.name)) {
                return readAll(in);
            }
        }
        File f = new File(importDir(), script.name);
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readAll(in);
        }
    }

    /** 从输入流导入一个脚本（文件选择器用）。 */
    public File importFrom(InputStream in, String displayName) throws IOException {
        String safe = sanitize(displayName);
        if (!safe.endsWith(".js")) {
            safe = safe + ".js";
        }
        File target = new File(importDir(), safe);
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        return target;
    }

    /** 从设备上的 .js 文件导入（adb push 后用）。 */
    public File importFromFile(File src) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src)) {
            return importFrom(in, src.getName());
        }
    }

    public boolean delete(Script script) {
        if (script.source == Source.BUILTIN) {
            return false;
        }
        return new File(importDir(), script.name).delete();
    }

    private static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "imported_" + System.currentTimeMillis();
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, in.available()));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
