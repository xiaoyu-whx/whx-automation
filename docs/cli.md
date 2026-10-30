# whx —— PC 端工具链

项目书的原则是「**PC 上编程、设备只负责运行**」。之前写完脚本得手动 `adb push` +
在手机上点运行，等于没有开发体验。`tools/whx.py` 把这条往返做顺。

## 命令

```bash
python tools/smoke.py               # 端到端冒烟：构建 → 单测 → 安装 → 12 项检查
```


```bash
python tools/whx.py devices                 # 列设备、确认 root 与分辨率
python tools/whx.py ls                      # 设备上有哪些脚本
python tools/whx.py run demo.js             # 推脚本 → 选中 → 运行 → 跟日志直到结束
python tools/whx.py run demo.js --no-wait   # 只启动，不等结果
python tools/whx.py log                     # 跟随日志（Ctrl-C 退出）
python tools/whx.py shot dist/a.png         # 截图到 PC
python tools/whx.py pack demo.js --label 小鱼 --pkg com.whx.sprite.demo
```

多设备时用 `--serial emulator-5554` 指定；不指定则用第一个在线设备。

### 任务链（多个脚本按顺序跑）

```bash
python tools/whx.py run login.js order.js cleanup.js
```

```
任务链：login.js → order.js → cleanup.js
[WHX/Task] 链式执行：共 3 个脚本，开始 login.js
[WHX/Task] 链式执行下一个: order.js（剩余 1）
[WHX/Task] chain finished
```

**中途失败会停链**（后续脚本通常依赖前一个的结果）：

```
[WHX/Task] done ok=false cost=30ms
[WHX/Task] 链式执行中止：本次失败，跳过后续 1 个脚本
```

### 写脚本的循环：watch

```bash
python tools/whx.py watch dist/my.js
```

监听本地文件，**存盘即推送运行**，日志实时回显：

```
监听 dist/watch_test.js（Ctrl-C 退出），改动后自动运行

===== 第 1 次运行 00:06:49 =====
[WHX/Task] start watch_test.js
[WHX/Script] [info] 500 次取色耗时 25 ms
[WHX/Task] done ok=true cost=430ms

===== 第 2 次运行 00:07:01（检测到改动）=====
[WHX/Script] [info] 改动生效了 1793376423981
[WHX/Task] done ok=true cost=405ms
```

每次运行前会等文件"稳定"（编辑器保存常连续触发多次写入），避免跑到一半的代码。

## 识别调试：vision_cli

写完坐标总要验证对不对，所以把识别搬到 PC 上做，**结果直接画在截图上**：

```bash
python tools/whx.py shot dist/screen.png          # 先抓一张

python tools/vision_cli.py pick --at 360,640      # 取某点颜色 → 直接给出脚本代码
python tools/vision_cli.py color --hex "#14161A" --tol 6 --save dist/hit.png
python tools/vision_cli.py crop  --region 30,140,400,70 --out dist/tpl.png
python tools/vision_cli.py template --tpl dist/tpl.png --threshold 0.9 --save dist/tpl_hit.png
```

输出示例：

```
坐标 (360, 640) 颜色 #D6D7D7  rgb(214, 215, 215)

脚本里这样用：
  var c = getColor(360, 640);            // → '#D6D7D7'
  var isMatch = isColor(360, 640, '#D6D7D7', 6);
```

```
模板 dist/tpl.png 尺寸 400x70
最佳匹配: 左上 (30, 140)  中心 (230, 175)  得分 1.000
```

**实测**：自己裁的模板找回自己返回 (30,140) 得分 1.000；随机噪声模板得分 0.099
（低于阈值、退出码 1）；重复纹理能列出多个候选并给出各自得分。

实现说明：找图用 **FFT 归一化互相关**（`np.fft.rfft2`），配合积分图算窗口均值/方差，
整屏匹配在 PC 上是毫秒级，不需要 OpenCV。

## 实测

| 命令 | 结果 |
| --- | --- |
| `devices` | `emulator-5554 2509FPN0BC API 28 root=yes 720×1280` |
| `run 正常脚本` | 推送 → 运行 → 日志流式回显 → `done ok=true cost=414ms` → **退出码 0** |
| `run 报错脚本` | 回显 `QuickJsException: cannot read property 'split' of null` + `at JavaScript.<eval>(?:5)` → `done ok=false` → **退出码 1** |
| `shot` | 117 KB PNG |
| `pack` | 3.2 MB 小精灵，包名与名称已改 |

退出码有语义：脚本失败返回 1，所以能直接接 CI（`python tools/whx.py run x.js || notify`）。

## 两个坑

**坑 1：热启动的 intent 会静默失效**

App 支持 `--es script <名字> --ez run true` 指定脚本（PC 端靠它驱动）。
但实测：**当 Activity 已在栈顶时，`am start` 只是把 intent 投递给 onNewIntent，
脚本却没跑，而且一行日志都没有** —— 典型的"静默失败"。

对策是 CLI 侧改成确定性的冷启动：

```bash
am force-stop com.whx.automation
am start -n com.whx.automation/.com.whx.automation.MainActivity --es script x.js --ez run true
```

顺便在 App 里补了日志（`handleIntentExtras` 会打印收到的参数），
以后同类问题不会再无声无息。

**坑 2：推脚本要改属主**

应用私有目录属主是 `u0_a54`(10054)，root 拷进去后属主是 root，
应用读不到自己的脚本。必须：

```bash
uid=$(stat -c %u:%g /data/data/com.whx.automation/files)
chown $uid /data/data/com.whx.automation/files/scripts/x.js
chmod 660 /data/data/com.whx.automation/files/scripts/x.js
```

`whx.py` 里 `app_uid()` 就是干这个的。

## 运行时配置

小精灵的配置项在命令行也能直接传，调试时不用反复打包：

```bash
python tools/whx.py run config_demo.js --config x=111 --config y=222 --config delay=0
```

```
[WHX/Script] [info] x = 111
[WHX/Script] [info] y = 222
[WHX/Script] [info] 已按配置点击 111,222
```

脚本用 `config('x')` 读，键不存在会抛异常并列出可用键。

## 无障碍开关

脚本要读界面文字（`texts()` / `findText()` / `clickText()`）必须先启用无障碍：

```bash
python tools/whx.py ui enable     # 用 root 写 secure 设置，无需手点
python tools/whx.py ui            # 查看状态
python tools/whx.py ui disable
python tools/whx.py ui enable --package com.whx.sprite.demo   # 小精灵是独立包名
```

> 注意：`whx run --cold` 会 force-stop 应用，无障碍服务会被系统摘掉，
> 需要重新 `ui enable`。默认（非冷启动）不受影响。

## 定时任务

```bash
python tools/whx.py schedule add demo.js --time 08:30
python tools/whx.py schedule list
python tools/whx.py schedule remove demo.js
```

到点由**设备自己**触发（AlarmManager），PC 不需要在线；开机后自动重挂。

**实测**：添加 `00:20` 的计划后日志为

```
WHX/Schedule: 00:20  demo.js 下次触发 10-31 00:20
WHX/Schedule: 已挂载 1 条定时任务
```

到点（误差 6ms）自动运行并续到次日：

```
00:20:00.006 WHX/Schedule: 闹钟触发: demo.js
00:20:00.010 WHX/Task: start demo.js
00:20:00.020 WHX/Schedule: 00:20  demo.js 下次触发 11-01 00:20
00:20:00.731 WHX/Task: done ok=true cost=720ms
```

实现要点：

- 用**一次性闹钟 + 触发后重排**，不用重复闹钟 —— 重复闹钟在改系统时间/夏令时后会漂。
- 存储是 SharedPreferences 里的 `名字|小时|分钟`，启动时校验范围，越界条目丢弃
  （宁可少跑一条，不可按非法时间乱跑）。
- 小精灵包也能定时：触发时先看是不是绑定脚本，是就从 assets 读。

## 运行历史

```bash
python tools/whx.py history --limit 5
```

```
最近 3 条运行记录（新的在前）：
  10-31 00:34:21  成功  find_image_demo.js      685ms
  10-31 00:33:49  失败  bad.js                   90ms  QuickJsException: cannot read property 'split' of null
  10-31 00:33:47  成功  demo.js                 387ms
```

无人值守（定时任务/小精灵）出问题时人不在现场，事后必须能回答：
**什么时候跑的、成没成功、为什么失败**。历史只存这四项（最多 20 条），
不存日志全文（logcat 会被系统回卷覆盖）。

## 日志显示策略

logcat 里的 Java 栈对脚本作者没用，所以只保留：

- 异常首行（异常类型 + 消息）
- `at JavaScript.<eval>(?:行号)` —— **JS 报错的行号**
- 我们自己的 `[info]/[warn]/[error]/[done]` 行

完整栈仍在 `adb logcat -s WHX/Script` 里可查。
