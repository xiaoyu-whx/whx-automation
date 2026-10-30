# 验收清单

每次改动后跑一遍这套，全部通过才算"可用"。下面的数字都是**实测**（雷电 9 / Android 9 /
API 28 / x86_64 / 720×1280 / 2026-10-31）。

## 0. 一条命令跑完全部（推荐）

```bash
python tools/smoke.py            # 构建 → 单测 → 安装 → 14 项端到端检查
python tools/smoke.py --skip-build
```

实测输出：

```
[通过] 构建 release            APK 3.14 MB
[通过] 单元测试
[通过] 安装 release 包
[通过] 启用无障碍
[通过] 找图与点击（find_image_demo）
[通过] 失败脚本退出码为 1
[通过] 任务链顺序执行
[通过] 应用控制（启动/强停）
[通过] 读取界面文本            节点数 16
[通过] 运行历史可查
[通过] 定时任务增删查
[通过] 打包小精灵（含图标）      3.13 MB

汇总：14 项，通过 13，失败 0，跳过 1（新装小精灵需人工点一次「允许」）
```

失败时退出码为 1，可直接接 CI。下面的章节是它覆盖不到的细节与人工项。

## 1. 单元测试

```bash
./gradlew :core:test :helper:test :app:testDebugUnitTest
```

**期望**：`BUILD SUCCESSFUL`，**71 个用例，0 失败**。

| 模块 | 测试类 | 用例 | 覆盖 |
| --- | --- | --- | --- |
| core | ColorTest | 9 | 颜色解析、容差、通道 |
| core | VisionTest | 16 | 取色/找色/多点找色/找图/裁剪/越界/帧偏移 |
| core | FrameDiffTest | 6 | 变化率、bbox、阈值、尺寸校验 |
| helper | AxisMapperTest | 7 | 坐标换算（恒等映射、1080p、交换开关、夹紧） |
| app | ScriptConfigTest | 10 | 配置解析/顺序/默认值/容错/校验 |
| app | ScheduleStoreTest | 6 | 定时任务解析、范围校验、下次触发 |
| app | RunHistoryTest | 5 | 历史记录解析、竖线转义、可读输出 |
| app | RunScriptReceiverTest | 5 | 运行时配置 key=value 解析（空格/等号/异常项） |
| helper | InputProbeTest | 7 | 真实 `getevent -pl` 样本解析（跳过非触摸设备/无 SLOT 轴/缺 Y 轴/空输入） |

## 2. 构建

```bash
./gradlew assembleRelease
```

**期望**：`app/build/outputs/apk/release/app-release.apk`，**约 3.13 MB**。

## 3. CLI 冒烟

```bash
export PATH="$ANDROID_HOME/platform-tools:$PATH"

python tools/whx.py devices        # 期望：显示设备、API、root=yes、分辨率
python tools/whx.py ls             # 期望：列出内置脚本
python tools/whx.py run app/src/main/assets/scripts/find_image_demo.js
python tools/whx.py shot dist/screen.png
python tools/whx.py history --limit 5
python tools/whx.py schedule list
python tools/whx.py ui enable      # 无障碍（脚本读界面文字的前提）
python tools/whx.py run app/src/main/assets/scripts/ui_demo.js
```

**期望**（run 的输出）：

```
[WHX/Script] [info] 找图命中 templates/title.png 得分 1.000 位置 30,140
[WHX/Script] [info] 标题命中中心 230,175
[WHX/Script] [info] 已点击标题
[WHX/Task] done ok=true cost=701ms
```

## 4. 端到端（关键路径必须人工过）

| # | 操作 | 期望 |
| --- | --- | --- |
| 1 | 装 APK → 打开 | 状态面板显示 `root 已就绪(uid=0)`、两个后端名 |
| 2 | 点「运行」跑 `demo.js` | 日志出现截图、取色、找色、点击，`done 成功` |
| 3 | `whx run` 一条命令 | 推送 → 运行 → 日志回显 → 退出码 0 |
| 4 | `whx run` 跑必失败脚本 | 回显异常 + `at JavaScript.<eval>(?:行号)`，退出码 1 |
| 5 | `whx watch` 后改脚本 | 自动重跑并显示新日志 |
| 6 | 打包小精灵（release 壳） | 安装后标题/包名正确，打开即跑 |
| 7 | 小精灵填参数再运行 | `配置: {x=100,...}`，按新值点击 |
| 8 | 小精灵内找图 | `找图命中 … 得分 1.000` |
| 9 | 定时任务到点 | `闹钟触发` → `done ok=true` → 自动续次日 |
| 10 | 失败重试（`retry=2`） | 共 3 次尝试，间隔 2 秒 |
| 11 | `ui enable` 后在系统设置里跑 `ui_demo.js` | `界面文本节点 17 个`、`找「设置」→ 256,127` |
| 12 | `ui_click_demo.js` 点击「电池」 | Activity 变为 `Settings$PowerUsageSummaryActivity` |
| 13 | `whx --package <精灵包> run demo.js` | 精灵的绑定脚本被广播执行（需先授权 root） |
| 14 | `whx --package <精灵包> schedule add` | 精灵的定时任务与主应用互不干扰 |

## 5. 性能基线（回归对比用）

| 指标 | 数值 | 说明 |
| --- | --- | --- |
| 截图（720p，端到端） | 19.6 ms | 阶段二实测，P50 |
| 找色（全屏未命中） | 1.96 ms | 阶段三 |
| 找图 160×120 | 14.2 ms | 阶段三 |
| 点击（hold 30ms） | 32 ms | 阶段四 |
| 找图+点击一轮 | 57.6 ms（release） | 阶段八/十二 |
| 500 次取色（JS 桥接） | 2~3 ms（release） | 阶段十二，debug 为 27ms |
| 冷启动到可运行 | ~250 ms | 阶段二 |
| APK 体积 | 3.13 MB（release） | 阶段十二 |

> 性能数字变化超过 30% 就要查：可能是误加了逐像素拷贝、丢了帧缓存、或换了后端。

## 6. 回归时最容易踩的点

1. **改了坐标/注入逻辑**：必须同时跑 `AxisMapperTest` 和真机点击自检 ——
   历史上"轴交换"误判就是靠界面选中标记移动才发现的。
2. **开了 R8**：必须跑真实链路（截图/找图/点击），光看日志不报错不够；
   反射与动态代理的类名被裁会**静默失效**。
3. **改了 packer**：注意 zip 同名条目，apksigner 会拒绝签名。
4. **模板换了界面**：模板必须取自将要匹配的那个界面（开发包 ≠ 小精灵）。
5. **用了 `--cold`**：force-stop 会摘掉无障碍服务，之后要重新 `ui enable`。
6. **PC 驱动脚本**：走广播 `RUN_SCRIPT`（不是 Activity intent）；广播要带
   `-f 0x00000020`，且必须先挂 logcat 再发广播，否则几毫秒结束的脚本日志会全丢。
