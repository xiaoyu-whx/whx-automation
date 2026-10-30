# 小精灵打包器

把单个脚本绑定进"壳 APK"，产出一个**独立可分发的小精灵**：改包名、改应用名、打开即跑。

```bash
python packer/make_sprite.py \
    --shell app/build/outputs/apk/debug/app-debug.apk \
    --script app/src/main/assets/scripts/demo.js \
    --label "小鱼示例" \
    --pkg com.whx.sprite.demo \
    --out dist/xiaoyu-demo.apk
```

实测：**打包 0.77 秒**，产物 3.2MB，`com.whx.automation` / `com.whx.sprite.demo` /
`com.whx.sprite.task` 三个包可在同一台设备共存。

## 它做了什么

| 步骤 | 手段 |
| --- | --- |
| 1. 绑定脚本 | 往 APK 写入 `assets/bundle/bundle.json` + `assets/bundle/<script>` |
| 2. 改包名 | 就地改写 `AndroidManifest.xml`（**二进制 AXML 字符串池**） |
| 3. 改应用名 | 同上，替换 `android:label` 的字符串 |
| 4. 重签 | `zipalign -p 4` + `apksigner sign`（首次自动用 keytool 生成密钥） |

**不重新编译**：壳的代码完全不动，只换资源与清单，所以是秒级出包而不是分钟级。

## 为什么自己写 AXML 解析

不想依赖 apktool（GitHub 下载不稳），而 AXML 的字符串池是**自包含**的：
替换字符串后重新序列化字符串池、拼接后续字节、修正总长度即可，不需要理解其余节点。

## AXML 的两个格式坑（都踩过）

**坑 1：字符串池有两种编码，长度前缀不同**

```
flags & 0x100（UTF8_FLAG）→ 变长整数(字节长度) + 变长整数(UTF-16 长度) + UTF-8 字节 + 0x00
否则（UTF-16 池）        → u16 小端(码元数) + UTF-16LE 码元 + 0x0000
```

我们的 manifest 是 **UTF-16 池**。一开始把 UTF-16 也按变长整数读写，结果字符串池整体偏移一个字节，
`apksigner` 直接报错退出：

```
java.lang.IllegalArgumentException
  at AndroidBinXmlParser$StringPool.getLengthPrefixedUtf16EncodedString
```

**坑 2：自检方式 —— 往返字节相等**

判断编码器对不对，最省事的办法是「解析 → 重新编码 → 与原字节比较」：

```python
strings, end, meta = parse_string_pool(raw, 8)
assert build_string_pool(strings, meta["flags"], ...) == raw[8:end]
```

这一行断言能立刻暴露编码差异（我们第一次跑就是 1972 → 1928 字节，差 44 字节 = 每个字符串少一个长度字节）。

## App 侧的小精灵模式

`ScriptStore.bundle()` 读 `assets/bundle/bundle.json`，存在即进入小精灵模式：

- 标题显示 `appName`，副标题显示绑定脚本名
- 隐藏脚本列表与「导入」按钮
- `autoStart=true` 时打开即运行（`--no-autostart` 可关闭）

```json
{
  "script": "demo.js",
  "appName": "小鱼示例",
  "package": "com.whx.sprite.demo",
  "version": "1.0.0",
  "autoStart": true
}
```

## 自定义图标（--icon 1..4）

```bash
python packer/make_sprite.py ... --icon 2 --out dist/xiaoyu-icon.apk
```

壳里内置了 4 套图标（绿/蓝/红/紫，白 W），打包时选一个即可。

**为什么不用改 resources.arsc**：清单里 `android:icon` 存的是**资源 id（int）**，
不是字符串，字符串池替换对它无效。改用 `activity-alias`：
壳里声明 `.Launcher`（默认）+ `.Sprite1..4` 五个启动入口，各自的图标写在清单里，
运行时用 `PackageManager.setComponentEnabledSetting` **只启用其中一个**。

实测（`com.whx.sprite.icon`，`--icon 2`）：

```
WHX/Icon: 图标切换为 sprite2
桌面查询结果：com.whx.sprite.icon/com.whx.automation.Sprite2   ← 只有这一个入口
```

桌面截图确认显示蓝色 W 图标 + 名称「小鱼示例」。

**踩坑**：组件类名不能用 `getPackageName()` 拼 —— 包名被改后是 `com.whx.sprite.icon`，
但清单里的组件是 AGP 按 **namespace** 展开的绝对名 `com.whx.automation.Sprite2`，
拼错会报 `Component class … does not exist`。

## 配置项（不写死坐标）

打包时用 `--config` 指定参数文件，小精灵打开后自动生成输入框，脚本用 `config('键')` 读：

```bash
python packer/make_sprite.py ... --config packer/sample-config.txt
```

`config.txt` 一行一个字段，竖线分隔（**顺序稳定、人肉可编辑、无需 JSON 依赖**）：

```
# 键 | 显示名 | 类型 | 默认值
x | 点击 X 坐标 | number | 360
y | 点击 Y 坐标 | number | 640
delay | 等待秒数 | number | 1
debug | 打印详细日志 | switch | false
```

脚本里：

```js
log('x = ' + config('x'));
click(Number(config('x')), Number(config('y')));
if (hasConfig('debug')) { log('开调试'); }
```

**实测**：生成的界面显示「参数 / 点击 X 坐标 / 点击 Y 坐标 / 等待秒数 / 打印详细日志」四个控件；
把 X 改成 100 后运行，日志为 `配置: {x=100, y=640, delay=1, debug=false}`，
脚本按新值点击 `(100,640)`。

**失败要吵**：`config('拼错的键')` 会抛异常并列出可用键，不会静默返回空串；
数字型字段填了非数字时拒绝运行并提示具体字段名。

## 打包模板图片（--assets）

找图要模板，模板得跟着小精灵走：

```bash
python packer/make_sprite.py ...     --assets dist/tpl_assets --out dist/xiaoyu-img.apk
```

`--assets` 目录会被**递归**拷进 APK 的 `assets/` 下并保持相对路径，
脚本里就能 `findImageAsset('templates/title.png', 0.9)`。

**工作流**（踩过坑，顺序不能错）：

```bash
python tools/whx.py shot dist/sprite.png                                  # 1. 截当前小精灵界面
python tools/vision_cli.py --shot dist/sprite.png crop     --region 30,140,400,70 --out dist/tpl_assets/templates/title.png      # 2. 裁模板
python tools/vision_cli.py --shot dist/sprite.png template     --tpl dist/tpl_assets/templates/title.png                             # 3. 先验证能命中
python packer/make_sprite.py ... --assets dist/tpl_assets                 # 4. 打包
```

> 实测教训：我第一次用的是**开发包界面**截的模板，装进小精灵后匹配失败 ——
> 因为小精灵的标题是它自己的名字，像素根本不一样。
> **模板必须从"将要匹配的那个界面"上裁。**

## 失败自动重试

配置里声明 `retry` 就能让脚本失败后自动重跑（无人值守场景很关键）：

```
retry | 失败重试次数 | number | 2
```

实测 `retry=2`：共 3 次尝试（初始 + 2 次重试），间隔 2 秒，日志逐次提示剩余次数。
样例见 `packer/sample-retry-config.txt`。

## 坑：重复 ZIP 条目导致签名失败

`--assets` 里的文件如果壳 APK 里已经有了（例如 `assets/templates/title.png`），
直接再写一次会让 zip 出现同名条目，apksigner 直接拒绝：

```
ApkFormatException: Multiple ZIP entries with the same name: assets/templates/title.png
```

处理方式：先把 `--assets` 收集成字典，拷壳时**跳过将被覆盖的条目**，最后统一写入。

## 已知限制

- **图标还不能换**：`android:icon` 指向资源表，改它要重编 `resources.arsc`，留到后续版本。
- **签名密钥是自动生成的**：`packer/.keys/sprite.jks`（已在 .gitignore 中忽略）。
  正式分发要自己备份这个密钥，否则后续版本无法覆盖安装。
- **壳是 debug APK**：正式发布应改用 release 壳（`minifyEnabled` 会改变行为，需要单独验证）。
