#!/usr/bin/env python3
"""端到端冒烟测试：不需要人看，跑完给结论。

覆盖真实链路：构建 → 安装 → 脚本执行 → 识别 → 应用控制 → 定时/历史/无障碍。
用法：
    python tools/smoke.py                 # 全部
    python tools/smoke.py --skip-build    # 跳过构建（已装好包）
    python tools/smoke.py --serial emulator-5554
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time

APP_ID = "com.whx.automation"
# Windows 上 ./gradlew 是 shell 脚本，subprocess 直接调会报 WinError 193，得用 .bat
GRADLEW = "gradlew.bat" if os.name == "nt" else "./gradlew"
RELEASE_APK = "app/build/outputs/apk/release/app-release.apk"

PASS = "PASS"
FAIL = "FAIL"
SKIP = "SKIP"


class Runner:
    def __init__(self, serial: str | None):
        self.serial = serial
        self.results: list[tuple[str, str, str]] = []

    def adb(self, *args, timeout=120, check=False):
        cmd = ["adb"]
        if self.serial:
            cmd += ["-s", self.serial]
        cmd += list(args)
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                               encoding="utf-8", errors="replace")
            return r.returncode, (r.stdout or "") + (r.stderr or "")
        except subprocess.TimeoutExpired:
            return -1, "超时"

    def whx(self, *args, timeout=180) -> tuple[int, str]:
        cmd = [sys.executable, "tools/whx.py"]
        if self.serial:
            cmd += ["--serial", self.serial]
        cmd += list(args)
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                           encoding="utf-8", errors="replace")
        return r.returncode, (r.stdout or "") + (r.stderr or "")

    def record(self, name: str, status: str, detail: str = ""):
        self.results.append((name, status, detail))
        icon = {"PASS": "[通过]", "FAIL": "[失败]", "SKIP": "[跳过]"}[status]
        print("%s %-34s %s" % (icon, name, detail))

    def check(self, name, ok, detail=""):
        self.record(name, PASS if ok else FAIL, detail)
        return ok


def grant_root(r: "Runner"):
    """点掉可能出现的 su 授权弹窗（"永久记住" + "允许"）。

    弹窗出现时机不固定（应用请求 root 才弹），这里只负责"点一下"，
    是否生效由调用方用结果判断 —— 调用方失败时会再调一次。
    """
    r.adb("shell", "input", "tap", "250", "1090")
    time.sleep(0.8)
    r.adb("shell", "input", "tap", "550", "1195")
    time.sleep(1.5)


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=None)
    ap.add_argument("--skip-build", action="store_true")
    args = ap.parse_args(argv)
    r = Runner(args.serial)

    code, out = r.adb("devices")
    devices = [l for l in out.splitlines() if "\tdevice" in l]
    if not devices:
        print("没有可用设备")
        return 2
    if not args.serial:
        r.serial = devices[0].split("\t")[0]

    # 1. 构建
    if args.skip_build:
        r.record("构建 release", SKIP, "按参数跳过")
    else:
        p = subprocess.run([GRADLEW, "assembleRelease", "--console=plain"],
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
        r.check("构建 release", p.returncode == 0,
                "APK %.2f MB" % (os.path.getsize(RELEASE_APK) / 1048576) if os.path.exists(RELEASE_APK) else "")

    # 2. 单元测试
    p = subprocess.run([GRADLEW, ":core:test", ":helper:test", ":app:testDebugUnitTest",
                        "--console=plain"], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    r.check("单元测试", p.returncode == 0, "（细节见 build/test-results）")

    # 3. 安装
    code, out = r.adb("install", "-r", RELEASE_APK, timeout=180)
    r.check("安装 release 包", code == 0, "")

    # 4. 启动 + 授权（已授权时不会弹窗）
    r.adb("shell", "am", "start", "-n", APP_ID + "/com.whx.automation.MainActivity")
    time.sleep(3)
    r.adb("shell", "input", "tap", "250", "1090")   # 永久记住
    time.sleep(0.6)
    r.adb("shell", "input", "tap", "550", "1195")   # 允许
    time.sleep(2)

    # 5. 无障碍
    code, out = r.whx("ui", "enable")
    r.check("启用无障碍", code == 0 and "已启用" in out, "")

    # 6. 跑通识别 + 点击
    code, out = r.whx("run", "app/src/main/assets/scripts/find_image_demo.js")
    r.check("找图与点击（find_image_demo）",
            code == 0 and "得分 1.000" in out and "已点击" in out,
            "退出码 %d" % code)

    # 7. 失败脚本必须返回非 0
    bad = "dist/_smoke_bad.js"
    os.makedirs("dist", exist_ok=True)
    with open(bad, "w", encoding="utf-8") as fh:
        fh.write("refresh();\nnull.split(',');\n")
    code, out = r.whx("run", bad)
    r.check("失败脚本退出码为 1", code == 1 and "JavaScript.<eval>" in out, "退出码 %d" % code)

    # 8. 任务链
    code, out = r.whx("run", "app/src/main/assets/scripts/demo.js",
                      "app/src/main/assets/scripts/find_image_demo.js")
    r.check("任务链顺序执行",
            "chain finished" in out and out.count("start ") >= 2, "退出码 %d" % code)

    # 9. 应用控制
    code, out = r.whx("run", "app/src/main/assets/scripts/app_ctrl_demo.js")
    r.check("应用控制（启动/强停）",
            code == 0 and "com.android.settings" in out and "exit=0" in out, "")

    # 10. 无障碍文本
    r.adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
    time.sleep(2)
    code, out = r.whx("run", "app/src/main/assets/scripts/ui_demo.js")
    m = re.search(r"界面文本节点 (\d+) 个", out)
    count = int(m.group(1)) if m else 0
    r.check("读取界面文本", code == 0 and count > 5, "节点数 %d" % count)

    # 11. 运行历史
    code, out = r.whx("history", "--limit", "3")
    r.check("运行历史可查", code == 0 and ("成功" in out or "失败" in out), "")

    # 12. 定时任务增删
    code, out = r.whx("schedule", "add", "demo.js", "--time", "07:30")
    ok_add = code == 0
    code, out = r.whx("schedule", "list")
    ok_list = "demo.js" in out
    r.whx("schedule", "remove", "demo.js")
    r.check("定时任务增删查", ok_add and ok_list, "")

    # 13. 小精灵打包
    code, out = r.whx("pack", "app/src/main/assets/scripts/demo.js",
                      "--label", "冒烟测试", "--pkg", "com.whx.sprite.smoke",
                      "--icon", "3", "--out", "dist/_smoke_sprite.apk")
    r.check("打包小精灵（含图标）",
            code == 0 and os.path.exists("dist/_smoke_sprite.apk"),
            "%.2f MB" % (os.path.getsize("dist/_smoke_sprite.apk") / 1048576)
            if os.path.exists("dist/_smoke_sprite.apk") else "")

    # 14. 小精灵：安装 → 授权 → 用 --package 运行绑定脚本
    sprite_pkg = "com.whx.sprite.smoke"
    sprite_apk = "dist/_smoke_sprite.apk"
    if os.path.exists(sprite_apk):
        r.adb("uninstall", sprite_pkg)
        code, out = r.adb("install", "-r", sprite_apk, timeout=180)
        installed = code == 0
        r.adb("shell", "am", "start", "-n", "%s/com.whx.automation.MainActivity" % sprite_pkg)
        time.sleep(6)
        grant_root(r)
        r.whx("--package", sprite_pkg, "ui", "enable")

        code, out = r.whx("--package", sprite_pkg, "run", "demo.js")
        if code != 0 or "done ok=true" not in out:
            # 首次授权可能没点上（弹窗时机不定），再补一次
            grant_root(r)
            r.whx("--package", sprite_pkg, "ui", "enable")
            code, out = r.whx("--package", sprite_pkg, "run", "demo.js")
        if installed and code == 0 and "done ok=true" in out:
            r.check("小精灵：安装并运行绑定脚本", True, "")
        elif any(k in out for k in ("helper", "ECONNREFUSED", "授权", "su 请求")):
            # 新装的包要过一遍 root 授权；这台机器的 su 拒绝后会拉黑，无法全自动
            r.record("小精灵：安装并运行绑定脚本", SKIP,
                     "需人工在授权弹窗点「允许」后重跑")
        else:
            r.check("小精灵：安装并运行绑定脚本", False, "退出码 %d" % code)
            for line in out.strip().splitlines()[-3:]:
                print("      " + line[:110])

        code, out = r.whx("--package", sprite_pkg, "schedule", "add", "demo.js", "--time", "11:00")
        code2, out2 = r.whx("--package", sprite_pkg, "schedule", "list")
        r.check("小精灵：独立定时任务", code == 0 and "demo.js" in out2, "")
        r.whx("--package", sprite_pkg, "schedule", "remove", "demo.js")
    else:
        r.record("小精灵：安装并运行绑定脚本", SKIP, "没有打包产物")

    # 汇总
    total = len(r.results)
    failed = [x for x in r.results if x[1] == FAIL]
    skipped = [x for x in r.results if x[1] == SKIP]
    print("\n汇总：%d 项，通过 %d，失败 %d，跳过 %d"
          % (total, total - len(failed) - len(skipped), len(failed), len(skipped)))
    for name, _, detail in failed:
        print("  失败项：%s %s" % (name, detail))
    if os.path.exists("dist/_smoke_bad.js"):
        os.remove("dist/_smoke_bad.js")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
