# 发布壳（release）

debug 壳不能对外分发：没签名保护、没有 R8 优化、体积和速度都差一截。
`assembleRelease` 已经是可用状态。

```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

## 实测对比（雷电 9 / 720×1280）

| 项 | debug | release |
| --- | --- | --- |
| APK 体积 | 3.17 MB | **3.13 MB** |
| 500 次取色（JS→Java 桥接） | 27 ms | **2~3 ms** |
| 找图+点击一轮 | 58.4 ms | 57.6 ms |
| 截图 / 点击 / 识别 | 全部正常 | 全部正常 |

桥接从 27ms 降到 2ms 是 R8 内联与去虚化的功劳 —— **这一步跑了 release 才发现**，
debug 下测出的 0.044ms/次调用开销其实是"没优化的版本"，真实性能还要好一个数量级。

## 签名

密钥在 `~/.whx/release.jks`（**故意放在仓库外**）。口令不写死，按优先级取值：

```
环境变量 WHX_RELEASE_PASS  >  -PwhxReleasePass=…  >  默认值（仅便于首次本地构建）
别名同理：WHX_RELEASE_ALIAS，默认 release
```

> ⚠️ 本仓库已公开。默认口令写在构建脚本里只是为了让首次构建能跑通，
> **正式发布前必须改成自己的口令**（`export WHX_RELEASE_PASS=…`）并妥善备份密钥。

首次构建前生成密钥：

```bash
mkdir -p ~/.whx
keytool -genkeypair -keystore ~/.whx/release.jks -alias release \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass whxrelease -keypass whxrelease \
  -dname "CN=WHX Release, OU=app, O=WHX, C=CN"
```

> 正式发布前请换掉默认口令并备份密钥：**密钥丢了，已安装的用户就再也无法覆盖升级。**

## R8 必须保留的东西（app/proguard-rules.pro）

| 保留对象 | 原因 |
| --- | --- |
| `com.whx.automation.helper.**` | helper 由 `app_process` 按类名字符串加载，混淆后就找不到 |
| `com.whx.automation.script.JsApi` / `ScriptApi` | QuickJS 用动态代理调接口方法，方法名被改就调不到 |
| `SurfaceControl` / `DisplayManagerGlobal` | 截图走反射调用系统隐藏 API |
| 各 Service / Receiver | 系统按类名实例化组件 |

**验证方式不是"看日志没报错"，而是跑通全链路**：装 release 包 →
`python tools/whx.py run demo.js` → 截图、找色、点击都成功，才说明反射与绑定都没被裁掉。

## 用 release 壳打小精灵

```bash
python packer/make_sprite.py \
    --shell app/build/outputs/apk/release/app-release.apk \
    --script demo.js --label "小鱼示例" --pkg com.whx.sprite.rel \
    --config packer/sample-config.txt \
    --out dist/xiaoyu-rel.apk
```

实测：3.18 MB，安装后配置项、截图、找色、点击全部正常。

## 注意

- debug 与 release 签名不同，**必须卸载旧包**才能安装另一个（`adb uninstall com.whx.automation`）。
  卸载会导致应用私有目录（导入的脚本、定时任务）被清空。
- release 包首次启动会重新弹一次 root 授权（包名相同但签名不同，视为新应用）。
