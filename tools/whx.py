#!/usr/bin/env python3
"""whx —— PC 端开发工具链。

项目书的原则是「PC 上编程、设备只负责运行」，这个脚本把两边的往返做顺：
推脚本、跑脚本、看日志、抓截图、打包小精灵，全部一条命令。

用法：
    python tools/whx.py devices
    python tools/whx.py ls
    python tools/whx.py run app/src/main/assets/scripts/demo.js
    python tools/whx.py run demo.js --no-wait
    python tools/whx.py log
    python tools/whx.py shot dist/screen.png
    python tools/whx.py pack app/src/main/assets/scripts/demo.js --label 小鱼 --pkg com.whx.sprite.demo

依赖：adb 在 PATH 里，设备已 root 且已给应用授权。
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time

APP_ID = "com.whx.automation"
ACTIVITY = APP_ID + "/com.whx.automation.MainActivity"
REMOTE_TMP = "/data/local/tmp/whx_script.js"
LOG_TAGS = ["WHX/Script", "WHX/Task"]
DONE_MARK = "WHX/Task: done"


# ---------------------------------------------------------------- adb 封装
class Adb:
    def __init__(self, serial: str | None = None):
        self.adb = shutil.which("adb") or "adb"
        self.serial = serial

    def _base(self):
        cmd = [self.adb]
        if self.serial:
            cmd += ["-s", self.serial]
        return cmd

    def sh(self, *args, check=True, timeout=60, binary=False):
        cmd = self._base() + list(args)
        result = subprocess.run(cmd, capture_output=True, timeout=timeout)
        if check and result.returncode != 0:
            raise RuntimeError("%s 失败: %s" % (" ".join(args),
                                                result.stderr.decode("utf-8", "replace").strip()))
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    def shell(self, cmd, check=True, timeout=60):
        return self.sh("shell", cmd, check=check, timeout=timeout)

    def su(self, cmd, check=True, timeout=60):
        """在 root 下执行（su 已永久授权则不会弹窗）。"""
        return self.sh("shell", "su -c '%s'" % cmd.replace("'", "'\\''"), check=check, timeout=timeout)


def parse_devices(adb: Adb) -> list[tuple[str, str]]:
    out = adb.sh("devices")
    found = []
    for line in out.splitlines()[1:]:
        line = line.strip()
        if not line or "\t" not in line:
            continue
        serial, state = line.split("\t", 1)
        if state.strip() == "device":
            found.append((serial, state.strip()))
    return found


def pick_device(adb: Adb, wanted: str | None) -> Adb:
    if wanted:
        return Adb(wanted)
    devices = parse_devices(adb)
    if not devices:
        raise SystemExit("没有可用设备：先 adb connect 或启动模拟器")
    return Adb(devices[0][0])


def app_uid(adb: Adb) -> str:
    """拿应用 data 目录的属主 uid:gid，推脚本后要把权限改回去。"""
    out = adb.su("stat -c %%u:%%g /data/data/%s/files" % APP_ID, check=False)
    out = out.strip()
    if not re.match(r"^\d+:\d+$", out):
        raise SystemExit("拿不到应用 uid（root 没授权？输出：%r）" % out)
    return out


# ---------------------------------------------------------------- 子命令
def cmd_devices(args):
    adb = Adb()
    devices = parse_devices(adb)
    if not devices:
        print("没有可用设备")
        return 1
    for serial, _ in devices:
        d = Adb(serial)
        model = d.shell("getprop ro.product.model", check=False).strip()
        sdk = d.shell("getprop ro.build.version.sdk", check=False).strip()
        root = d.su("id -u", check=False).strip()
        size = d.shell("wm size", check=False).strip().splitlines()[-1]
        print("%-18s %-22s API %-3s root=%s  %s"
              % (serial, model, sdk, "yes" if root == "0" else "no", size))
    return 0


def cmd_ls(args):
    adb = pick_device(Adb(), args.serial)
    internal = adb.su("ls /data/data/%s/files/scripts 2>/dev/null" % APP_ID, check=False)
    print("内置脚本（assets，只读）:")
    for name in ("demo.js", "long_task.js", "stress.js"):
        print("  " + name)
    print("已导入脚本（/data/data/%s/files/scripts）:" % APP_ID)
    names = [n.strip() for n in internal.splitlines() if n.strip()]
    print("\n".join("  " + n for n in names) if names else "  （无）")
    return 0


def do_run(adb: Adb, src: str, timeout: float, wait: bool = True,
           echo: bool = True, cold: bool = False) -> int:
    """推脚本 → 冷启动运行 → 跟日志。返回脚本的成败（0 成功 / 1 失败）。"""
    name = os.path.basename(src)

    if not os.path.exists(src):
        if echo:
            print("本地没有 %s，按设备上已有的脚本名处理" % src)
        installed = adb.su("ls /data/data/%s/files/scripts" % APP_ID, check=False)
        if name not in installed.split():
            raise SystemExit("设备上也没有 %s" % name)
    else:
        if echo:
            print("推送脚本 %s → 设备" % name)
        adb.sh("push", src, REMOTE_TMP)
        uid = app_uid(adb)
        adb.su("cp %s /data/data/%s/files/scripts/%s" % (REMOTE_TMP, APP_ID, name))
        adb.su("chown %s /data/data/%s/files/scripts/%s" % (uid, APP_ID, name))
        adb.su("chmod 660 /data/data/%s/files/scripts/%s" % (APP_ID, name))
        adb.su("rm -f %s" % REMOTE_TMP)

    # 冷启动：先 force-stop 再带参数启动。
    # 实测热启动（activity 已在栈顶）时 onNewIntent 那条路会静默不执行，
    # 冷启动可以保证 onCreate 路径必然生效，也让每次运行的状态干净。
    if echo:
        print("运行 %s" % name)
    proc = start_logcat(adb) if wait else None
    if cold:
        # 冷启动只在需要"干净状态"时用：会杀掉进程，无障碍服务会被系统摘掉
        adb.shell("am force-stop %s" % APP_ID, check=False)
        time.sleep(0.4)
    # 用广播而不是 Activity intent：实测界面不在前台时 am start --es 会静默不执行
    # -f 0x00000020 = FLAG_INCLUDE_STOPPED_PACKAGES：
    # 应用被 force-stop 过会进入 stopped 状态，默认收不到广播（实测卡住无输出）
    adb.shell("am broadcast -a com.whx.automation.RUN_SCRIPT -f 0x00000020 "
              "-n %s/.RunScriptReceiver --es script %s" % (APP_ID, name))
    if not wait:
        return 0
    return consume_log(proc, wait_done=True, timeout=timeout)


def cmd_run(args):
    sys.stdout.reconfigure(line_buffering=True)
    adb = pick_device(Adb(), args.serial)
    code = do_run(adb, args.script, args.timeout, wait=not args.no_wait,
                  cold=args.cold)
    if args.no_wait:
        print("已启动（不等结果）。用 `python tools/whx.py log` 看日志")
    return code


def cmd_watch(args):
    """监听本地脚本变化，保存即推送并运行。

    这是"写脚本"最需要的循环：改一行 → 存盘 → 立刻能看到结果，
    不用手动 adb push、不用在手机上点运行。
    """
    sys.stdout.reconfigure(line_buffering=True)
    adb = pick_device(Adb(), args.serial)
    src = args.script
    if not os.path.exists(src):
        raise SystemExit("要监听的脚本不存在: %s" % src)

    print("监听 %s（Ctrl-C 退出），改动后自动运行" % src)
    last_stamp = os.path.getmtime(src)
    runs = 0

    # 先跑一次，省得改了才看到效果
    runs += 1
    print("\n===== 第 1 次运行 %s =====" % time.strftime("%H:%M:%S"))
    try:
        do_run(adb, src, args.timeout, echo=False)
    except Exception as exc:
        print("运行出错: %s" % exc)

    try:
        while True:
            time.sleep(args.interval)
            try:
                stamp = os.path.getmtime(src)
            except OSError:
                continue
            if stamp == last_stamp:
                continue
            # 编辑器保存常常连续触发多次，等文件稳定
            stable = stamp
            for _ in range(10):
                time.sleep(0.1)
                now = os.path.getmtime(src)
                if now == stable:
                    break
                stable = now
            last_stamp = os.path.getmtime(src)
            runs += 1
            print("\n===== 第 %d 次运行 %s（检测到改动）=====" % (runs, time.strftime("%H:%M:%S")))
            try:
                do_run(adb, src, args.timeout, echo=False)
            except Exception as exc:
                print("运行出错: %s" % exc)
    except KeyboardInterrupt:
        print("\n已停止监听（共运行 %d 次）" % runs)
    return 0


def start_logcat(adb: Adb):
    """先挂上日志，再触发动作 —— 否则脚本可能几毫秒就跑完，日志还没接上（实测踩过）。"""
    cmd = adb._base() + ["logcat", "-T", "1"] + sum([["-s", t] for t in LOG_TAGS], [])
    return subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                            encoding="utf-8", errors="replace")


def consume_log(proc, wait_done: bool, timeout: float = 120) -> int:
    started = time.time()
    failed = False
    try:
        for line in proc.stdout:
            line = line.rstrip("\n")
            if not any(tag in line for tag in LOG_TAGS):
                continue
            if noisy_stack_frame(line):
                continue
            print(clean_log(line))
            if "E WHX/Script" in line:
                failed = True
            if wait_done and DONE_MARK in line:
                break
            if time.time() - started > timeout:
                print("（超时 %.0f 秒，停止跟随；任务可能还在跑）" % timeout)
                break
    except KeyboardInterrupt:
        print("\n（已停止跟随日志，任务仍在设备上运行）")
    finally:
        proc.terminate()
    return 1 if failed else 0


def follow_log(adb: Adb, wait_done: bool, timeout: float = 120) -> int:
    return consume_log(start_logcat(adb), wait_done, timeout)


def noisy_stack_frame(line: str) -> bool:
    """丢掉 Java 栈帧，只留 JS 行号。

    完整栈在 logcat 里还能查到；命令行只显示对脚本作者有用的那一层，
    否则一个 JS 报错会刷十几行 java.lang.Thread.run。
    """
    if chr(9) + "at " not in line:
        return False
    return "at JavaScript" not in line


def clean_log(line: str) -> str:
    """把 logcat 前缀去掉，只留标签与内容。"""
    m = re.match(r"^\S+\s+\S+\s+\d+\s+\d+\s+[VDIWEF]\s+(\S+)\s*:\s*(.*)$", line)
    if m:
        return "[%s] %s" % (m.group(1), m.group(2))
    return line


def cmd_log(args):
    adb = pick_device(Adb(), args.serial)
    print("跟随日志（Ctrl-C 退出）")
    return follow_log(adb, wait_done=False, timeout=args.timeout)


def cmd_shot(args):
    adb = pick_device(Adb(), args.serial)
    out_dir = os.path.dirname(os.path.abspath(args.path))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(args.path, "wb") as fh:
        fh.write(adb.sh("exec-out", "screencap", "-p", binary=True, timeout=60))
    print("截图已保存: %s（%.1f KB）" % (args.path, os.path.getsize(args.path) / 1024.0))
    return 0


def read_pref(xml: str, key: str) -> str:
    """从 SharedPreferences XML 里取一个字符串值。

    SharedPreferences 会把换行写成 &#10; 之类的 XML 实体，
    不还原的话整串会挤成一行（实测踩过）。
    """
    m = re.search(r'<string name="%s">(.*?)</string>' % re.escape(key), xml, re.S)
    if not m:
        return ""
    text = m.group(1)
    for entity, ch in (("&#10;", "\n"), ("&#13;", ""), ("&lt;", "<"),
                       ("&gt;", ">"), ("&quot;", '"'), ("&apos;", "'"), ("&amp;", "&")):
        text = text.replace(entity, ch)
    return text


def cmd_schedule(args):
    """定时任务：每天某时刻运行脚本（设备端 AlarmManager 触发，不需要 PC 在线）。"""
    adb = pick_device(Adb(), args.serial)
    receiver = APP_ID + "/.schedule.ScheduleReceiver"

    if args.action == "add":
        hh, mm = args.time.split(":")
        adb.shell("am broadcast -a com.whx.automation.SCHEDULE_ADD -f 0x00000020 -n %s "
                  "--es script %s --ei hour %d --ei minute %d"
                  % (receiver, os.path.basename(args.script), int(hh), int(mm)))
        print("已添加：每天 %s 运行 %s" % (args.time, os.path.basename(args.script)))
        print("（到点由设备自己触发，PC 不需要在线；开机后会自动重挂）")
        return 0

    if args.action == "remove":
        adb.shell("am broadcast -a com.whx.automation.SCHEDULE_REMOVE -f 0x00000020 "
                  "-n %s --es script %s"
                  % (receiver, os.path.basename(args.script)))
        print("已删除与 %s 相关的计划" % os.path.basename(args.script))
        return 0

    # list：直接读应用的 SharedPreferences，比从日志里抓更可靠
    xml = adb.su("cat /data/data/%s/shared_prefs/whx_schedules.xml" % APP_ID, check=False)
    raw = read_pref(xml, "entries")
    if not raw.strip():
        print("没有定时任务")
        return 0
    print("定时任务：")
    for line in raw.splitlines():
        parts = line.split("|")
        if len(parts) == 3:
            print("  %s:%s  %s" % (parts[1], parts[2], parts[0]))
        return 0
    print("定时任务：")
    for line in m.group(1).split("\n"):
        parts = line.split("|")
        if len(parts) == 3:
            print("  %s:%s  %s" % (parts[1], parts[2], parts[0]))
    return 0


A11Y_SERVICE = APP_ID + "/com.whx.automation.access.WhxAccessibilityService"


def cmd_ui(args):
    """无障碍服务开关：脚本读界面文字的前提。"""
    adb = pick_device(Adb(), args.serial)
    if args.action == "enable":
        current = adb.shell("settings get secure enabled_accessibility_services",
                            check=False).strip()
        services = [s.strip() for s in current.split(":") if s.strip()]
        if A11Y_SERVICE not in services:
            services.append(A11Y_SERVICE)
        adb.su("settings put secure enabled_accessibility_services '%s'" % ":".join(services))
        adb.su("settings put secure accessibility_enabled 1")
        print("已启用无障碍服务（脚本里可用 texts() / findText() / clickText()）")
        return 0
    if args.action == "disable":
        current = adb.shell("settings get secure enabled_accessibility_services",
                            check=False).strip()
        services = [s.strip() for s in current.split(":") if s.strip() and s.strip() != A11Y_SERVICE]
        if services:
            adb.su("settings put secure enabled_accessibility_services '%s'" % ":".join(services))
        else:
            adb.su("settings delete secure enabled_accessibility_services")
        print("已关闭无障碍服务")
        return 0
    # status
    current = adb.shell("settings get secure enabled_accessibility_services",
                        check=False).strip()
    enabled = A11Y_SERVICE in current
    print("无障碍服务: %s" % ("已启用" if enabled else "未启用"))
    print("（启用：python tools/whx.py ui enable）")
    return 0


def cmd_history(args):
    """查运行历史：无人值守任务出问题后，事后追溯用。"""
    adb = pick_device(Adb(), args.serial)
    xml = adb.su("cat /data/data/%s/shared_prefs/whx_history.xml" % APP_ID, check=False)
    raw = read_pref(xml, "entries")
    if not raw.strip():
        print("还没有运行记录")
        return 0
    lines = [l for l in raw.splitlines() if l.strip()]
    print("最近 %d 条运行记录（新的在前）：" % min(len(lines), args.limit))
    for line in lines[: args.limit]:
        parts = line.split("|")
        if len(parts) < 4:
            continue
        ok = "成功" if parts[2] == "1" else "失败"
        err = ("  " + parts[4]) if len(parts) > 4 and parts[4] else ""
        print("  %s  %s  %-20s %6sms%s" % (parts[0], ok, parts[1], parts[3], err))
    return 0


def cmd_pack(args):
    packer = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "packer", "make_sprite.py")
    cmd = [sys.executable, packer,
           "--shell", args.shell,
           "--script", args.script,
           "--label", args.label,
           "--pkg", args.pkg,
           "--out", args.out]
    if args.no_autostart:
        cmd.append("--no-autostart")
    return subprocess.call(cmd)


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    p = argparse.ArgumentParser(prog="whx", description="WHX Automation PC 端工具链")
    p.add_argument("--serial", default=None, help="指定设备序列号")
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("devices", help="列出设备")
    sp.set_defaults(func=cmd_devices)

    sp = sub.add_parser("ls", help="列出设备上的脚本")
    sp.set_defaults(func=cmd_ls)

    sp = sub.add_parser("run", help="推送并运行脚本")
    sp.add_argument("script", help="本地脚本路径，或设备上已有的脚本名")
    sp.add_argument("--no-wait", action="store_true", help="不等结果，立即返回")
    sp.add_argument("--cold", action="store_true",
                    help="先 force-stop 再启动（状态干净，但会摘掉无障碍服务）")
    sp.add_argument("--timeout", type=float, default=180, help="等待秒数，默认 180")
    sp.set_defaults(func=cmd_run)

    sp = sub.add_parser("watch", help="监听脚本改动并自动运行")
    sp.add_argument("script", help="本地脚本路径")
    sp.add_argument("--interval", type=float, default=0.6, help="轮询间隔秒，默认 0.6")
    sp.add_argument("--timeout", type=float, default=180)
    sp.set_defaults(func=cmd_watch)

    sp = sub.add_parser("log", help="跟随脚本日志")
    sp.add_argument("--timeout", type=float, default=3600)
    sp.set_defaults(func=cmd_log)

    sp = sub.add_parser("shot", help="截图到 PC")
    sp.add_argument("path", help="保存路径，例如 dist/screen.png")
    sp.set_defaults(func=cmd_shot)

    sp = sub.add_parser("schedule", help="定时任务（每天某时刻运行）")
    sp.add_argument("action", choices=["add", "list", "remove"])
    sp.add_argument("script", nargs="?", default="", help="脚本名")
    sp.add_argument("--time", default="08:00", help="HH:mm，默认 08:00")
    sp.set_defaults(func=cmd_schedule)

    sp = sub.add_parser("ui", help="无障碍服务开关（读界面文字）")
    sp.add_argument("action", nargs="?", default="status", choices=["enable", "disable", "status"])
    sp.set_defaults(func=cmd_ui)

    sp = sub.add_parser("history", help="查看运行历史")
    sp.add_argument("--limit", type=int, default=10)
    sp.set_defaults(func=cmd_history)

    sp = sub.add_parser("pack", help="打包成小精灵 APK")
    sp.add_argument("script")
    sp.add_argument("--shell", default="app/build/outputs/apk/release/app-release.apk")
    sp.add_argument("--label", required=True)
    sp.add_argument("--pkg", required=True)
    sp.add_argument("--out", default=None)
    sp.add_argument("--no-autostart", action="store_true")
    sp.set_defaults(func=cmd_pack)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
