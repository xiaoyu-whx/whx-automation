# 架构设计

依据：[项目书](project-charter.md)。本文只负责回答"怎么实现"，不改变项目书的目标。

## 1. 总体结构

一个 APK，两段进程。

```
┌─ Automation App（uid=10xxx，Java）──────────────────┐
│  权限管理 · 任务启动 · 运行状态 · 日志 · 前台服务     │
│                                                    │
│  ┌─ Automation Core（独立模块，同进程）───────────┐  │
│  │  ScreenSource（截图后端，可替换）              │  │
│  │  Vision（取色 / 找色 / 匹配）                  │  │
│  │  Input（触控后端，可替换）                     │  │
│  │  ScriptEngine（QuickJS 宿主）                  │  │
│  │  ScriptApi（JS ↔ Java 绑定）                   │  │
│  │  TaskManager（生命周期 / 停止 / 日志）          │  │
│  └────────────────────────────────────────────────┘  │
└──────────────────┬───────────────────────────────────┘
                   │ LocalSocket（约 0.1~0.3ms）
┌──────────────────▼───────────────────────────────────┐
│ Root Helper（uid=0，Java，APK 自带，su 拉起）          │
│  ScreenCapture · TouchInject · KeyInject · RootShell  │
└──────────────────────────────────────────────────────┘
```

### 1.1 为什么必须有 helper 进程

**实测结论**（2026-10-30，雷电 9）：

- `SurfaceControl.screenshot` 在 **shell 身份（uid 2000）** 下调用成功（11.4ms）
- 普通应用进程缺少 `READ_FRAME_BUFFER` 权限，同一调用拿不到画面
- 写 `/dev/input/eventN` 需要 root

所以"硬件能力"必须放在 root 进程里。helper 仍是 APK 的组成部分（作为 dex 随 APK 分发，
首次运行时从 assets 释放，用 `su -c app_process` 启动），**不增加交付物**。

替代方案的代价（都不采用）：

| 方案 | 代价 |
| --- | --- |
| MediaProjection | 每次运行要授权弹窗；常驻镜像多占 GPU/内存（低配云手机吃不消） |
| AccessibilityService 手势 | 点击 50~150ms，慢 3~5 倍，且服务容易被系统回收 |
| 全 root 进程跑一切 | 脚本引擎、UI 都在 root 进程，闪退即全崩，调试也困难 |

### 1.2 后端抽象（满足项目书"可替换设计"）

```java
interface ScreenSource {          // 截图后端
    Frame capture(Rect region);    // 返回可复用的帧对象
    String name();                 // "surfacecontrol" / "mediaprojection" / ...
    boolean available();
}

interface InputBackend {          // 触控后端
    void tap(int x, int y, int holdMs);
    void swipe(Path path, int durationMs);
    void key(int code);
    String name();                 // "evdev" / "accessibility" / "shell"
    boolean available();
}
```

**v1 只实现 `SurfaceControlSource` 与 `EvdevInput`（root 路径）**，其余保持接口空位。
项目书要求"不假定所有设备都能用同一种方式"，接口层已经满足；实现按需再补。

## 2. 模块职责

| 模块 | 归属 | 职责 | 明确不做 |
| --- | --- | --- | --- |
| `App` | APK | 权限、任务、状态、日志、前台服务 | 不编辑代码、不构建 APK |
| `core/ScreenSource` | APK | 抓帧、缓存、区域裁剪、格式转换 | 不认识业务 |
| `core/Vision` | APK | 取色、找色、模板匹配 | 不做 I/O |
| `core/Input` | APK | 触控语义（点击/长按/滑动/手势）、调度队列 | 不直接写设备节点 |
| `core/ScriptEngine` | APK | QuickJS 实例、脚本线程、停止标志 | 不做设备操作 |
| `core/ScriptApi` | APK | JS 全局函数绑定、参数校验、异常转换 | 不含算法 |
| `helper` | root 进程 | 截图、注入、root shell | 不认识脚本与视觉 |
| `tools` | PC | 部署、日志、调试、打包 | 不参与运行时 |

**边界铁律**：helper 不认识"找色"，core 不碰 `/dev/`。

## 3. 线程模型

| 线程 | 数量 | 说明 |
| --- | --- | --- |
| Script thread | 1 | QuickJS 实例专属线程；脚本同步调用在这里阻塞等待结果 |
| Input thread | 1 | 触控串行执行，保证事件流顺序 |
| Capture thread | 1 | 抓帧串行，避免撕裂 |
| Control thread | 1 | TCP 控制面，与脚本执行解耦 |
| Helper threads | 少量 | 每个连接一个读线程 + 一个执行线程 |

线程总数控制在 **6~8 个**（项目书要求"控制线程数量"，低配云手机对线程数敏感）。

**为什么触控必须串行**：`write()` 到 evdev 是内核事件流，两条并发写入会交错成非法序列，
表现是"偶尔点偏"或"滑动变直线"。这是正确性要求，不是性能妥协（单次仅 0.03ms）。

**脚本线程阻塞为什么没问题**：脚本在独立线程，不影响 UI 与前台服务；且单次操作
只有 11~31ms（实测），同步调用代价可接受 —— 详见 [api.md](api.md) 第 1 节。

## 4. 实测数据（2026-10-30 · 雷电 9 · Android 9 · x86_64 · 720×1280）

| 阶段 | P50 | 说明 |
| --- | --- | --- |
| `SurfaceControl.screenshot()` | 3.5 ms | SurfaceFlinger 合成，binder 往返 |
| HARDWARE → ARGB_8888 回读 | 5.7 ms | GPU 回读，**不可省略** |
| `getPixels()` → int[] | 2.3 ms | |
| **截图合计** | **11.4 ms** | |
| 9 条 evdev 事件写入 | 0.033 ms | |
| **完整 tap（按住 30ms）** | **31.3 ms** | 按住时长是延迟下限 |

对照组（同一台机器、同一帧）：`adb exec-out screencap` = 141.6ms；`input tap` = 134.6ms；
shell 里 9 条 `sendevent` = 210ms（每条 17ms 进程开销）。

**结论：进程内实现比 adb 方案快 4~12 倍，且瓶颈已转移到"平台本身"而非我们的代码。**

## 5. 关键技术细节

### 5.1 截图：SurfaceControl + 必须回读硬件位图

```java
// Android 9（API 28）：screenshot(Rect crop, int width, int height, int rotation)
Method m = SurfaceControl.class.getMethod(
        "screenshot", Rect.class, int.class, int.class, int.class);
Bitmap bmp = (Bitmap) m.invoke(null, new Rect(0, 0, w, h), w, h, 0);

// 坑：返回的可能是 Config#HARDWARE，不能直接 getPixels()
Bitmap soft = (bmp.getConfig() == Bitmap.Config.HARDWARE)
        ? bmp.copy(Bitmap.Config.ARGB_8888, false)   // 约 5.7ms
        : bmp;
soft.getPixels(pixels, 0, w, 0, 0, w, h);
```

- **坑 1**：`getPixels()` 对 HARDWARE bitmap 抛
  `IllegalStateException: unable to getPixels(), pixel access is not supported on Config#HARDWARE bitmaps`。
  报错文本与"截图"无关，误判成权限问题会浪费大量时间。
- **坑 2**：**雷电 9 没有任何 framebuffer**（`/dev/fb0`、`/dev/graphics` 均不存在），
  所以不存在"fb0 直读"这条路，只能用 SurfaceControl。
- API 34 起 `screenshot` 被 `captureDisplay` 取代，helper 需按 SDK 版本分支。

**缓存与复用**（项目书 4.1 要求）：`Frame` 对象持有 `int[]` 像素与尺寸，同一帧内多次
识别共享同一份像素；只有显式 `refresh()` 才重新抓帧。避免"每次找色都重新截图"。

### 5.2 注入：evdev 直写 + 轴坐标交换

本机实测 `/dev/input/event2`：

```
ABS_MT_POSITION_X : 0..1279   ← 屏幕「高」
ABS_MT_POSITION_Y : 0..719    ← 屏幕「宽」
ABS_MT_SLOT       : 0..15     ← 最多 16 点
```

**映射：`轴X = 屏幕y`，`轴Y = 屏幕x`**（已用"注入后跳转到同一页面"验证）。
不交换不会报错，只会静默点偏 —— 这类问题是"失败要吵"原则存在的理由。

事件结构（64 位设备 `struct input_event` 24 字节，小端）：

```
[0..7] tv_sec   [8..15] tv_usec   [16..17] type   [18..19] code   [20..23] value
```

一次 tap 的 9 条事件：`ABS_MT_SLOT 0` → `TRACKING_ID 1` → `POSITION_X <屏幕y>` →
`POSITION_Y <屏幕x>` → `BTN_TOUCH 1` → `SYN_REPORT` → （按住 30~40ms）→
`TRACKING_ID -1` → `BTN_TOUCH 0` → `SYN_REPORT`。

**其它必知**：节点号重启后会变（`event2`→`event3`），每次启动必须重新探测；
部分云手机是虚拟化输入，`getevent` 里看不到触摸设备，需要在阶段七单独处理。

### 5.3 图像识别：先自己写，不引 OpenCV

项目书明确：简单识别优先自主实现。取色/找色本质是数组扫描，自己写更快更省内存：

- 像素内联为 `int`（ARGB），避免 `Bitmap.getPixel()` 的 JNI 开销
- 区域扫描按行连续访问，提高缓存命中
- 找色支持容差与"步长"参数（低配设备可跳采样）
- 模板匹配采用"金字塔 + 归一化互相关"，仍不足再考虑 JNI/C++

OpenCV 只在**确实需要**（多尺度、特征匹配、形态学）时才评估，不预先引入 20MB。

### 5.4 脚本引擎：QuickJS

- 实例非线程安全 → 一个实例一个线程，停止用标志位 + 中断回调
- JS 调用 Java 走 JNI 绑定；**每次调用开销要测**，必要时批量绑定（阶段五验证）
- 同步 API 的实现方式：Java 方法在脚本线程上阻塞等待任务完成（见 api.md 第 1 节）

### 5.5 打包（项目书第六节两种模式）

| 模式 | 做法 |
| --- | --- |
| 内置脚本 | 壳 APK + 脚本与资源作为 assets，重签后分发，装完即跑 |
| 外部导入 | APK 提供导入入口（`adb push` 到指定目录或应用内选择文件），运行时读盘 |

两种模式共用同一套 Core，区别只在"脚本从 assets 读还是从磁盘读"。

## 6. 低配设备约束（项目书第八节）

| 约束 | 做法 |
| --- | --- |
| 1 核 1GB 云手机 | 单线程脚本、单 capture 线程；像素缓冲只保留最近 1~2 帧 |
| 内存不持续增长 | `Frame` 显式回收；避免每帧新建数组；脚本包资源按需加载 |
| 线程数 | 总数 ≤ 8 |
| CPU 占用 | 找色支持区域限定与步长；不做无意义高频截图（帧内复用） |

## 7. 失败模式与对策

| 失败 | 表现 | 对策 |
| --- | --- | --- |
| su 被拒 | helper 起不来 | App 明确报错并退出，不降级 |
| helper 崩溃 | 截图/点击超时 | 心跳检测，自动重启 |
| 截图返回 null | 息屏/权限不足 | 连续 3 次失败重启 helper 并上报 |
| HARDWARE 位图未回读 | `IllegalStateException` | 强制判断 config 后 copy |
| 触摸节点变更 | 点击全部无效 | 每次启动重新探测；点击后无画面变化则告警 |
| QuickJS 崩溃 | 脚本线程挂掉 | 隔离在脚本线程，App 与 Core 不受影响 |

## 8. 明确不做

- 设备端代码编辑 / 编译 / 打包
- 中文脚本语言、图形化编排
- 复刻任何现有工具的 API 或脚本语法
- 预先引入 OpenCV、C++、NDK 原生库（先测再说）
- iOS
