# 通信协议 · WHX Automation v1

PC 工具与 Android 端 **Automation App** 之间的全部约定。**实现以本文件为准**，改动先改这里。

> 适用范围：仅 PC 开发调试与脚本部署（项目书第五、六节）。打包成独立 APK 后，这条通道不启用 —— 脚本从 assets 或本地目录读取，不依赖 PC。

- 传输：TCP，默认端口 **7911**（设备侧监听 `127.0.0.1`，经 `adb forward` 访问）
- 字节序：全大端（网络序）
- 单连接可**流水线**：用 `seq` 匹配响应
- 事件（设备 → PC）与响应共用一条连接，靠 `type` 区分

## 0. 实现状态（2026-10-31）

本文档分两部分：**第 1~3 节是设计稿**（JSON 元数据 + 事件流，面向未来的多语言 SDK），
**本节是实现现状**。两者不一致时以本节 + 代码为准。

### 0.1 实际在用的帧格式（`helper/Proto.java`）

请求与响应同构，纯二进制，没有 JSON：

```
[int32 负载长度][1B op][1B 状态][其余为参数或数据]
状态：0 = OK，非 0 = 错误码
```

参数一律 `int32`（大端，`ByteBuffer` 默认序），blob 直接跟在后面。

### 0.2 已实现的 op

| op | 名称 | 请求参数 | 响应 |
| --- | --- | --- | --- |
| 1 | `CAPTURE` | w, h, rx, ry, rw, rh（region 全 0 = 整屏） | int32 宽、高 + ARGB 像素 |
| 2 | `TAP` | x, y, holdMs | 耗时 ms（double） |
| 3 | `SWIPE` | count, count×(x,y), durationMs | 耗时 ms |
| 4 | `PING` | 无 | 当前时间戳（int64） |
| 5 | `INFO` | 无 | UTF-8 文本 |
| 6 | `KEY` | keyCode | 耗时 ms |
| 7 | `SHELL` | UTF-8 命令 | `"exitCode|输出"` |
| 9 | `SHUTDOWN` | 无 | `{1}` |

### 0.3 设计稿里尚未实现的部分

- JSON 元数据 + blob 分层的负载布局（第 1 节）
- 事件流（`0x11xx`）、错误码细分（`0xE0xx`）
- `0x0204 key` / `0x0401~0x0403 app.*` / `0x0501~0x0505 script.*` 这组高层命令 ——
  应用层目前**直接调 Java 方法**（`HelperClient`），比走通用命令表更直接、更少一层序列化。

保留设计稿的原因是：以后要做 PC 端 SDK（Python/Node 直接连 helper）时，这套命令表就是接口定义。

## 1. 帧格式

```
偏移  长度  字段      说明
 0     2    magic    固定 0x58 0x59（"XY"）
 2     1    version  协议版本，当前 1
 3     1    type     0x01 REQ / 0x02 RESP / 0x03 EVENT / 0x04 ERROR
 4     2    code     命令码 / 错误码 / 事件码
 6     4    seq      请求序号；RESP 与 ERROR 原样带回；EVENT 为 0
10     4    length   负载总字节数
14     N    payload  负载
```

### 1.1 负载布局（所有帧统一）

```
[4B metaLen][metaLen 字节的 UTF-8 JSON][剩余字节 = 二进制块 blob]
```

纯控制命令 `blob` 为空（`length = 4 + len(meta)`）。统一布局让解析器只有一条路径。

### 1.2 一个完整的 ping（十六进制）

```
58 59 01 01 00 01 00 00 00 01 00 00 00 06   帧头
00 00 00 02 7B 7D                            metaLen=2, meta="{}"
```

## 2. 命令集

### 2.1 控制

| 码 | 名称 | 请求 meta | 响应 meta |
| --- | --- | --- | --- |
| `0x0001` | `ping` | `{"v":1}` | `{"v":1,"rt":"0.1.0","pid":1234}` |
| `0x0002` | `info` | `{}` | 见 2.2 |
| `0x0003` | `close` | `{}` | `{}`，随后关闭连接 |

### 2.2 `info` 响应

```json
{
  "v": 1,
  "rt": "0.1.0",
  "android": {"sdk": 28, "release": "9"},
  "device": {"model": "2509FPN0BC", "brand": "XIAOMI"},
  "screen": {"width": 720, "height": 1280, "density": 320, "rotation": 0},
  "helper": {"alive": true, "uid": 0, "pid": 4321},
  "grabber": {"mode": "surfacecontrol", "format": "argb8888", "hwreadback": true},
  "input": {"ready": true, "device": "/dev/input/event2",
            "absmax": {"x": 1279, "y": 719}, "swapped": true, "maxSlots": 16},
  "script": {"running": false, "name": null},
  "caps": ["screencap", "input", "vision", "script"]
}
```

- `helper.alive=false` → 运行时拒绝提供任何服务（截图与注入都依赖它）
- `input.ready=false` → PC 侧必须拒绝执行点击（宁可报错，不要静默失败）
- `input.swapped=true` → 触摸轴与屏幕轴交换（雷电 9 实测如此），仅供排查参考，
  交换由 helper 内部完成，上层永远传屏幕坐标
- `caps` 用于能力协商：PC 端按此决定可用功能，不猜版本

### 2.3 屏幕

| 码 | 名称 | 请求 meta | 响应 |
| --- | --- | --- | --- |
| `0x0101` | `screenshot` | `{"format":"raw"｜"jpeg"｜"png","quality":85,"region":[x,y,w,h]}` | meta + blob |
| `0x0102` | `uiDump` | `{"tree":true}` | meta `{"count":N,"nodes":[...]}`（M3 后再实现） |

`screenshot` 响应 meta：

```json
{"format":"raw","width":720,"height":1280,"stride":2880,"pixfmt":"rgba8888"}
```

- `raw`：`blob` 长 = `stride × height`，顺序 **R,G,B,A**，行优先无填充
- `jpeg`/`png`：`blob` 即文件字节，`stride`/`pixfmt` 省略
- `region` 由设备端裁剪，省传输

### 2.4 输入（同连接内**串行**）

| 码 | 名称 | 请求 meta |
| --- | --- | --- |
| `0x0201` | `tap` | `{"x":120,"y":340,"durationMs":60}` |
| `0x0202` | `swipe` | `{"points":[[120,900],[120,400]],"durationMs":300,"steps":20}` |
| `0x0203` | `multiTouch` | `{"fingers":[{"id":0,"points":[[100,500],[300,500]]}],"durationMs":400}` |
| `0x0204` | `key` | `{"code":3}` 或 `{"name":"HOME"}` |
| `0x0205` | `text` | `{"text":"你好"}` |

约定：
- `tap.durationMs` 默认 60，范围 30~1500；`≥ 800` 视为长按
- `swipe.points` 至少 2 点，设备端按 `steps` 插值，总时长误差 `± 10%`
- 坐标是**当前方向下的屏幕像素**，左上原点；轴向换算由设备端负责
- 响应统一带 `{"ms": 实际耗时}`

### 2.5 Shell

| 码 | 名称 | 请求 meta | 响应 meta |
| --- | --- | --- | --- |
| `0x0301` | `shell` | `{"cmd":"ls /sdcard","timeoutMs":5000,"asRoot":true}` | `{"code":0,"stdout":"…","stderr":"…","ms":8}` |

`asRoot=true` 复用常驻 `su` 会话（快）。输出超 256KB 截断并置 `truncated=true`。

### 2.6 App

| 码 | 名称 | 请求 meta | 响应 meta |
| --- | --- | --- | --- |
| `0x0401` | `launch` | `{"package":"com.android.settings"}` | `{"ok":true}` |
| `0x0402` | `forceStop` | `{"package":"…"}` | `{"ok":true}` |
| `0x0403` | `currentApp` | `{}` | `{"package":"…","activity":"…"}` |

### 2.7 脚本

| 码 | 名称 | 请求 meta | 响应 meta |
| --- | --- | --- | --- |
| `0x0501` | `script.push` | `{"name":"demo","version":"1.0.0","overwrite":true}` + **blob = zip 包** | `{"installed":true,"files":12,"bytes":83421}` |
| `0x0502` | `script.run` | `{"name":"demo","entry":"main.js","args":[]}` | `{"started":true,"runId":"r7"}` |
| `0x0503` | `script.stop` | `{"runId":"r7"}` | `{"stopped":true}` |
| `0x0504` | `script.status` | `{}` | `{"running":true,"runId":"r7","name":"demo","ms":1420}` |
| `0x0505` | `script.list` | `{}` | `{"packages":[{"name":"demo","version":"1.0.0","source":"push"|"assets","bytes":83421}]}` |

**脚本来源（对应项目书第六节的两种模式）**：`script.push` 推来的属于「外部导入」；
打包进 APK assets 的属于「内置脚本」，走同一个 `script.run` 接口，只是包来自不同位置。

**脚本包（zip）结构**：

```
manifest.json         {"name","version","entry","assets":["btn.png"],"desc"}
scripts/main.js       入口脚本
scripts/lib/*.js      可 require 的模块（相对路径）
assets/btn.png        模板图
assets/btn.png.json   {"w":720,"h":1280} 模板来源分辨率
```

推送是**原子**的：先解到临时目录，校验 manifest 与入口存在，再替换旧版本；失败保留旧版本。

### 2.8 事件（设备 → PC，`type=0x03`，`seq=0`）

| 码 | 名称 | meta |
| --- | --- | --- |
| `0x1101` | `log` | `{"level":"info","msg":"…","runId":"r7","ts":1234}` |
| `0x1102` | `step` | `{"runId":"r7","index":3,"name":"tap","ms":11,"changedRatio":0.042,"shot":"s3.png"}` |
| `0x1103` | `done` | `{"runId":"r7","ok":false,"error":"…","ms":1420}` |
| `0x1104` | `alert` | `{"reason":"grabber fallback","detail":"fb0 首帧全黑"}` |

PC 收到事件不回复，只记录/转发。

## 3. 错误码（`type=0x04`）

| 码 | 名称 | 含义 |
| --- | --- | --- |
| `0xE001` | `E_NO_ROOT` | 拿不到 root，运行时拒绝服务 |
| `0xE002` | `E_BAD_REQUEST` | JSON 非法、字段缺失、坐标越界 |
| `0xE003` | `E_TIMEOUT` | 执行超时 |
| `0xE004` | `E_UNSUPPORTED` | 未知命令码或本机不支持 |
| `0xE005` | `E_IO` | 读 fb0 / 写事件节点失败 |
| `0xE006` | `E_BUSY` | 资源被占用（如抓帧中） |
| `0xE007` | `E_NO_SCRIPT` | 脚本不存在 / 入口缺失 |
| `0xE008` | `E_SCRIPT_ERROR` | 脚本抛异常（`detail` 带堆栈首帧） |
| `0xE0FF` | `E_INTERNAL` | 未捕获异常 |

负载：`{"msg":"一句话","detail":"堆栈或原始错误"}`。

## 4. 连接生命周期

```
PC                                 设备
 │ TCP connect（经 adb forward）────▶
 │ REQ ping {"v":1} ────────────────▶
 │ ◀──────────────────────── RESP {"v":1,...}
 │ REQ info ────────────────────────▶
 │ ◀──────────── 校验 root.ok / input.ready / screen
 │ （流水线请求 + 事件流）
 │ REQ script.push（zip）───────────▶
 │ REQ script.run ──────────────────▶
 │ ◀──── EVENT log / step / done ×N
 │ REQ ping（心跳 5s）
 │ REQ close ───────────────────────▶
```

- **启动校验**：`helper.alive=false`、`input.ready=false` → PC 直接退出，不做降级
- **版本不一致**：PC 主动断开并报错，不做兼容
- **空闲**：30s 无请求，设备端断开并发 `alert`
- **重连**：PC 指数退避 0.2s→0.4s→0.8s→1.6s→3.2s，5 次后放弃
- **超时**：PC 默认 2s（截图 5s，脚本运行不限），超时后该 `seq` 作废

## 5. 安全边界

默认**只绑 `127.0.0.1`**，PC 通过 `adb forward tcp:7911 tcp:7911` 访问：

- 不暴露到局域网，不需要认证，也不怕同网段扫描
- 上云手机若必须直连 IP，则：绑 `0.0.0.0` + 防火墙/IP 白名单为**强制前置**，
  协议层不引入 token（多一个握手状态，换不来实质安全）

> 这条不做成"可选建议"：直连 IP 而没做白名单，等于把 root shell 开放给公网。

## 6. 兼容性

- `version` 只在整版不兼容时递增；新增命令码不升版本
- 未知命令码必须回 `E_UNSUPPORTED`，**不得断开连接**（便于能力探测）
- 所有 meta 字段都是可扩展的：解析方必须容忍多余字段，缺省字段必须有默认值
