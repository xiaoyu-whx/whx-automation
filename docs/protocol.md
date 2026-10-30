# 通信协议 · XiaoYu Agent v1

PC（`engine/`）与设备（`agent/`）之间的全部约定。**实现必须以本文件为准**，改动先改这里。

- 传输：TCP，默认端口 **7911**
- 编码：定长头 + 变长负载，二进制安全
- 字节序：全部 **大端（网络序）**
- 一条连接上可**流水线**（pipeline）发多个请求，用 `seq` 对应响应

## 1. 帧格式

```
偏移  长度  字段        说明
 0     2    magic      固定 0x58 0x59（ASCII "XY"）
 2     1    version    协议版本，当前 1
 3     1    type       0x01 REQ / 0x02 RESP / 0x03 EVENT / 0x04 ERROR
 4     2    code       命令码（REQ/RESP）或错误码（ERROR）
 6     4    seq        请求序号，客户端递增；RESP/ERROR 原样带回
10     4    length     负载总字节数（可变，见下）
14     N    payload    负载
```

### 1.1 负载布局（所有帧统一）

```
[4B metaLen][metaLen 字节的 UTF-8 JSON][剩余字节 = 二进制块 blob]
```

- 纯控制类命令：`blob` 为空，`length = 4 + len(meta)`
- 图像类命令：`meta` 描述格式，`blob` 是像素或压缩数据

统一布局的好处：解析器只有一条路径，加新命令不用改解码器。

### 1.2 一个完整的 ping 请求（十六进制）

```
58 59            magic "XY"
01               version 1
01               type REQ
00 01            code 0x0001 ping
00 00 00 01      seq 1
00 00 00 06      length 6
00 00 00 02      metaLen 2
7B 7D            meta "{}"
```

对应响应：

```
58 59 01 02 00 01 00 00 00 01 00 00 00 17
00 00 00 13 7B 22 76 22 3A 31 2C 22 61 67 ...   → meta {"v":1,"agent":"0.1.0","pid":1234}
```

## 2. 命令集

### 2.1 控制

| 码 | 名称 | 请求 meta | 响应 meta |
| --- | --- | --- | --- |
| `0x0001` | `ping` | `{"v":1}` | `{"v":1,"agent":"0.1.0","pid":1234}` |
| `0x0002` | `info` | `{}` | 见下 |
| `0x0003` | `close` | `{}` | `{}`（随后 agent 关闭连接） |

`info` 响应：

```json
{
  "v": 1,
  "agent": "0.1.0",
  "android": {"sdk": 28, "release": "9"},
  "device": {"model": "2509FPN0BC", "brand": "XIAOMI"},
  "screen": {"width": 720, "height": 1280, "rotation": 0},
  "root": {"ok": true, "uid": 0, "su": "/system/xbin/su"},
  "grabber": {"mode": "fb0", "pixfmt": "rgba8888", "fallback_screencap": false},
  "input": {"ready": true, "device": "/dev/input/event2",
            "absmax": {"x": 32767, "y": 32767}, "protocol": "B", "max_slots": 10}
}
```

- `grabber.mode`：`fb0` 或 `screencap`
- `input.ready=false` 时 Engine 必须拒绝执行任何点击（宁可报错，不要静默失败）

### 2.2 屏幕

| 码 | 名称 | 请求 meta | 响应 |
| --- | --- | --- | --- |
| `0x0101` | `screenshot` | `{"format":"raw"｜"jpeg"｜"png","quality":85,"region":[x,y,w,h]}` | meta + blob |
| `0x0102` | `uiDump` | `{"tree":true}` | meta `{"count":N,"nodes":[...]}` |

`screenshot` 响应 meta：

```json
{"format":"raw","width":720,"height":1280,"stride":2880,"pixfmt":"rgba8888"}
```
- `format=raw`：`blob` 长度 = `stride × height`，像素顺序 **R,G,B,A**，行优先，无填充
- `format=jpeg/png`：`blob` 即文件字节，`stride`/`pixfmt` 省略
- `region` 省略或为整屏时不做裁剪（裁剪由 Agent 做，省传输）

### 2.3 输入（同连接内**串行执行**）

| 码 | 名称 | 请求 meta |
| --- | --- | --- |
| `0x0201` | `tap` | `{"x":120,"y":340,"durationMs":60}` |
| `0x0202` | `swipe` | `{"points":[[120,900],[120,400]],"durationMs":300,"steps":20}` |
| `0x0203` | `multiTouch` | `{"fingers":[{"id":0,"points":[[100,500],[300,500]]},{"id":1,"points":[[400,500],[600,500]]}],"durationMs":400}` |
| `0x0204` | `key` | `{"code":3}` 或 `{"name":"HOME"}` |
| `0x0205` | `text` | `{"text":"你好"}` |

约定：
- `tap` 的 `durationMs` 默认 60，范围 30~1500；`> 800` 视为长按
- `swipe` 的 `points` 至少 2 点，Agent 按 `steps` 做线性/贝塞尔插值，总时长 `durationMs ± 10%`
- 坐标一律是**当前方向下的屏幕像素**，左上角原点；Agent 负责换算成轴值
- `text` 走 `input text`（UTF-8 不稳定时 Agent 内部改用 base64 → `am broadcast` 方案），延迟高属正常
- 响应 meta 统一带 `{"doneMs":12}`（实际耗时），便于 Engine 统计

### 2.4 Shell

| 码 | 名称 | 请求 meta | 响应 meta |
| --- | --- | --- | --- |
| `0x0301` | `shell` | `{"cmd":"ls /sdcard","timeoutMs":5000,"asRoot":true}` | `{"code":0,"stdout":"...","stderr":"...","ms":8}` |

`asRoot=true` 走常驻 root shell（复用一个 `su` 进程，快）；`false` 走普通 `sh`。
输出超过 256KB 时截断，`meta.truncated=true`。

### 2.5 App

| 码 | 名称 | 请求 meta | 响应 meta |
| --- | --- | --- | --- |
| `0x0401` | `launch` | `{"package":"com.android.settings"}` | `{"ok":true}` |
| `0x0402` | `forceStop` | `{"package":"..."}` | `{"ok":true}` |
| `0x0403` | `currentApp` | `{}` | `{"package":"...","activity":"..."}` |

### 2.6 事件（Agent → PC，单向）

| 码 | 名称 | meta |
| --- | --- | --- |
| `0x1001` | `log` | `{"level":"warn","msg":"fb0 fallback to screencap"}` |
| `0x1002` | `disconnect` | `{"reason":"idle timeout"}` |

PC 端收到 EVENT 不得回复，只记日志。

## 3. 错误

`type=0x04` 时 `code` 为错误码，`seq` 对应请求：

| 码 | 名称 | 含义 |
| --- | --- | --- |
| `0xE001` | `E_NO_ROOT` | 拿不到 root，Agent 拒绝服务 |
| `0xE002` | `E_BAD_REQUEST` | JSON 非法、字段缺失、坐标越界 |
| `0xE003` | `E_TIMEOUT` | 执行超时（shell/截图） |
| `0xE004` | `E_UNSUPPORTED` | 未知命令码或本机不支持 |
| `0xE005` | `E_IO` | 读 fb0 / 写事件节点失败 |
| `0xE006` | `E_BUSY` | 前一个截图未完成（可选，默认排队而非报错） |
| `0xE0FF` | `E_INTERNAL` | 未捕获异常，见 `detail` |

负载：`{"msg":"一句话","detail":"堆栈或原始错误"}`。

## 4. 连接生命周期

```
PC                                   Agent
 │  TCP connect ───────────────────────▶
 │  REQ ping {"v":1} ─────────────────▶
 │  ◀───────────────────── RESP {"v":1,...}
 │  REQ info ─────────────────────────▶
 │  ◀────────────────────────────────── 校验 1280×720、root.ok、input.ready
 │  （正常请求流水线）
 │  REQ ping（心跳，每 5s）───────────▶
 │  REQ close ────────────────────────▶
```

- **版本不一致**：PC 主动断开并报错，不做兼容
- **分辨率**：PC 记录实际尺寸，按模板标注的比例缩放模板（见 api.md §10）；仅当
  `input.ready=false` 时拒绝执行点击并退出
- **空闲**：30s 无请求，Agent 主动断开并发 `disconnect` 事件
- **重连**：PC 指数退避 0.2s → 0.4s → 0.8s → 1.6s → 3.2s，5 次失败后放弃
- **超时**：PC 侧默认 2s（截图 5s，shell 按请求值），超时后该 `seq` 作废，迟到的响应丢弃

## 5. 安全边界

Agent  listens on `0.0.0.0:7911`，**无认证** —— 这是刻意的简化，前提是：
- 只在模拟器 / 自有云手机内网使用
- 雷电视同 `127.0.0.1`（经 `adb forward`），不暴露到局域网
- 若要暴露到局域网，必须在防火墙层限制来源 IP（不做在协议里）

> 上云手机时如果厂商分配的是公网 IP，**必须先加 IP 白名单**，否则等于把 root shell 开放给公网。

## 6. 兼容性预留

- `version` 字段留给整版不兼容变更；新增命令码不需要升版本
- 未知命令码必须回 `E_UNSUPPORTED`，不能断开连接（便于 PC 端探测能力）
- 所有 meta 字段都是**可选扩展**的：PC 端解析必须容忍多余字段
