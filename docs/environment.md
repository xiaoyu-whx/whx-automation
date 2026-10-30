# 环境现状与待装项

记录这台机器上**实际验证过**的事实，以及 M1 之前必须补齐的东西。

## 已具备（2026-10-30 实测）

| 项 | 版本 / 路径 | 状态 |
| --- | --- | --- |
| JDK | 21.0.9 LTS，`JAVA_HOME=D:\Java\1.JAVA`，`javac` 可用 | ✅ |
| ADB | platform-tools 37.0.1，`C:\Users\1\AppData\Local\Android\platform-tools\adb.exe` | ✅ |
| 雷电模拟器 9 | `D:\leidian\LDPlayer9\dnplayer.exe`，运行中，adb 端口 5555 | ✅ |
| 模拟器系统 | Android 9（API 28），720×1280，SU 可用 | ✅ |
| Python | 3.8.6，numpy 1.24.4 / opencv 4.12.0 / flask / websockets | ✅ |
| Node | v24.21.0 | ✅ |

> 注：JDK 是 **21**，不是 25。AGP 8.x 要求 JDK 17+，21 完全够用，不需要再装。

## 缺失（M1 开始前必须装）

| 项 | 用途 | 装法（规划，尚未执行） |
| --- | --- | --- |
| Android SDK cmdline-tools | `sdkmanager`，装 platform/build-tools | 官方 zip 解压到 `D:\Android\Sdk` |
| Android SDK Platform 34 | 编译目标 | `sdkmanager "platforms;android-34"` |
| Build-Tools 34.0.0 | aapt2 / zipalign / apksigner | `sdkmanager "build-tools;34.0.0"` |
| Gradle 8.x | 构建 | 用 **wrapper**（工程自带，首次运行自动下载） |

**总计约 1~2GB 下载**。`ANDROID_HOME=D:\Android\Sdk` 需写入用户环境变量。

## 模拟器的两处设置（人工确认）

1. **root**：雷电设置 → 其他设置 → `ROOT 权限` 打开（当前 SU 已可用）
2. **分辨率**：固定 720×1280，DPI 建议 320，**不要开"自适应"**

## 端口与转发

```
雷电实例 1: 127.0.0.1:5555   （adb）
Agent 服务: 设备内 0.0.0.0:7911
PC 访问方式: adb forward tcp:7911 tcp:7911  →  连 127.0.0.1:7911
```

多开时第 N 台实例用 `5555 + 2×(N-1)`，agent 端口统一 7911，靠不同 `adb forward`
的本地端口（7911、7912、7913…）区分。

## 复查命令

```bash
java -version
adb devices -l
node --version
python -c "import cv2, numpy; print(cv2.__version__, numpy.__version__)"
```
