# JavaScript API 设计

依据项目书 §4.5：**自主设计、统一简洁、为扩展留空间**。本文定接口形态；具体实现随阶段推进细化。

## 1. 同步还是异步

**结论：主 API 同步阻塞。**

早期设计过全异步（`await click(...)`），理由当时是"adb 下每次调用 300ms，同步会卡死"。
实测推翻了前提：**截图 11.4ms、点击 31.3ms**（进程内，见 architecture.md 第 4 节）。
在这个量级下，同步写法更贴合脚本作者的直觉，也与项目书 §4.5 的示例一致。

实现方式：脚本跑在**独立线程**（不是 UI 线程），Java API 在该线程上阻塞等待操作完成。

- 脚本线程阻塞 → 不影响 UI、前台服务、日志推送
- 需要并发等待多个条件时，用专门的 `waitForAny()`，而不是让用户自己写 Promise 编排

**代价**：单条脚本线程无法并行做两件设备操作。这个限制是刻意的 —— 设备操作本来就该串行。

## 2. 截图与帧

```js
let img = captureScreen();            // 抓一帧（Frame 对象）
img.width, img.height                 // 720, 1280
img.region                            // {x, y, w, h}，缺省整屏
img.ageMs                             // 距抓帧的毫秒数

let part = captureScreen({ region: [0, 0, 720, 640] });  // 只抓上半屏
img.refresh();                        // 重新抓一帧，返回新 Frame（旧帧不变）
```

**帧语义**：`captureScreen()` 的结果是**显式快照**，后续所有识别都在这份像素上做。
同一帧内查 10 次结果完全一致，不会出现"查到的是上一帧"这种竞态。

**为什么强调这一点**：很多同类工具每次 `findColor` 内部偷偷重新截图，导致"找到元素→
去点它"之间画面已变，是自动化最典型的偶发 bug。这里把它变成不可能。

## 3. 图像识别

### 3.1 取色与颜色判断

```js
let c = getColor(500, 800);                    // → { r, g, b, hex: "#F5A623" }
let ok = isColor(500, 800, "#FFFF00");         // → true / false
let ok2 = isColor(500, 800, "#FFFF00", { tolerance: 12 });
let ok3 = isColor(500, 800, [255, 255, 0], { tolerance: 12 });
```

### 3.2 找色

```js
let pt = findColor("#FFFF00");                            // → { x, y } 或 null
let pt = findColor("#FFFF00", { region: [100, 100, 800, 600] });
let pt = findColor("#FFFF00", { tolerance: 10, direction: "left2right" });
let all = findColorAll("#FFFF00", { max: 20 });            // → [{x,y}, ...]
```

参数：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `region` | 整屏 | `[x, y, w, h]` |
| `tolerance` | 8 | 单通道最大差值（0~255） |
| `direction` | `left2right` | 扫描顺序，决定"先返回哪个点" |
| `step` | 1 | 采样步长，低配设备可设 2~3 提速 |
| `max` | 1 / 不限 | `findColorAll` 的结果上限 |

### 3.3 多点找色

```js
// 以锚点为基准，检查若干相对偏移的颜色是否同时成立
let pt = findMultiColor({
  anchor: "#FFFF00",
  offsets: [[10, 0, "#FF0000"], [0, 10, "#00FF00"]],
  region: [0, 0, 720, 1280],
  tolerance: 8
});
```

### 3.4 找图（阶段三第二阶段）

```js
let hit = findImage("assets/btn_ok.png", { threshold: 0.85 });
// → { score, x, y, w, h, center: {x, y} } 或 null

let hits = findImageAll("assets/icon.png", { threshold: 0.8, max: 10 });
let hit  = findImage("assets/btn.png", { region: [0, 600, 720, 200] });
```

模板分辨率无关：模板旁存 `<name>.png.json` 记录来源分辨率，匹配时按
`scale = 设备宽 / 模板标注宽` 缩放；同分辨率时缩放 1.0，最快。

## 4. 触控

```js
click(500, 800);                    // 点击，默认按住 30~40ms
click(500, 800, { hold: 80 });      // 自定义按住时长
longClick(500, 800, 1000);          // 长按 1000ms
swipe(500, 1000, 500, 300, 500);    // 500ms 内从 (500,1000) 滑到 (500,300)
swipe([500,1000], [300,600], { duration: 400, curve: 0.2 });  // 带弧度
clickPoint(pt);                     // 直接点识别结果
```

约束：

- **坐标是屏幕像素，左上原点**；轴坐标交换由核心层内部完成，脚本永远写屏幕坐标
- 默认每次点击带 ±4px 随机偏移与 30ms 最小间隔（`setPolicy` 可调），避免机械特征
- `swipe` 支持 `steps` 隐式插值，总时长误差 ±10%

## 5. 等待与流程

```js
sleep(1000);                                     // 毫秒

// 轮询直到条件成立，返回条件结果；超时抛异常
let hit = waitFor(() => findImage("ready.png"), 15000, 200);

// 多条件竞争，谁先成立返回谁
let which = waitForAny([
  () => findImage("ok.png") && "ok",
  () => findImage("error.png") && "error"
], 10000);

// 屏幕变化等待（替代固定 sleep 的稳妥写法）
waitForScreenChange(2000);
```

`waitFor` 每轮开销 = 抓帧 11ms + 识别 8~15ms，`interval: 200` 时约占用 12%。

## 6. 设备与应用

```js
let info = deviceInfo();          // { screen, sdk, release, model, helper, backends }
deviceSize();                     // { width, height }
launch("com.android.settings");
forceStop("com.android.settings");
currentApp();                     // { package, activity }
installed("com.foo");             // true / false
key("BACK");                      // HOME / BACK / ENTER / POWER ...
inputText("你好");                // 输入文本（较慢，150~300ms，属正常）
shell("ls /sdcard");              // → { code, stdout, stderr }
```

## 7. 策略与日志

```js
setPolicy({
  click:  { jitterPx: 4, minGapMs: 30, holdMs: 40 },
  find:   { tolerance: 8, step: 1 },
  frame:  { maxAgeMs: 300, cache: true },
  wait:   { interval: 200 }
});

log("info", "进入登录页");
step("登录", () => { click(500, 800); sleep(500); });   // 打点，供轨迹回放分组
```

## 8. 错误约定

| 情况 | 行为 |
| --- | --- |
| 参数非法 | 抛 `TypeError`，消息指出哪个参数、期望什么 |
| 截图失败 | 抛 `ScreenError`，附带连续失败次数 |
| 触控失败（helper 无响应） | 抛 `InputError`，附带 helper 状态 |
| 坐标越界 | 抛 `RangeError`（不静默截断） |
| `waitFor` 超时 | 抛 `TimeoutError`，附带最后一次失败原因 |
| 识别不到 | `findColor` / `findImage` 返回 `null`（**唯一允许返回 null 的地方**），其余一律抛错 |

> 为什么识别返回 null 而不抛错：找不到目标是最常见的正常分支，强制 try/catch 会让脚本
> 难以阅读。但**动作类**接口绝不静默失败。

## 9. 扩展位（暂不实现）

- 多指手势 `multiTouch(fingers)`
- 元素树查询（走 `uiautomator dump`，200~400ms，比找图慢，按需再评估）
- OCR 找字
- 多设备并发（同一 PC 驱动多台）

接口命名与返回结构已为这些预留：所有识别结果统一为 `{x, y, w, h, center}`，
所有动作统一接受 `{ hold, duration, ... }` 选项对象。
