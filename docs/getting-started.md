# 怎么用：从写第一行脚本到跑起来

三条命令就能跑。全程在 **PC 上编辑**，手机只负责执行。

## 1. 一次性的准备（只做一次）

```bash
cd E:\aotojs_xiaoyu

# 装包（手机连上后）
adb install -r app/build/outputs/apk/release/app-release.apk

# 自检：哪一步没准备好，它会直接告诉你
python tools/whx.py doctor
```

`doctor` 全 ✓ 就可以往下走了。常见没过的项：

| 提示 | 怎么办 |
| --- | --- |
| `adb 未找到` | 把 Android SDK 的 `platform-tools` 加进 PATH |
| `root uid=?` | 模拟器要开 root；真机要 root 后首次弹窗点「允许」 |
| `无障碍服务 未启用` | `python tools/whx.py ui enable` |

## 2. 写脚本（在哪编辑）

脚本就是**普通 `.js` 文本文件，放在 PC 任何位置**。推荐放仓库里的 `scripts/`：

```
E:\aotojs_xiaoyu\scripts\          ← 你自己的脚本放这里
├─ hello.js                        模板（带注释，照着改）
└─ my_task.js                      你的
```

用什么编辑都行：

- **VS Code**（推荐）：`code E:\aotojs_xiaoyu\scripts`
- 记事本、Sublime、Notepad++ 都可以，**存成 UTF-8**

API 速查：[api.md](api.md) —— 截图、找色、找图、点击、滑动、读文字、启动应用、配置项。
`scripts/hello.js` 是带注释的活模板，覆盖了主要 API。

## 3. 跑起来（改一行就能看到结果）

```bash
# 边写边跑：存盘即推送并执行，日志实时回显在终端
python tools/whx.py watch scripts/hello.js
```

```
监听 scripts/hello.js（Ctrl-C 退出），改动后自动运行

===== 第 1 次运行 09:42:10 =====
[WHX/Task] start hello.js
[WHX/Script] [info] 你好，WHX
[WHX/Script] [info] 屏幕 720x1280
[WHX/Script] [info] 屏幕中心颜色 #D6D7D7
[WHX/Script] [info] 找到了：702,10
[WHX/Script] [info] 界面文本节点 13 个
[WHX/Script] [info] 「电池」→ 360,712
[WHX/Script] [info] 已点击文本「电池」@ 360,712
[WHX/Script] [info] 当前前台 com.android.settings
[WHX/Task] done ok=true cost=2011ms
```

改一行、按 Ctrl-S 保存 → 自动重跑。**不用碰手机**。

跑单次或传参数：

```bash
python tools/whx.py run scripts/my_task.js                     # 跑一次，等结果
python tools/whx.py run scripts/my_task.js --config x=100       # 传运行时参数
python tools/whx.py run a.js b.js c.js                          # 任务链：顺序执行
```

退出码：成功 0，失败 1（脚本报错会打印 **JS 行号**）。

## 4. 找坐标 / 找颜色（不用猜）

```bash
python tools/whx.py shot dist/screen.png          # 先截一张到 PC
python tools/vision_cli.py pick --at 360,640      # 看这个点的颜色，并给出脚本代码
python tools/vision_cli.py color --hex "#14161A"  # 找色，打印第一个命中点
python tools/vision_cli.py crop --region 30,140,400,70 --out dist/tpl.png   # 裁模板
python tools/vision_cli.py template --tpl dist/tpl.png --save dist/hit.png  # 验证找图能命中
```

`--save` 会把命中位置画在截图上，一眼就能确认对不对。

## 5. 打包成小精灵（可选）

给不会用电脑的人用时，把脚本打成一个独立 APK：

```bash
python tools/whx.py pack scripts/my_task.js --label "我的小工具" --pkg com.whx.sprite.mine --icon 2
# 产物：dist/xiaoyu-mine.apk（3.1MB，装完打开即跑，不需要 PC）
```

要让它有参数输入框，加 `--config`（格式见 [packer.md](packer.md)）：

```bash
python tools/whx.py pack scripts/my_task.js --label 我的工具 --pkg com.whx.sprite.mine --config packer/sample-config.txt
```

脚本里用 `config('x')` 读，用户改过的值会**记住**。

## 6. 出了问题怎么看

```bash
python tools/whx.py history --limit 5    # 最近几次：什么时候跑、成没成、为什么失败
python tools/whx.py log                  # 实时跟着日志
python tools/whx.py doctor               # 环境再自检一遍
```

脚本报错时终端会直接显示到 JS 行号：

```
[WHX/Script] app.cash.quickjs.QuickJsException: cannot read property 'split' of null
[WHX/Script] at JavaScript.<eval>(?:5)      ← 你的脚本第 5 行
[WHX/Task] done ok=false cost=93ms
```

## 常见问题

**Q：必须用命令吗？能点界面吗？**
A：设备端 App 也可以导入脚本后点「运行」，但**编辑只在 PC 上**（项目设计如此）。
用命令更顺，尤其配合 `watch`。

**Q：脚本放在别的盘可以吗？**
A：可以，`run` / `watch` 收任意路径，比如 `python tools/whx.py run D:\work\a.js`。

**Q：怎么知道某个 API 怎么调？**
A：看 [api.md](api.md)；或者直接看 `scripts/hello.js` 的注释。

**Q：跑起来说「连接 helper 失败 / helper 进程已退出」？**
A：root 没授权。手机上看一眼有没有「超级用户请求」弹窗，点「永久记住」+「允许」。
也可以试 `python tools/whx.py run app/src/main/assets/scripts/grant_root.js` 自动点。

**Q：想让它每天定时跑？**
A：`python tools/whx.py schedule add my_task.js --time 08:30`（到点由设备自己触发）。
