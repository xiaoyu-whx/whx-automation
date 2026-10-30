# whx —— PC 端工具链

项目书的原则是「**PC 上编程、设备只负责运行**」。之前写完脚本得手动 `adb push` +
在手机上点运行，等于没有开发体验。`tools/whx.py` 把这条往返做顺。

## 命令

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

## 日志显示策略

logcat 里的 Java 栈对脚本作者没用，所以只保留：

- 异常首行（异常类型 + 消息）
- `at JavaScript.<eval>(?:行号)` —— **JS 报错的行号**
- 我们自己的 `[info]/[warn]/[error]/[done]` 行

完整栈仍在 `adb logcat -s WHX/Script` 里可查。
