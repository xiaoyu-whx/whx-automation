# 路线图

每个阶段都以**可测量的验收标准**结束，不达标准不进下一阶段。

## M0 · 规划（当前）

**产出**：`README.md`、`docs/architecture.md`、`docs/protocol.md`、`docs/roadmap.md`、
`docs/environment.md`、`docs/api.md`

**验收**：协议文档能直接照着写代码，没有"到实现时再定"的坑。

---

## M1 · 打通链路（只做截图，不做点击）

| 任务 | 说明 |
| --- | --- |
| 装构建环境 | Android SDK + Gradle wrapper（见 environment.md） |
| `agent/` 工程骨架 | 空 Activity + 前台 Service，能装进雷电 9 并常驻 |
| `Protocol.kt` | 帧编解码 + 单元测试（Kotlin 侧 JVM test，不需要设备） |
| `RootShell.kt` | libsu 常驻 `su`，`id` 返回 uid=0 |
| `ScreenGrabber.kt` | fb0 直读 + screencap 回退 |
| `AgentService` | 监听 7911，实现 `ping` / `info` / `screenshot` |
| `engine/client.py` | 连接、请求、seq 匹配、超时、重连 |
| `engine/cli.py` | `xiaoyu ping` / `xiaoyu info` / `xiaoyu shot -o a.png` |

**验收**
- `xiaoyu ping` 往返 < 5ms
- `xiaoyu shot` 连续 100 次，**P50 ≤ 45ms，P99 ≤ 90ms**，无一次全黑/撕裂
- 杀掉 agent 进程后，`xiaoyu shot` 能自动重连成功

---

## M2 · 注入（性能的关键一步）

| 任务 | 说明 |
| --- | --- |
| `InputSource.kt` | `getevent -p` 解析触摸设备、轴范围、协议类型 |
| `TouchInjector.kt` | Protocol B：单点按下/移动/抬起，多指 slot 分配 |
| `KeyInjector.kt` | 按键节点直写，回退 `input keyevent` |
| `engine/device.py` | `tap / longPress / swipe / key / text` 语义层 |
| CLI | `xiaoyu tap x y` / `swipe` / `text` / `key` |

**验收**
- 单次 `tap` 端到端 **P50 ≤ 12ms**
- 连点 20 次/秒，目标 App 100% 收到（用计数器 App 或日志验证）
- 滑动轨迹与请求点吻合：录 `getevent` 比对，误差 ≤ 2 帧
- 多指（2 指）能触发目标 App 的缩放

---

## M3 · 识别 + 脚本层（第一次"能用"）

| 任务 | 说明 |
| --- | --- |
| `engine/vision.py` | 模板匹配（分辨率自适应缩放 + ±10% 兜底）、找色、区域裁剪 |
| `engine/rpc.py` | JS ↔ Python 的 JSON-RPC |
| `runtime/` | 自研 API：帧 / 定位器 / 动作断言 / waitFor，脚本入口，顶层 await |
| `scripts/` | 3 个示例：找图点击、等待页面、批量填表 |
| `tools/` | `shot` 取模板、批量裁剪小工具 |

**验收**
- `await findImage('btn.png')` 单帧匹配 ≤ 15ms
- 示例脚本在雷电 9 上跑通"启动 App → 等首页 → 点按钮 → 校验页面"
- 脚本异常时退出码非 0 且有可读堆栈

---

## M4 · 调试（这个项目的差异化）

| 任务 | 说明 |
| --- | --- |
| `engine/trace.py` | 每步截图 + 变化率 + jsonl |
| `engine/diff.py` | 截图差异、bbox、红框热力图 |
| `replay.html` | 单文件时间线回放 |
| CLI | `run --inspect` / `diff` / `replay` |

**验收**
- 故意写一个"点了但没生效"的脚本，能通过回放**在 30 秒内**定位到那一步
- 录制开销 < 20%（因为截图快，这里比 PC+adb 方案便宜）

---

## M5 · 多开与云手机

| 任务 | 说明 |
| --- | --- |
| 设备池 | 一个 PC 控 N 台，连接复用 |
| 任务队列 | 优先级、重试、失败隔离 |
| 云手机接入 | `ip:7911` 直连 + 装机脚本 + IP 白名单提醒 |
| `xiaoyu ui`（可选） | 本地 Web 面板：设备列表、实时画面、脚本编辑 |

**验收**
- 单 PC 同时驱动 4 台雷电实例，各跑不同脚本互不干扰
- 任意一台断开，其余继续，断开的自动重连

---

## 风险清单

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| fb0 在 Android 10+ 被禁用 | 截图必须走 screencap，延迟翻倍 | M1 就探测；云手机优先选 Android 9~11 |
| sendevent 轴范围因设备而异 | 点击偏移 | 启动时解析 `absmax` 并做比例映射，不硬编码 |
| 云手机厂商限制外部 TCP | agent 连不上 | 用 `adb forward` 走 adb 通道兜底（延迟 +5ms，可接受） |
| 目标 App 检测注入 | 操作被忽略 | 加随机抖动与轨迹曲线（M2 可选开关） |
| Gradle/AGP 与 JDK 版本冲突 | 构建失败 | 固定 JDK 21 + AGP 8.x + Gradle 8.x，写进 environment.md |
