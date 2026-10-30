# autojs-xiaoyu

> 面向 **已 root 的安卓模拟器 / 云手机** 的自动化引擎。PC 写脚本，设备端 root 直连执行。
> 目标：点击 3~10ms、截图 20~40ms —— 比 AutoJS6 / AutoPro 快一个数量级。

**当前状态：规划阶段（M0）。仓库里只有文档，没有代码。**

---

## 一句话定位

AutoJS6 把脚本跑在手机上但用无障碍服务，慢且不稳；AutoPro 类工具偏录制回放。
**注意：本项目对标的是「同一件事」，不是「同一套写法」** —— API 完全自研，不复刻 AutoJS6 的语义。
本项目走第三条路：**设备端放一个极小的 root 常驻 Agent（只管截图和注入），PC 端放完整的
脚本引擎、图像识别和调试工具**。root 保证性能上限，PC 保证开发体验。

## 硬约束（决定了所有设计）

| 约束 | 取值 | 影响 |
| --- | --- | --- |
| root | **必须有**，不做免 root 兜底 | 可用 `sendevent` 注入、直读 `/dev/graphics/fb0` 截图 |
| 目标设备 | 雷电模拟器 9、云手机（后期） | 无真机适配负担，但要走 ADB over TCP |
| 分辨率 | **任意**（自己用 720×1280） | 模板图带来源分辨率标注，匹配时按比例缩放；同分辨率时缩放到 1.0，最快 |
| 系统版本 | Android 9 ~ 13 | `minSdk` 至少覆盖 9（API 28） |

## 架构

```
scripts/*.js  ──stdio JSON-RPC──▶  engine/ (Python)  ──TCP 二进制──▶  agent/ (Kotlin, root)
   JS 脚本                        OpenCV 识别 + 调度              截图 / sendevent 注入
```

- **agent/**：设备端 APK，root 常驻，只做三件事 —— 截图、注入触摸/按键、透传 shell。
- **engine/**：PC 端 Python，负责图像识别、坐标计算、流程编排、调试录制、CLI。
- **runtime/**：PC 端 Node，提供自研 JS API（帧优先 + 动作带断言），脚本在这里跑。

详细设计见 [docs/architecture.md](docs/architecture.md)，
通信协议见 [docs/protocol.md](docs/protocol.md)，
里程碑见 [docs/roadmap.md](docs/roadmap.md)。

## 目录（规划，尚未创建）

```
autojs-xiaoyu/
├─ agent/                    Android 工程：com.xiaoyu.agent
│  ├─ app/src/main/java/com/xiaoyu/agent/
│  │  ├─ AgentService.kt     TCP 服务、连接管理、请求分发
│  │  ├─ Protocol.kt         帧编解码（与 docs/protocol.md 一一对应）
│  │  ├─ ScreenGrabber.kt    截图：fb0 直读 / screencap 回退
│  │  ├─ TouchInjector.kt    sendevent 触摸注入（Protocol B，支持多点）
│  │  ├─ KeyInjector.kt      按键注入
│  │  ├─ InputSource.kt      /dev/input 探测：找触摸屏、解析轴范围
│  │  └─ RootShell.kt        libsu 常驻 root shell
│  └─ app/src/main/AndroidManifest.xml
├─ engine/                   Python 包：xiaoyu
│  ├─ xiaoyu/protocol.py     帧编解码 + 命令码常量
│  ├─ xiaoyu/client.py       Agent 客户端：连接、请求、超时、重连
│  ├─ xiaoyu/device.py       设备语义层：tap/swipe/key/screenshot
│  ├─ xiaoyu/vision.py       OpenCV 匹配（单尺度，定分辨率优化）
│  ├─ xiaoyu/rpc.py          JS ↔ Python 的 JSON-RPC
│  ├─ xiaoyu/trace.py        执行轨迹录制（后期）
│  ├─ xiaoyu/cli.py          命令行
│  └─ pyproject.toml
├─ runtime/                  Node：JS 运行时
│  ├─ src/api.js             自研 API：帧 / 定位器 / 动作断言
│  ├─ src/bridge.js          JSON-RPC 客户端
│  └─ src/index.js           脚本入口
├─ scripts/                  示例脚本
├─ assets/                   模板图库（每张带 .json 标注来源分辨率）
├─ tools/                    装机 / 连设备 / 取模板 脚本
└─ docs/
```

## 里程碑

| 阶段 | 内容 | 验收标准 |
| --- | --- | --- |
| **M0** 规划 | 协议、架构、路线 | 本目录文档评审通过（当前） |
| M1 连通 | agent `ping`+`screenshot`，engine `xiaoyu shot` | 1280×720 截图 ≤ 60ms |
| M2 注入 | `sendevent` 点击/滑动/按键 | 单次点击 ≤ 15ms，能稳定 20 次/秒连点 |
| M3 识别 | 找图、找色、waitFor、JS 脚本层跑通 | 脚本"找到图标并点击"全链路成功 |
| M4 调试 | 轨迹录制、HTML 回放、截图 diff | 能定位"点了没生效"的那一步 |
| M5 多开 | 多设备池、任务队列、云手机接入 | 单 PC 同时控 4 台 |

## 环境现状

见 [docs/environment.md](docs/environment.md)。一句话：JDK 21 ✅、ADB ✅、雷电模拟器 ✅；
**缺 Gradle wrapper 和 Android SDK（M1 开始前必须装）**。

## 免责

仅用于自有设备、自有应用的自动化与测试。请遵守目标平台的服务条款。
