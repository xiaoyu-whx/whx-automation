# 脚本 API 设计（规划，未实现）

> **这不是 AutoJS6 的 API。** 目标不是兼容谁，而是把"用脚本操作安卓"这件事做到最好。
> 参照 AutoJS6 只说明生态位，不复刻它的语义。

## 0. 为什么重设计

AutoJS6 那一套的问题不在语法，在**语义**：

| 它的做法 | 后果 |
| --- | --- |
| 同步阻塞调用 | 无法并发、无法超时、无法组合 |
| `findImage()` 内部偷偷截图 | 找到元素→去点它时画面已变，**竞态 bug 根源** |
| `click(100,200)` / `click("登录")` / `click(选择器)` 三重载 | 参数含义靠猜，类型系统失效 |
| `selector.findOne()` 一次阻塞到超时 | 无法表达"每轮检查多个条件" |
| 找不到返回 `null`，脚本继续跑 | **静默失败**，最难排查 |
| 几十个函数挂在全局 | 无补全、无类型、和业务变量撞名 |
| 脚本只能上真机跑 | **无法单测** |

## 1. 七条原则

1. **帧是第一公民**：视觉判断必须发生在**显式快照**上，`screen.grab()` 出的帧在有效期内可反复查询，结果一致。
2. **全异步**：单次调用 3~10ms，异步不亏；换来 `Promise.all` 并发与 `AbortSignal` 取消。
3. **零重载**：不同意图用不同方法名，不靠参数类型猜。
4. **失败要吵**：查询不到即抛错；要允许缺失时显式写 `findOrNull()`。
5. **动作带断言**：写操作可以附断言，"点了屏幕没变"= 失败，而不是"执行成功"。
6. **命名空间**：全局只暴露 `device / screen / app / images / step / policy / expect`，其余 `import`。
7. **可测试**：`Transport` 可注入，用录好的帧序列在 PC 上跑脚本，不需要设备。

## 2. 最小可用脚本

```js
import { screen, device, step } from 'xiaoyu'

await step('打开设置并进入电池页', async () => {
  await app.launch('com.android.settings')

  const frame = await screen.grab()                       // 一张显式快照
  const entry = frame.find(img('assets/battery_entry.png'))
  if (!entry) throw new Error('电池入口未出现')

  await device.tap(entry.center).expect(screen.changed())
})
```

## 3. 帧与定位器

### 3.1 帧

```js
const f = await screen.grab({ region: [0, 0, 720, 720] })  // 可只截一块，省传输
f.size        // { width, height }
f.ageMs       // 距离截图的毫秒数，脚本可据此判断"帧是否还新鲜"
f.stale       // ageMs > policy.frame.maxAgeMs
await f.refresh()  // 重新截一帧，返回新对象（旧帧不变）
```

**帧是值语义**：`f.find()` 是纯函数，同一帧上查 10 次结果完全一致，互不影响，可以缓存。

### 3.2 定位器（惰性、可组合）

```js
img('assets/ok.png')                          // 模板图
img('assets/ok.png', { threshold: 0.9 })      // 自定义阈值
text('登录')                                   // OCR（M5 之后，先留位置）
color('#FF5722', { tolerance: 8 })             // 颜色
```

组合子：

```js
img('a.png').within([0, 400, 720, 300])       // 只在某区域内找
img('a.png').above(img('b.png'))              // 相对位置
img('a.png').nearest(entry.center)            // 多个命中里取最近的
img('a.png').nth(2)                           // 取第 2 个命中
```

求值：

```js
f.find(locator)          // → Hit，找不到抛 NotFoundError
f.findOrNull(locator)    // → Hit | null
f.findAll(locator)       // → Hit[]
f.has(locator)           // → boolean
```

`Hit` 结构：

```js
{ score: 0.98, rect: { x, y, w, h }, center: { x, y }, frameId: 'f7' }
```
`Hit` 带 `frameId`，跨帧使用会在调试日志里标出来 —— 这是"拿着旧帧坐标去点"的显式痕迹。

## 4. 动作与断言

所有动作返回一个**可等待的 Action**，它本身就是一个 Promise：

```js
await device.tap(point)                                  // 只执行
await device.tap(point).expect(screen.changed())          // 执行 + 断言
await device.swipe(from, to, { durationMs: 300 }).expect(screen.changed({ minRatio: 0.02 }))
await device.key('BACK').expect(screen.same({ since: f }))
```

动作清单：

| 方法 | 说明 |
| --- | --- |
| `device.tap(point, { durationMs })` | 单点 / 长按（`durationMs ≥ 800`） |
| `device.swipe(from, to, { durationMs, curve })` | 直线或带弧线 |
| `device.path(points, { durationMs })` | 多点折线 |
| `device.pinch(fingers, { durationMs })` | 多指 |
| `device.key(nameOrCode)` | 按键 |
| `device.text(str)` | 输入文本（150~300ms，慢是正常的） |
| `device.shell(cmd, { asRoot, timeoutMs })` | 直通 root shell |

**默认插桩**：每个动作自动带 ~30ms 随机间隔与 ±6px 随机偏移（抗检测），可用 `policy` 关闭。

断言清单：

| 断言 | 含义 |
| --- | --- |
| `screen.changed({ minRatio = 0.005, timeoutMs = 2000 })` | 指定时长内画面变化超过阈值 |
| `screen.same({ since, timeoutMs })` | 画面保持不变（用于"点了但不该跳转"） |
| `screen.shows(locator, { timeoutMs })` | 画面中出现了某元素 |
| `screen.hides(locator, { timeoutMs })` | 某元素消失了 |
| `app.isForeground(pkg)` | 前台 App 是某包名 |
| `app.isGone(pkg)` | 应用已退出 |

**为什么这是卖点**：自动化最难查的不是崩溃，是"脚本全绿但什么都没发生"。把断言写进动作，这类 bug 在发生的当场就炸，而不是跑到第 50 步才发现。

## 5. 等待

```js
// 声明式：内部循环"截图 → 判断"，不做死等
const hit = await screen.waitFor(img('ready.png'), { timeout: 15_000, poll: 150 })

// 多条件并发：谁先满足用谁
const first = await screen.waitForAny([
  img('success.png'),
  img('error.png')
], { timeout: 10_000 })

// 条件表达式
await screen.waitUntil(async (f) => f.findAll(img('item.png')).length >= 10, { timeout: 20_000 })
```

`waitFor` 每轮的开销 = 一次截图（20~40ms）+ 一次匹配（8~12ms），所以 `poll: 150` 的实际开销约 20%。

## 6. 策略与配置

策略集中在一个对象里，脚本不再散落 `sleep(500)` 这类魔法数字：

```js
import { policy } from 'xiaoyu'

policy.tap.jitterPx = 6          // 点击随机偏移
policy.tap.minGapMs = 30         // 两次点击最小间隔
policy.frame.maxAgeMs = 300      // 帧超过此时长视为过期（调试时告警）
policy.wait.poll = 150           // waitFor 轮询间隔
policy.retry.attempts = 3
```

**默认值在 PC 端（Engine）配置，脚本可覆盖**。多人协作时，行为差异只看 `policy`，不看各人脚本。

## 7. 步骤与轨迹

```js
await step('登录', async () => { ... })          // 自动记录起止、耗时、首尾截图
await step.note('当前余额 12.3 元')               // 往轨迹里插一条备注
```

`step` 是调试体系的地基：M4 的 `replay.html` 按步骤分组渲染，而不是几十条平铺的 API 调用。

## 8. 可测试性

```js
// 测试环境：用录制好的帧序列替代真实设备
import { testing } from 'xiaoyu'
testing.useReplay('runs/20261030-150000-a1b2')   // 逐帧喂给脚本
```

这让脚本可以进 CI：录一次真机，之后每次改脚本都能离线重跑，断言回归。
**这是所有同类工具都做不到的事**，代价只是把 `Transport` 抽象一层。

## 9. 设备与 App

```js
await device.info()        // { screen, root, android, model, input }
await device.size()        // { width, height }
await device.density()     // dpi
await app.launch('com.foo')
await app.forceStop('com.foo')
await app.current()        // { package, activity }
await app.installed('com.foo')
```

## 10. 分辨率无关

本项目**不绑定分辨率**（自己用 720×1280，开源后别人可能 1080×1920）：

- 模板图旁边存 `<name>.png.json`：`{ "w": 720, "h": 1280 }`
- 匹配时自动算 `scale = 当前宽 / 模板标注宽`，按该比例缩放模板
- 允许 ±10% 多尺度兜底（`policy.vision.scales`）
- 分辨率相同时 `scale = 1.0`，只跑一次匹配，8~12ms；不同分辨率最多 3 次，仍 < 40ms

所以"你固定 720×1280"只是**让匹配更快**，不是能力上限。

## 11. 明确不提供

| 不提供 | 原因 |
| --- | --- |
| 同步阻塞 API | 会卡死事件循环；异步单次 3~10ms 已足够快 |
| `click(x, y)` / `click("文本")` 重载 | 类型系统失效，参数靠猜 |
| `findOne()` 死等语义 | `waitFor` 表达得更清楚 |
| 返回 `null` 当失败 | 静默失败是自动化最大的敌人 |
| UI 控件选择器（`text('登录').click()`） | 依赖 `uiDump`（200~400ms），比找图慢一个数量级；M4 后按需评估 |
| 无障碍相关 API | 本项目不用无障碍，走 root |
| 兼容 AutoJS6 脚本 | 需要时另做一层 `autojs-compat` 适配器，主 API 不为它变形 |
