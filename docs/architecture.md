# 架构设计

## 1. 为什么是这个形状

### 1.1 为什么不用无障碍服务（AutoJS6 的路）

| | 无障碍服务 | root（本项目） |
| --- | --- | --- |
| 点击 | `dispatchGesture` 回调链，单次 50~150ms，容易被系统掐断 | `sendevent` 直写触摸节点，**3~10ms** |
| 截图 | MediaProjection，要授权弹窗，`FLAG_SECURE` 截不到 | 直读 fb0，**20~40ms**，安全界面也能截 |
| 存活 | 服务可能被系统回收、被省电策略杀 | 常驻进程 + root，稳 |
| 前提 | 用户手动开无障碍开关 | 一次 root，永久可用 |

既然已经要求 root，就没有理由再走无障碍 —— 它是"不能 root 时的妥协方案"，不是更优解。

### 1.2 为什么 Agent 不直接跑脚本

脚本层需要 OpenCV 识别、需要写文件、需要富文本日志、需要断点式调试。把这些塞进 APK：
编译一次 20~60 秒、没法断点、日志靠 adb logcat。放在 PC 上，改一行立刻重跑。
**设备端只保留"必须靠近硬件"的三件事**：截图、注入、shell。

### 1.3 为什么 PC ↔ Agent 用 TCP 而不是 adb shell

`adb shell input tap` 每次要 spawn 一个进程，端到端 150~300ms；其中绝大部分是进程和
协议栈开销，不是设备本身慢。TCP 直连 + 常驻 root shell 后，同样的 `sendevent` 只要 3~10ms。

雷电视同局域网，`adb forward tcp:7911 tcp:7911` 后 PC 直连 `127.0.0.1:7911`，延迟稳定；
上云手机时直接连 `ip:7911`，无需改协议。

### 1.4 为什么脚本层是 Node、引擎是 Python

- JS 脚本层要现代语法和 `async/await`（AutoJS6 的 Rhino 停在 ES5）；
- 图像识别要 OpenCV 原生性能（Node 只能吃 WASM 版，慢 3~10 倍）。
两者用 JSON-RPC over stdio 隔开，各自换实现不影响对方。

### 1.5 为什么不复刻 AutoJS6 的 API

「同类工具」和「克隆」是两件事。AutoJS6 的 API 有三个结构性缺陷：同步阻塞、`findImage` 内部
隐式截图（竞态根源）、失败返回 `null`（静默失败）。照抄这三条等于继承它们的 bug。

本项目 API 自研，见 [api.md](api.md)：**帧优先 + 定位器惰性求值 + 动作带断言 + 全异步**。
将来若有人需要跑老脚本，另做一层薄的 `autojs-compat` 适配器，主 API 不为兼容性变形。

## 2. 组件职责

```
┌──────────────────────────────────────────────────────────┐
│ JS 脚本  await click(120, 340)                            │
├──────────────────────────────────────────────────────────┤
│ runtime/  Node                                           │
│   api.js     把 API 调用翻成 RPC 请求，管理 Promise        │
│   bridge.js  spawn Python，单行 JSON 收发                 │
├──────────────────────────────────────────────────────────┤
│ engine/  Python                                          │
│   rpc.py      请求分发                                    │
│   device.py   语义层：tap / swipe / key / screenshot      │
│   vision.py   OpenCV：模板匹配、找色（定分辨率，单尺度）    │
│   client.py   Agent 客户端：连接池、超时、重连、seq 匹配    │
│   trace.py    轨迹录制（M4）                              │
├──────────────────────────────────────────────────────────┤
│ agent/  Kotlin（设备内，root）                            │
│   Protocol.kt     帧编解码                                │
│   ScreenGrabber   fb0 直读 → RGBA / JPEG                  │
│   TouchInjector   sendevent Protocol B，多点、贝塞尔插值   │
│   KeyInjector     sendevent 按键 / input keyevent         │
│   InputSource     /dev/input 探测与轴范围解析              │
│   RootShell       libsu 常驻 shell                        │
└──────────────────────────────────────────────────────────┘
```

**职责边界铁律**：Agent 不认识"找图"，Engine 不认识"事件节点"。跨界的逻辑一律放 Engine。

## 3. 线程模型

| 组件 | 模型 |
| --- | --- |
| Agent | 1 个 accept 线程 + 每连接 1 个读线程 + 1 个**串行执行线程**（注入必须保序） |
| Agent 截图 | 独立的抓帧线程，一次只允许一个在跑，后来的请求等待（避免撕裂） |
| Engine | 主线程阻塞式 CLI；Web 服务模式下每设备一线程 |
| runtime | Node 单线程事件循环，全异步 |

**为什么注入要串行**：`sendevent` 是内核事件流，两条并发写入会交错成非法序列，表现是
"偶尔点偏"或"滑动变成直线"。串行化是正确性要求，不是性能妥协（单次 3~10ms 足够快）。

## 4. 延迟预算（目标值，1280×720 / 雷电 9 / 本机回环）

| 操作 | 分解 | 目标 |
| --- | --- | --- |
| ping | TCP 回环 | < 1ms |
| tap | 协议 1ms + sendevent 2~6ms | **3~10ms** |
| swipe（300ms） | 插值 20 帧 × sendevent | 300ms ± 10ms |
| screenshot (raw) | fb0 读 15~30ms + 传输 3.7MB ≈ 5ms | **20~40ms** |
| screenshot (jpeg q85) | 读 25ms + 编码 20ms + 传 250KB | 45~60ms |
| key | sendevent 按键节点 | 5~15ms |
| inputText | `input text`（起进程） | 150~300ms |
| uiDump | `uiautomator dump` | 200~400ms |
| **找图 + 点击 一轮** | 截图 30 + 匹配 10 + 点击 5 | **~45ms** |

对比：同样的循环走 adb 是 400~700ms。这是本项目唯一真正重要的指标。

## 5. 关键技术决策

### 5.1 截图：优先 fb0 直读，screencap 是第二条腿

```
读 /dev/graphics/fb0 → 按屏幕宽高裁剪首帧 → 按 framebuffer 的 pixel format 转换
```

**失败原因和 root 无关**，是这两条：

1. Android 8+ 很多内核**压根没编译 framebuffer**（走 DMA-BUF + HWC），`/dev/graphics/fb0`
   这个文件不存在 —— root 再高也读不到不存在的节点；
2. 就算存在，Android 用双缓冲，直接读首帧可能拿到**上一帧甚至黑帧**，需要探测 offset 或重读。

所以 `screencap` 不是「应急兜底」，是必须存在的第二条腿。处理方式：**Agent 启动时探测一次**
（读一帧，非全黑即认为可用），结果写进 `info.grabber.mode`，PC 端可强制指定，**不静默切换**。

> 雷电 9（Android 9 / qemu 系）大概率有 fb0；上云手机前必须先用 `info` 探测一遍。

### 5.2 注入：sendevent + Protocol B

**失败原因同样和 root 无关**，是「找错设备节点」或「节点变了」：

- 一台设备上 `getevent` 能看到一堆节点（触摸屏、虚拟按键、传感器、手柄），必须按
  `ABS_MT_POSITION_X` 挑出真正的触摸屏
- 轴值不是屏幕像素（常见 0~32767），必须按 `absmax` 做比例映射，硬编码必点偏
- 重启或切前后台后节点号会变（`event2` → `event3`），**探测结果不能永久缓存**
- 部分云手机是虚拟化输入，`getevent` 里看不到触摸设备（厂商自有注入通道）

启动时通过 `getevent -p` 探测并记录 `absmax`/设备名，之后把像素坐标线性映射到轴值，按 B 协议发：

```
EV_ABS ABS_MT_SLOT 0
EV_ABS ABS_MT_TRACKING_ID <唯一id>
EV_ABS ABS_MT_POSITION_X / _Y
EV_SYN SYN_REPORT
（移动帧只重发 X/Y + SYN_REPORT）
EV_ABS ABS_MT_TRACKING_ID -1   ← 抬手
EV_SYN SYN_REPORT
```
按下与抬手之间**必须**有时长（默认 40~80ms 随机），否则被判定为无效点击。

### 5.3 分辨率无关设计

自己用固定 720×1280，但开源后别人可能是 1080×1920 甚至折叠屏，所以**不能绑死**：

- 模板图旁存 `<name>.png.json`：`{"w":720,"h":1280}` 标注来源分辨率
- 匹配时 `scale = 设备宽 / 模板标注宽`，按比例缩放模板后匹配
- 允许 ±10% 多尺度兜底（`policy.vision.scales`），最多 3 次匹配
- 分辨率相同时 `scale = 1.0`，只跑一次：单帧 **8~12ms**；不同分辨率最多 3 次，**仍 < 40ms**

所以「固定 720×1280」只是**让匹配更快**，不是能力上限。剧本里也不硬编码坐标，一律走定位器 —— 硬编码坐标换分辨率后必然失效。

### 5.4 图标/文字识别的边界

v1 只做**模板匹配**（`findImage`）。OCR（`findText`）留到 M5 之后单独评估 —— 云手机跑
PaddleOCR 要额外模型文件和 200ms+ 推理时间，先不做。

## 6. 失败模式与对策

| 失败 | 表现 | 对策 |
| --- | --- | --- |
| Agent 拿不到 root | `su -c id` 失败 | 启动即报 `E_NO_ROOT`，不进入半残状态 |
| fb0 读不到 | 首帧全黑/权限拒绝 | 自动回退 screencap，并在 `info` 里标记 `screencap=true` |
| 触摸设备找不到 | 注入无反应 | 启动探测失败则 `info` 返回 `input_ready=false`，Engine 拒绝执行点击 |
| 连接断开 | 请求超时 | 客户端指数退避重连（0.2s → 3.2s，上限 5 次），期间请求排队 |
| 分辨率和预期不符 | 点击偏移 | Engine 在连接握手中校验 1280×720，不符直接报错退出 |
| 注入过频 | 目标 App 丢帧/误判 | 全局最小间隔 30ms + 随机抖动（可关） |

## 7. 明确不做的事

- 不做免 root 兜底（用户明确要求，简化掉一半分支）
- 不做真机适配（只针对模拟器/云手机）
- 不做 iOS
- 不内置脚本商店 / 图形化编排（至少 M5 之前）
