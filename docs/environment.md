# 环境现状与待装项

只记录**实测过**的事实。M1 之前必须补齐的项单独标出。

## 已具备（2026-10-30 实测）

| 项 | 版本 / 路径 | 状态 |
| --- | --- | --- |
| JDK | **21.0.9 LTS**，`JAVA_HOME=D:\Java\1.JAVA`，`javac` 可用 | ✅ |
| ADB | platform-tools 37.0.1，`C:\Users\1\AppData\Local\Android\platform-tools\adb.exe` | ✅ |
| 雷电模拟器 9 | `D:\leidian\LDPlayer9\dnplayer.exe`，运行中，adb 端口 5555 | ✅ |
| 模拟器系统 | Android 9（API 28），720×1280，**root 可用（`su` 已验证）** | ✅ |
| Python | 3.8.6（numpy / opencv / flask 可用） | ✅ |
| Node | v24.21.0 | ✅ |

> JDK 是 21，不是 25。AGP 8.x 要求 17+，21 够用，不需要另装。
> Android Studio 装不装可选：命令行 `gradlew assembleDebug` 同样能出 APK，
> 装它的唯一收益是布局预览与真机调试面板。

## 缺失（M1 开始前必装）

| 项 | 用途 | 大小 |
| --- | --- | --- |
| `cmdline-tools` | 提供 `sdkmanager` | ~120MB |
| `platforms;android-34` | 编译期 API 存根 | ~100MB |
| `build-tools;34.0.0` | `aapt2` / `d8` / `zipalign` / `apksigner` | ~60MB |
| Gradle（wrapper 自取） | 构建 | ~130MB |
| NDK（可选） | 只有在图像处理被证明是瓶颈时才用 | ~2GB |
| CMake（可选） | 同上，随 NDK 一起装 | ~200MB |

> 阶段一按项目书要求把 NDK/CMake 装上备用，但**不引入 C++ 代码** ——
> 项目书原则：先测量，再决定是否使用 C++。Java 层已实测达标（截图 11.4ms / 点击 31.3ms）。

建议装到 `D:\Android\Sdk`，并写入 `ANDROID_HOME`。

**首次构建另需下载** AGP + QuickJS AAR（几百 MB，落在 `~/.gradle` 与 `~/.m2`）。

## M2 额外依赖

| 依赖 | 用途 | 来源 |
| --- | --- | --- |
| QuickJS AAR | 设备端 JS 引擎 | Maven Central（`com.github.taoweiji` 等现成封装） |
| libsu | 常驻 root shell | Maven Central |

> 这两个都要能访问 Maven 仓库。若下载被墙，先在 PC 上下载离线包，用
> `mavenLocal()` 或本地 `libs/` 引入。

## 实测基准（2026-10-30，雷电 9 / Android 9 / x86_64 / 720×1280）

用最小 Java 程序（`javac` + `d8` + `app_process`，不需要 Gradle）测得：

| 测量项 | 结果 |
| --- | --- |
| 进程内 `SurfaceControl.screenshot` | **3.5 ms** |
| HARDWARE → ARGB_8888 回读 | **5.7 ms** |
| `getPixels()` | **2.3 ms** |
| 截图合计 | **11.4 ms** |
| 9 条 evdev 事件写入 | **0.033 ms** |
| tap（含 30ms 按住） | **31.3 ms** |
| `adb exec-out screencap`（raw） | 141.6 ms |
| `input tap` | 134.6 ms |
| shell 里 9 条 `sendevent` | 210 ms（17ms/条进程开销） |

关键环境事实：

- `/dev/graphics`、`/dev/fb0` **不存在**，`/sys/class/graphics` 为空 → **无 framebuffer**
- 触摸设备 `/dev/input/event2`，`ABS_MT_POSITION_X max=1279`（=屏幕高），
  `ABS_MT_POSITION_Y max=719`（=屏幕宽），`ABS_MT_SLOT max=15` → **轴需交换**
- 截图在 **shell 身份（uid 2000）** 下即可成功；注入需要 **root**

## 构建（阶段一已验证）

```bash
cd <repo>
./gradlew assembleDebug          # 产物: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.whx.automation/.MainActivity
adb logcat -s WHX/Service
```

首次构建 58 秒，APK 14KB（debug、无资源、无第三方依赖）。

**镜像说明（重要）**：本机直连 `repo.maven.apache.org` 与 `plugins.gradle.org` 的 TLS
握手会被中断，因此 `settings.gradle` 中把阿里云镜像放在 Maven Central 之前；
`google()` / `dl.google.com` 直连正常，保持不变。换机器后若直连正常，可自行删掉镜像行。

## 网络前置检查（M1 动手前跑一次）

```bash
curl -sI https://dl.google.com/android/repository/repository2-3.xml | head -1   # SDK 源
curl -sI https://repo1.maven.org/maven2/ | head -1                              # Maven
```

两个都通才动手装，否则先解决代理。

## 模拟器设置（人工确认）

1. **root**：雷电设置 → 其他设置 → `ROOT 权限` 打开（已验证可用）
2. **分辨率**：720×1280，DPI 320，**关闭"自适应"**
3. **磁盘**：`/data` 至少留 2GB（脚本包与轨迹截图）

## 端口与转发

```
雷电实例 1  adb: 127.0.0.1:5555
设备端运行时监听: 127.0.0.1:7911
PC 访问: adb forward tcp:7911 tcp:7911  →  连 127.0.0.1:7911
```

多开时第 N 台实例用 `5555 + 2×(N-1)`；本地转发端口用 `7911、7912、7913…` 区分。

## 复查命令

```bash
java -version
adb devices -l
adb shell su -c id          # 应输出 uid=0(root)
node --version
python -c "import cv2, numpy; print(cv2.__version__, numpy.__version__)"
```

## 凭据与推送（2026-10-30 配置）

### 网络

本机直连 GitHub 会 TLS 失败，必须走本地代理（Clash 之类，监听 `127.0.0.1:7897`）：

```bash
curl -x http://127.0.0.1:7897 -o /dev/null -w "%{http_code}
" https://github.com   # 期望 200
```

代理**不稳定**：同一个请求可能一次 000 一次 200，所以 `git push` 偶尔报
`schannel: failed to receive handshake, SSL/TLS connection failed` —— **重试即可**，
不是配置错误。仓库里配了 `http.proxy`（仅本仓库，不动全局）。

### 凭据

token（PAT，`repo` 权限）已存进 **Windows 凭据管理器**，`git push` 不再需要贴 token。

存储方式：`git-credential-wincred`（Git 自带）

```bash
HELPER="$(git --exec-path)/git-credential-wincred.exe"
printf 'protocol=https
host=github.com
username=x-access-token
password=<TOKEN>

' | "$HELPER" store
```

凭据管理器里的条目（`cmdkey /list`，注意 MSYS 下要 `MSYS_NO_PATHCONV=1` 否则 `/list` 会被当路径）：

```
LegacyGeneric:target=git:https://github.com
LegacyGeneric:target=git:https://x-access-token@github.com
```

### 坑：忘了关掉的交互式选择器

DSH 自带的 git 在系统级 gitconfig 里写了：

```
credential.helper=helper-selector
```

这个选择器是**交互式**的，非交互环境（脚本、自动化）会直接**挂死**，`git push` 卡住没有任何输出。
trace 里能看到：

```
run_command: 'git credential-helper-selector get'
```

解决办法是在**仓库级**先把凭据链清空再加 wincred：

```bash
git config --local --replace-all credential.helper ""       # 空值 = 清空列表
git config --local --add credential.helper "$HELPER"
```

### 撤销 token 后怎么清理

```bash
HELPER="$(git --exec-path)/git-credential-wincred.exe"
printf 'protocol=https
host=github.com

' | "$HELPER" erase
```

或者：控制面板 → 凭据管理器 → Windows 凭据 → 删除 `git:https://github.com`。
