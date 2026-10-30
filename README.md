# WHX Automation

![tests](https://img.shields.io/badge/tests-71%20passed-brightgreen)
![platform](https://img.shields.io/badge/platform-Android%209%2B-3ddc84)
![language](https://img.shields.io/badge/language-Java-orange)
![engine](https://img.shields.io/badge/JS%20engine-QuickJS-blue)
![apk](https://img.shields.io/badge/APK-3.13%20MB-lightgrey)

> Android 自动化运行平台：**PC 上开发，Android 上运行，可打包成独立 APK 脱离 PC 执行**。
> 全部自研，不复刻任何现有工具的实现。

**当前状态：功能完整可用**（六阶段 + 十三项扩展全部完成并有实测数据）。
唯一未完成项：**ARM64 云手机 / 真机验证**（缺设备）。

📄 最高依据：[项目书](docs/project-charter.md) —— 文档与项目书冲突时以项目书为准。

---

## 定位

不是手机端编程软件，而是**用 JavaScript 控制 Android 设备的自动化运行平台**。

- 开发全在 PC：脚本编写、调试、测试、打包
- Android 只跑：导入、选择、启动、看日志，**不提供编辑功能**
- 引擎独立于界面，可复用
- 产物可打包成**小精灵**：独立包名 / 名称 / 图标 / 参数输入框，装完不需要 PC

## 界面

| 主应用（脚本管理） | 小精灵（独立分发） | 桌面图标 |
| --- | --- | --- |
| ![主应用](docs/images/app-main.png) | ![小精灵](docs/images/sprite.png) | ![桌面](docs/images/launcher.png) |

## 架构（一个 APK，两段进程）

```
PC 开发环境                    设备
┌──────────────┐   adb   ┌──────────────────────────────────────┐
│ 编辑器        │ ──────► │ Automation App（uid=10xxx）           │
│ whx CLI       │  推脚本  │   任务管理 · 脚本仓库 · 日志 · 前台服务 │
│ vision_cli    │  发广播  │   QuickJS · JS API · 无障碍服务        │
│ packer        │  收日志  │                                       │
└──────────────┘         └──────────────┬───────────────────────┘
                                        │ TCP 127.0.0.1（动态端口）
                                        │ 实测 ping 0.06ms
                          ┌─────────────▼───────────────────────┐
                          │ Root Helper（uid=0，su 拉起）        │
                          │  SurfaceControl 截图 · evdev 注入    │
                          │  root shell                          │
                          └─────────────┬───────────────────────┘
                                        ▼
                                 模拟器 / 云手机 / 真机
```

**为什么必须两段进程**：实测确认普通应用进程拿不到 `READ_FRAME_BUFFER`，
`SurfaceControl` 截图会失败；写 `/dev/input/*` 也必须有 root。所以把**纯硬件操作**
隔离到一个最小的 root 进程。它仍是同一个 APK 的一部分，不增加交付物。

**为什么用 TCP 回环而不是 LocalSocket**：抽象命名空间的 unix socket 跨 uid 易撞 SELinux
策略，TCP 回环在所有目标环境一致可用（实测往返 0.06ms）。

## 目录

```
whx-automation/
├─ app/          Automation App：界面 / 任务 / 服务 / 无障碍 / 精灵图标
├─ core/         Automation Core：帧、识别（纯 Java）、差异对比
├─ helper/       Root Helper：SurfaceControl 截图、evdev 注入、root shell
├─ packer/       小精灵打包器（改包名/名称/图标/参数/资源）+ AXML 补丁
├─ tools/        PC 端工具链：whx.py / vision_cli.py / smoke.py
├─ assets 内嵌   内置脚本 + 模板图库（app/src/main/assets）
└─ docs/         项目书、架构、协议、API、路线、打包、发布、验收
```

## 能力与实测

### 性能（雷电 9 / Android 9 / x86_64 / 720×1280）

| 项 | 结果 | 说明 |
| --- | --- | --- |
| 截图 720p（端到端） | **19.6 ms** | 对照 `adb screencap` 141.6ms |
| 区域截图 200×100 | **13.8 ms** | 接近平台下限（合成耗时） |
| 找色（全屏未命中） | **1.96 ms** | 纯 Java，无 JNI |
| 找图 160×120 | **14.2 ms** | 灰化 + 4× 粗筛 + 爬山 + ±2 精修 |
| 点击（按住 30ms） | **32 ms** | 对照 `input tap` 134.6ms |
| 找图 + 点击一轮 | **57.6 ms** | release 构建 |
| JS→Java 桥接 | **0.005 ms/次** | release；debug 为 0.044ms |
| 无障碍读文本 | **4~7 ms** | 节点遍历，比截图快一个量级 |
| 冷启动到可运行 | ~250 ms | |
| APK（release） | **3.13 MB** | |

### 脚本 API

| 类别 | API |
| --- | --- |
| 截图 | `refresh()`、`refresh(x,y,w,h)` |
| 取色/找色 | `getColor`、`isColor`、`findColor`、`findColorRegion`、`findColorAll` |
| 找图 | `findImageAsset`、`findImageAssetRegion`、`findImageFile`、`findImageAuto`（缩放无关） |
| 触控 | `click`、`clickHold`、`longClick`、`swipe`、`key` |
| 界面文本 | `texts`、`findText`、`findTextAll`、`findId`、`clickText`、`scrollFindText`、`uiReady` |
| 应用控制 | `appInstalled`、`launchApp`、`killApp`、`currentPackage` |
| 配置 | `config(key)`、`hasConfig(key)`（小精灵参数，打包时声明） |
| 工具 | `sleep`、`log`、`warn`、`info` |

详见 [docs/api.md](docs/api.md)。

## PC 端工具链

```bash
python tools/smoke.py                            # 一条命令跑完 12 项端到端检查
python tools/whx.py devices                      # 设备/root/分辨率
python tools/whx.py run demo.js                  # 推脚本 → 运行 → 跟日志（失败退出码 1）
python tools/whx.py run a.js b.js c.js           # 任务链：顺序执行，失败即停
python tools/whx.py run demo.js --config x=100   # 传运行时配置
python tools/whx.py watch dist/my.js             # 监听改动，存盘即跑
python tools/whx.py schedule add demo.js --time 08:30   # 设备端定时（触发精度 6ms）
python tools/whx.py history                      # 运行历史（成败/耗时/错误）
python tools/whx.py ui enable                    # 启用无障碍（读界面文字）
python tools/whx.py shot dist/a.png              # 截图到 PC
python tools/whx.py pack demo.js --label 小鱼 --pkg com.whx.sprite.demo --icon 2
python tools/vision_cli.py template --tpl t.png  # PC 上验证找图命中位置
```

## 构建与运行

```bash
./gradlew assembleDebug                 # 开发包
./gradlew assembleRelease               # 发布包（R8 优化，桥接快约 10×）
./gradlew :core:test :helper:test :app:testDebugUnitTest    # 64 个单元测试
adb install -r app/build/outputs/apk/release/app-release.apk
```

依赖：JDK 21、Android SDK（platform 34 + build-tools 34）、仓库自带 Gradle wrapper。
构建依赖走阿里云镜像（本机直连 Maven Central 的 TLS 会被中断），见 `settings.gradle`。

## 文档

| 文档 | 内容 |
| --- | --- |
| [project-charter.md](docs/project-charter.md) | 项目书（最高依据） |
| [architecture.md](docs/architecture.md) | 架构决策与实测依据 |
| [protocol.md](docs/protocol.md) | 协议：实现现状 + 设计稿 |
| [api.md](docs/api.md) | 脚本 API |
| [cli.md](docs/cli.md) | PC 工具链用法 |
| [packer.md](docs/packer.md) | 小精灵打包（包名/名称/图标/参数/资源） |
| [release.md](docs/release.md) | 发布壳、签名、R8 keep 规则 |
| [acceptance.md](docs/acceptance.md) | 验收清单（可重复执行） |
| [environment.md](docs/environment.md) | 本机环境与踩坑记录 |
| [roadmap.md](docs/roadmap.md) | 各阶段实测数据与踩坑 |

## 几个被实测推翻的直觉

记录在此，避免后来者重走：

1. **「触摸轴是横屏坐标，必须交换」—— 错。**
   框架对每轴各自按 `absMax` 缩放到屏幕对应维度，恒等映射即可。
   按"交换"处理后坐标落到窗口外，现象是**只收到 ACTION_DOWN、收不到 ACTION_UP**，
   按钮不响应且不报错。
2. **「输出用 SharedMemory 会更快」—— 不。**
   实测像素传输 3.5MB ≈ 4.9ms（约 730MB/s 回环），共享内存省不下多少。
3. **「adb screencap 是最快的截图方式」—— 不。**
   141.6ms vs 进程内 19.6ms。
4. **「小精灵的图标得改 resources.arsc」—— 不必。**
   用 `activity-alias` 多个启动入口，运行时切换启用项即可。
5. **「release 只是小一点」—— 不止。**
   R8 之后 JS→Java 桥接从 0.044ms 降到 0.005ms，快约一个数量级。

## 已知限制

- **ARM64 / API 29+ 未实测**：`SurfaceControl.screenshot` 在 API 30+ 被 `captureDisplay` 取代，
  代码已按版本分支，但本机只有 API 28。
- **小精灵图标是 4 套内置**（绿/蓝/红/紫），暂不支持任意图片。
- **签名默认口令写在构建脚本**（仓库公开）：正式发布前必须用 `WHX_RELEASE_PASS` 覆盖。
- 虚拟化输入的部分云手机 `getevent` 看不到触摸节点，需单独适配。
- 未附开源许可证：默认保留所有权利；如需授权使用请联系作者。
