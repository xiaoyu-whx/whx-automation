package com.whx.automation.script;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小精灵的配置项。
 *
 * <p>没有配置项的小精灵只能靠写死坐标，换台分辨率不同的设备就废了。这里让打包者声明
 * 参数（例如"点击 X 坐标""等待秒数"），小精灵打开后自动生成输入框，脚本用
 * {@code config('x')} 读值 —— 不需要改代码就能适配不同机器。
 *
 * <p>文件格式（{@code assets/bundle/config.txt}），一行一个字段，竖线分隔：
 * <pre>
 *   # 键 | 显示名 | 类型 | 默认值
 *   x | 点击 X 坐标 | number | 360
 *   y | 点击 Y 坐标 | number | 640
 *   delay | 等待秒数 | number | 2
 *   debug | 打印详细日志 | switch | false
 * </pre>
 * 用这种格式而不是 JSON：顺序稳定（JSON 对象顺序不保证）、人肉可编辑、解析无需依赖。
 */
public final class ScriptConfig {

    public enum Type {
        TEXT, NUMBER, SWITCH
    }

    public static final class Field {
        public final String key;
        public final String label;
        public final Type type;
        public final String defaultValue;

        Field(String key, String label, Type type, String defaultValue) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.defaultValue = defaultValue;
        }
    }

    private static final String PREF = "whx_config";

    private final List<Field> fields;
    private final Map<String, String> values = new LinkedHashMap<>();
    private Context context;

    private ScriptConfig(List<Field> fields) {
        this.fields = fields;
        for (Field f : fields) {
            values.put(f.key, f.defaultValue);
        }
    }

    /**
     * 从 assets/bundle/config.txt 读；不存在时返回空配置（普通模式）。
     *
     * <p>会用上次保存的值覆盖默认值 —— 用户改过的参数不该在重启后又变回默认。
     */
    public static ScriptConfig load(Context context) {
        ScriptConfig config;
        try (InputStream in = context.getAssets().open("bundle/config.txt")) {
            config = parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            config = new ScriptConfig(new ArrayList<>());
        }
        config.context = context.getApplicationContext();
        config.restoreSaved();
        return config;
    }

    /** 用上次保存的值覆盖默认值（只覆盖确实保存过的键）。 */
    private void restoreSaved() {
        if (context == null || fields.isEmpty()) {
            return;
        }
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        for (Field f : fields) {
            String saved = prefs.getString("v_" + f.key, null);
            if (saved != null) {
                values.put(f.key, saved);
            }
        }
    }

    /** 记住当前值，下次打开小精灵时沿用。 */
    public void save() {
        if (context == null || fields.isEmpty()) {
            return;
        }
        android.content.SharedPreferences.Editor editor =
                context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        for (Field f : fields) {
            editor.putString("v_" + f.key, get(f.key));
        }
        editor.apply();
    }

    static ScriptConfig parse(BufferedReader reader) throws IOException {
        List<Field> fields = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\|");
            if (parts.length < 2) {
                continue;
            }
            String key = parts[0].trim();
            String label = parts[1].trim();
            String typeText = parts.length > 2 ? parts[2].trim().toLowerCase() : "text";
            String def = parts.length > 3 ? parts[3].trim() : "";
            Type type;
            switch (typeText) {
                case "number":
                    type = Type.NUMBER;
                    break;
                case "switch":
                case "bool":
                    type = Type.SWITCH;
                    break;
                default:
                    type = Type.TEXT;
                    break;
            }
            if (key.isEmpty()) {
                continue;
            }
            fields.add(new Field(key, label.isEmpty() ? key : label, type, def));
        }
        return new ScriptConfig(fields);
    }

    public List<Field> fields() {
        return fields;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /** 设置某个键的值（值非法时保留默认）。 */
    public void set(String key, String value) {
        if (!values.containsKey(key)) {
            return;
        }
        values.put(key, value == null ? "" : value.trim());
    }

    public String get(String key) {
        String v = values.get(key);
        return v == null ? "" : v;
    }

    public Map<String, String> snapshot() {
        return new LinkedHashMap<>(values);
    }

    /** 校验并返回错误信息；null 表示没问题。 */
    public String validate() {
        for (Field f : fields) {
            String v = get(f.key);
            if (f.type == Type.NUMBER) {
                if (v.isEmpty()) {
                    return f.label + " 不能为空";
                }
                try {
                    Double.parseDouble(v);
                } catch (NumberFormatException e) {
                    return f.label + " 必须是数字，当前是「" + v + "」";
                }
            }
        }
        return null;
    }
}
