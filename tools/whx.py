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

# 组件必须用**绝对类名**：打包器只改包名（manifest package），类名是 AGP 按 namespace
# 展开的 com.whx.automation.*。用相对名（.RunScriptReceiver）在改过包名的小精灵里
# 会指向不存在的组件：广播返回成功但什么都不会发生（实测踩过）。
CLASS_PREFIX = "com.whx.automation."
MAIN_ACTIVITY = CLASS_PREFIX + "MainActivity"
RUN_RECEIVER = CLASS_PREFIX + "RunScriptReceiver"
SCHEDULE_RECEIVER = CLASS_PREFIX + "schedule.ScheduleReceiver"
A11Y_CLASS = CLASS_PREFIX + "access.WhxAccessibilityService"


def component(pkg: str, cls: str) -> str:
    return "%s/%s" % (pkg, cls)
REMOTE_TMP = "/data/local/tmp/whx_script.js"
LOG_TAGS = ["WHX/Script", "WHX/Task", "WHX/RunScript"]
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

    def broadcast_ping(self, pkg: str = APP_ID):
        """发一个无害广播，确认组件能收到（用于自检）。"""
        return self.shell("am broadcast -a com.whx.automation.SCHEDULE_LIST "
                          "-f 0x00000020 -n %s" % component(pkg, SCHEDULE_RECEIVER), check=False)

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


def app_uid(adb: Adb, pkg: str = APP_ID) -> str:
    """拿应用 data 目录的属主 uid:gid，推脚本后要把权限改回去。"""
    out = adb.su("stat -c %%u:%%g /data/data/%s/files" % pkg, check=False)
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
    """列出目标包里的脚本与模板。

    从 APK 里真实读取（pull 一份再解 zip），而不是写死列表 ——
    小精灵的内置脚本跟主应用完全不同，写死会误导。
    """
    adb = pick_device(Adb(), args.serial)
    pkg = args.package

    raw = adb.shell("pm path %s" % pkg, check=False).strip()
    if not raw.startswith("package:"):
        print("包不存在或未安装: %s" % pkg)
        return 1
    apk = raw.split("package:", 1)[1].splitlines()[0].strip()
    print("包: %s" % pkg)
    print("APK: %s" % apk)

    import tempfile
    import zipfile
    local = None
    builtin, templates, bundle = [], [], False
    try:
        with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as tmp:
            local = tmp.name
        adb.sh("pull", apk, local)
        with zipfile.ZipFile(local) as z:
            names = z.namelist()
        builtin = sorted(n[len("assets/scripts/"):] for n in names
                         if n.startswith("assets/scripts/") and n.endswith(".js"))
        templates = sorted(n[len("assets/templates/"):] for n in names
                           if n.startswith("assets/templates/") and not n.endswith("/"))
        bundle = "assets/bundle/bundle.json" in names
    finally:
        if local:
            try:
                os.remove(local)
            except OSError:
                pass

    print("内置脚本（打包在 APK 内，只读）:")
    print(chr(10).join("  " + n for n in builtin) if builtin else "  （无）")
    if templates:
        print("内置模板:")
        print(chr(10).join("  " + n for n in templates))
    if bundle:
        print("这是小精灵包（含 assets/bundle/bundle.json）")

    imported = adb.su("ls /data/data/%s/files/scripts 2>/dev/null" % pkg, check=False)
    names2 = [n.strip() for n in imported.splitlines() if n.strip()]
    print("已导入脚本（/data/data/%s/files/scripts）:" % pkg)
    print(chr(10).join("  " + n for n in names2) if names2 else "  （无）")
    return 0


def do_run(adb: Adb, src: str, timeout: float, wait: bool = True,
           echo: bool = True, cold: bool = False, push_only: bool = False,
           config: str = "", pkg: str = APP_ID, quiet: bool = False) -> int:
    """推脚本 → 冷启动运行 → 跟日志。返回脚本的成败（0 成功 / 1 失败）。"""
    name = os.path.basename(src)

    if not os.path.exists(src):
        if echo:
            print("本地没有 %s，按设备上已有的脚本名处理" % src)
        installed = adb.su("ls /data/data/%s/files/scripts" % pkg, check=False)
        if name not in installed.split():
            # 小精灵的脚本在 APK 的 assets/bundle 里，不在导入目录；
            # 不做硬性拦截，交给应用去判断（找不到会明确报错）
            if echo:
                print("导入目录里没有 %s，按「包内绑定脚本」继续（找不到会报错）" % name)
    else:
        if echo:
            print("推送脚本 %s → 设备" % name)
        adb.sh("push", src, REMOTE_TMP)
        uid = app_uid(adb, pkg)
        adb.su("cp %s /data/data/%s/files/scripts/%s" % (REMOTE_TMP, pkg, name))
        adb.su("chown %s /data/data/%s/files/scripts/%s" % (uid, pkg, name))
        adb.su("chmod 660 /data/data/%s/files/scripts/%s" % (pkg, name))
        adb.su("rm -f %s" % REMOTE_TMP)

    # 冷启动：先 force-stop 再带参数启动。
    # 实测热启动（activity 已在栈顶）时 onNewIntent 那条路会静默不执行，
    # 冷启动可以保证 onCreate 路径必然生效，也让每次运行的状态干净。
    if push_only:
        return 0
    if echo:
        print("运行 %s" % name)
    proc = start_logcat(adb) if wait else None
    if cold:
        # 冷启动只在需要"干净状态"时用：会杀掉进程，无障碍服务会被系统摘掉
        adb.shell("am force-stop %s" % pkg, check=False)
        time.sleep(0.4)
    # 用广播而不是 Activity intent：实测界面不在前台时 am start --es 会静默不执行
    # -f 0x00000020 = FLAG_INCLUDE_STOPPED_PACKAGES：
    # 应用被 force-stop 过会进入 stopped 状态，默认收不到广播（实测卡住无输出）
    extra = (" --es config '%s'" % config) if config else ""
    adb.shell("am broadcast -a com.whx.automation.RUN_SCRIPT -f 0x00000020 "
              "-n %s --es script %s%s" % (component(pkg, RUN_RECEIVER), name, extra))
    if not wait:
        return 0
    return consume_log(proc, wait_done=True, timeout=timeout, quiet=quiet)


def cmd_run(args):
    sys.stdout.reconfigure(line_buffering=True)
    adb = pick_device(Adb(), args.serial)
    scripts = args.script if isinstance(args.script, list) else [args.script]

    if len(scripts) == 1:
        return do_run(adb, scripts[0], args.timeout, wait=not args.no_wait, cold=args.cold,
                      config=",".join(args.config), pkg=args.package)

    # 多脚本 = 任务链：全部推送后再一次性广播，设备端顺序执行
    names = []
    for src in scripts:
        do_run(adb, src, args.timeout, wait=False, echo=True, push_only=True,
               pkg=args.package)
        names.append(os.path.basename(src))
    print("任务链：%s" % " → ".join(names))

    if args.no_wait:
        adb.shell("am broadcast -a com.whx.automation.RUN_SCRIPT -f 0x00000020 "
                  "-n %s --es script %s"
                  % (component(args.package, RUN_RECEIVER), ",".join(names)))
        print("已启动（不等结果）。用 `python tools/whx.py log` 看日志")
        return 0

    proc = start_logcat(adb)
    adb.shell("am broadcast -a com.whx.automation.RUN_SCRIPT -f 0x00000020 "
              "-n %s --es script %s"
              % (component(args.package, RUN_RECEIVER), ",".join(names)))
    return consume_log(proc, wait_done=True, timeout=args.timeout * max(1, len(names)),
                       done_marker="chain finished")


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


def consume_log(proc, wait_done: bool, timeout: float = 120,
                done_marker: str = DONE_MARK, quiet: bool = False) -> int:
    started = time.time()
    failed = False
    try:
        for line in proc.stdout:
            line = line.rstrip("\n")
            if not any(tag in line for tag in LOG_TAGS):
                continue
            if noisy_stack_frame(line):
                continue
            if not quiet:
                print(clean_log(line))
            if " E WHX/" in line:
                failed = True        # 任何本项目的 E 级日志都算失败
            if " E WHX/RunScript" in line:
                # 请求没被接受（脚本不存在等），任务根本不会开始，不用等 done
                break
            if wait_done and done_marker in line:
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


def cmd_doctor(args):
    """环境自检：一次性告诉你"哪里没准备好"以及怎么修。

    公开仓库的新用户最先卡的就是环境（adb / root / 无障碍 / SDK），
    与其在群里问，不如一条命令自检。
    """
    ok = True
    print("WHX 环境自检")
    print("=" * 46)

    def line(name, good, detail="", fix=""):
        nonlocal ok
        mark = "✓" if good else "✗"
        if not good:
            ok = False
        print("%s %-22s %s" % (mark, name, detail))
        if not good and fix:
            print("  → %s" % fix)

    # 1. 工具
    adb_path = shutil.which("adb")
    line("adb", adb_path is not None, adb_path or "未找到",
         "把 platform-tools 加进 PATH（Android SDK 里的 platform-tools 目录）")

    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    if not sdk and os.path.exists("local.properties"):
        for l in open("local.properties", encoding="utf-8"):
            if l.startswith("sdk.dir="):
                sdk = l.split("=", 1)[1].strip()
    line("Android SDK", bool(sdk) and os.path.isdir(sdk), sdk or "未配置",
         "设置 ANDROID_HOME 或写 local.properties 的 sdk.dir")

    java = shutil.which("java")
    line("JDK", java is not None, java or "未找到", "安装 JDK 17+ 并加进 PATH")

    # 2. 设备
    if adb_path is None:
        print("（没有 adb，跳过设备检查）")
        return 1
    adb = pick_device(Adb(), args.serial)
    out = adb.shell("echo ok", check=False)
    line("设备连接", "ok" in out, "已连接 %s" % (adb.serial or "默认设备"),
         "adb connect ip:port 或启动模拟器")

    uid = adb.su("id -u", check=False).strip()
    line("root", uid == "0", "uid=%s" % (uid or "?"),
         "设备必须已 root；模拟器请开启 root 权限")

    # 3. 应用
    pkg = args.package
    raw = adb.shell("pm path %s" % pkg, check=False).strip()
    installed = raw.startswith("package:")
    line("应用已安装", installed, pkg if installed else "未安装 %s" % pkg,
         "adb install -r app/build/outputs/apk/release/app-release.apk")

    if installed:
        ver = adb.shell("dumpsys package %s | grep -m1 versionName" % pkg, check=False).strip()
        print("  %-22s %s" % ("版本", ver.split("=")[-1] if "=" in ver else ver))

        current = adb.shell("settings get secure enabled_accessibility_services",
                            check=False).strip()
        svc = a11y_service(pkg)
        enabled = svc in current
        line("无障碍服务", enabled, "已启用" if enabled else "未启用",
             "python tools/whx.py ui enable" + ("" if pkg == APP_ID else " --package " + pkg))

        ps = adb.shell("ps -A -o ARGS 2>/dev/null | grep -c HelperMain", check=False).strip()
        try:
            helper_count = int(ps)
        except ValueError:
            helper_count = 0
        line("root helper", helper_count > 0, "运行中（%d 个）" % helper_count if helper_count else "未运行",
             "跑一次任何脚本即会拉起；失败看 adb logcat -s WHX/Helper")

        # 4. 端到端探针
        out = adb.broadcast_ping()
        line("广播通道", "result=0" in out, "可发送" if "result=0" in out else "发送失败",
             "确认应用未被停用：adb shell pm enable " + pkg)

    print("=" * 46)
    if ok:
        print("全部就绪。跑 python tools/smoke.py 做端到端验证。")
    else:
        print("有项目未就绪，按上面的 → 提示处理。")
    return 0 if ok else 1


def cmd_schedule(args):
    """定时任务：每天某时刻运行脚本（设备端 AlarmManager 触发，不需要 PC 在线）。"""
    adb = pick_device(Adb(), args.serial)
    receiver = component(args.package, SCHEDULE_RECEIVER)

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
    xml = adb.su("cat /data/data/%s/shared_prefs/whx_schedules.xml" % args.package, check=False)
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


def a11y_service(pkg: str) -> str:
    """小精灵是独立包名，无障碍服务也要按包名拼。"""
    return component(pkg, A11Y_CLASS)


def cmd_ui(args):
    """无障碍服务开关：脚本读界面文字的前提。"""
    adb = pick_device(Adb(), args.serial)
    A11Y_SERVICE = a11y_service(args.package or APP_ID)
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


def sample_memory(adb: Adb, pkg: str) -> dict:
    """采一次内存/进程指标。dumpsys meminfo 的列是 KB。"""
    out = adb.shell("dumpsys meminfo %s" % pkg, check=False)
    metrics = {"pss": None, "dalvik": None, "native": None}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 3:
            if parts[0] == "TOTAL":
                try:
                    metrics["pss"] = int(parts[1])
                except ValueError:
                    pass
            elif parts[0] == "Dalvik" and parts[1] == "Heap":
                try:
                    metrics["dalvik"] = int(parts[2])
                except ValueError:
                    pass
            elif parts[0] == "Native" and parts[1] == "Heap":
                try:
                    metrics["native"] = int(parts[2])
                except ValueError:
                    pass
    helpers = adb.shell("ps -A -o ARGS | grep -c HelperMain", check=False).strip()
    try:
        metrics["helpers"] = int(helpers)
    except ValueError:
        metrics["helpers"] = -1
    return metrics


def cmd_soak(args):
    """长跑稳定性：反复跑脚本，期间采样内存与进程数。

    验收清单里"云手机连续运行 24 小时内存增长 <10%"这条，必须先有能重复执行的手段。
    这里跑 N 分钟并给出内存走势与进程数，短跑能提前暴露泄漏。
    """
    sys.stdout.reconfigure(line_buffering=True)
    adb = pick_device(Adb(), args.serial)
    pkg = args.package
    deadline = time.time() + args.minutes * 60
    runs = fails = 0
    rows = []
    baseline = sample_memory(adb, pkg)
    print("长跑开始：%s 分钟，脚本 %s" % (args.minutes, args.script))
    print("基线  PSS=%sKB Dalvik=%sKB Native=%sKB helper=%d"
          % (baseline["pss"], baseline["dalvik"], baseline["native"], baseline["helpers"]))

    next_sample = time.time() + args.interval
    try:
        while time.time() < deadline:
            code = do_run(adb, args.script, args.timeout, wait=True, echo=False,
                          pkg=pkg, quiet=True)
            runs += 1
            if code != 0:
                fails += 1
            if time.time() >= next_sample:
                m = sample_memory(adb, pkg)
                rows.append((time.strftime("%H:%M:%S"), runs, m))
                print("  %s  第%3d次  PSS=%sKB Dalvik=%sKB Native=%sKB helper=%d"
                      % (rows[-1][0], runs, m["pss"], m["dalvik"], m["native"], m["helpers"]))
                next_sample = time.time() + args.interval
    except KeyboardInterrupt:
        print("\n（手动中断）")

    final = sample_memory(adb, pkg)
    print("-" * 62)
    print("运行 %d 次，失败 %d 次，耗时 %.1f 分钟" % (runs, fails, (time.time() - (deadline - args.minutes * 60)) / 60))
    if rows:
        pss = [r[2]["pss"] for r in rows if r[2]["pss"]]
        if len(pss) >= 2:
            # 拿首样本比末样本（基线是进程刚起、还没跑过脚本，不能代表稳态）
            growth = (pss[-1] - pss[0]) / max(1, pss[0]) * 100
            half = len(pss) // 2
            first_avg = sum(pss[:half]) / max(1, half)
            second_avg = sum(pss[half:]) / max(1, len(pss) - half)
            trend = "单调增长" if second_avg > first_avg * 1.15 else "锯齿波动（GC 正常回收）"
            print("PSS  首样本 %dKB → 末样本 %dKB（%+.1f%%），区间 %d~%dKB，趋势：%s"
                  % (pss[0], pss[-1], growth, min(pss), max(pss), trend))
            print("     前半均值 %.0fKB / 后半均值 %.0fKB" % (first_avg, second_avg))
        helpers = [r[2]["helpers"] for r in rows]
        print("helper 进程数 区间 %d~%d（基线 %d）" % (min(helpers), max(helpers), baseline["helpers"]))
    if fails:
        print("有 %d 次失败，用 whx history 看原因" % fails)
    return 1 if fails else 0


def cmd_history(args):
    """查运行历史：无人值守任务出问题后，事后追溯用。"""
    adb = pick_device(Adb(), args.serial)
    xml = adb.su("cat /data/data/%s/shared_prefs/whx_history.xml" % args.package, check=False)
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
    if args.icon:
        cmd += ["--icon", str(args.icon)]
    if args.config:
        cmd += ["--config", args.config]
    if args.assets:
        cmd += ["--assets", args.assets]
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
    p.add_argument("--package", default=APP_ID,
                   help="目标应用包名（小精灵是独立包名），默认 com.whx.automation")
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("devices", help="列出设备")
    sp.set_defaults(func=cmd_devices)

    sp = sub.add_parser("ls", help="列出设备上的脚本")
    sp.set_defaults(func=cmd_ls)

    sp = sub.add_parser("run", help="推送并运行脚本")
    sp.add_argument("script", nargs="+",
                    help="本地脚本路径或设备上已有的脚本名；给多个则为任务链（顺序执行）")
    sp.add_argument("--no-wait", action="store_true", help="不等结果，立即返回")
    sp.add_argument("--config", action="append", default=[], metavar="K=V",
                    help="运行时配置，可重复：--config x=100 --config y=640")
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

    sp = sub.add_parser("doctor", help="环境自检（adb / root / 无障碍 / SDK）")
    sp.set_defaults(func=cmd_doctor)

    sp = sub.add_parser("schedule", help="定时任务（每天某时刻运行）")
    sp.add_argument("action", choices=["add", "list", "remove"])
    sp.add_argument("script", nargs="?", default="", help="脚本名")
    sp.add_argument("--time", default="08:00", help="HH:mm，默认 08:00")
    sp.set_defaults(func=cmd_schedule)

    sp = sub.add_parser("ui", help="无障碍服务开关（读界面文字）")
    sp.add_argument("action", nargs="?", default="status", choices=["enable", "disable", "status"])
    sp.add_argument("--package", default=None,
                    help="覆盖全局 --package")
    sp.set_defaults(func=cmd_ui)

    sp = sub.add_parser("soak", help="长跑稳定性测试（采样内存与进程数）")
    sp.add_argument("script", help="要反复运行的脚本")
    sp.add_argument("--minutes", type=float, default=5, help="跑多少分钟，默认 5")
    sp.add_argument("--interval", type=float, default=30, help="采样间隔秒，默认 30")
    sp.add_argument("--timeout", type=float, default=120, help="单次运行超时秒")
    sp.set_defaults(func=cmd_soak)

    sp = sub.add_parser("history", help="查看运行历史")
    sp.add_argument("--limit", type=int, default=10)
    sp.set_defaults(func=cmd_history)

    sp = sub.add_parser("pack", help="打包成小精灵 APK")
    sp.add_argument("script")
    sp.add_argument("--shell", default="app/build/outputs/apk/release/app-release.apk")
    sp.add_argument("--label", required=True)
    sp.add_argument("--pkg", required=True)
    sp.add_argument("--out", default=None)
    sp.add_argument("--icon", type=int, default=0, choices=[0, 1, 2, 3, 4],
                    help="小精灵图标编号 1..4")
    sp.add_argument("--config", default=None, help="参数文件（键|显示名|类型|默认值）")
    sp.add_argument("--assets", default=None, help="额外资源目录（模板图片等）")
    sp.add_argument("--no-autostart", action="store_true")
    sp.set_defaults(func=cmd_pack)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
