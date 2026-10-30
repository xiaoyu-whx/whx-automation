#!/usr/bin/env python3
"""小精灵打包器。

把一个脚本绑进"壳 APK"，产出一个独立可分发的小精灵：

    python packer/make_sprite.py \
        --shell app/build/outputs/apk/debug/app-debug.apk \
        --script app/src/main/assets/scripts/demo.js \
        --name demo --label "小鱼示例" --pkg com.whx.sprite.demo \
        --out dist/xiaoyu-demo.apk

做的事：
1. 往 APK 里塞 assets/bundle/bundle.json + assets/bundle/<script>
2. 改写 AndroidManifest.xml 的 **package**（二进制 AXML 字符串池）
3. 改写 android:label（应用名）
4. zipalign + apksigner 重签

为什么不重新编译：壳的代码不用动，只换资源和清单，几秒钟出包。
为什么自己写 AXML 解析：不想依赖 apktool / GitHub 下载；AXML 的字符串池是自包含的，
替换字符串后整体重排即可，不需要理解其余节点。
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

ANDROID_NS = "http://schemas.android.com/apk/res/android"

# ---------------------------------------------------------------- AXML 常量
RES_STRING_POOL_TYPE = 0x0001
AXML_MAGIC = 0x0003
UTF8_FLAG = 0x100


class AxmlError(RuntimeError):
    pass


def _decode_length(buf: bytes, off: int):
    """AXML 的变长长度：首字节最高位为 1 表示占两字节。"""
    if buf[off] & 0x80:
        return ((buf[off] & 0x7F) << 8) | buf[off + 1], off + 2
    return buf[off], off + 1


def _encode_length(n: int) -> bytes:
    if n > 0x7F:
        return bytes([0x80 | (n >> 8), n & 0xFF])
    return bytes([n])


def parse_string_pool(buf: bytes, off: int):
    """解析字符串池，返回 (strings, chunk_end, header)。"""
    typ, header_size, size = struct.unpack_from("<HHI", buf, off)
    if typ != RES_STRING_POOL_TYPE:
        raise AxmlError("偏移 %d 不是字符串池（type=0x%04X）" % (off, typ))
    (string_count, style_count, flags,
     strings_start, styles_start) = struct.unpack_from("<IIIII", buf, off + 8)

    offsets = struct.unpack_from("<%dI" % string_count, buf, off + header_size)
    data_base = off + strings_start

    strings = []
    for rel in offsets:
        p = data_base + rel
        if flags & UTF8_FLAG:
            byte_len, p = _decode_length(buf, p)
            _char_len, p = _decode_length(buf, p)
            strings.append(buf[p:p + byte_len].decode("utf-8", "replace"))
        else:
            # UTF-16 池：长度是 2 字节小端（不是变长整数）——AXML 的两种池格式差别就在这
            char_len = struct.unpack_from("<H", buf, p)[0]
            p += 2
            strings.append(buf[p:p + char_len * 2].decode("utf-16-le", "replace"))

    return strings, off + size, dict(flags=flags, style_count=style_count,
                                     header_size=header_size)


def build_string_pool(strings, flags: int, style_count: int, header_size: int) -> bytes:
    """把字符串列表重新编码成字符串池 chunk。"""
    data = bytearray()
    offsets = []
    for s in strings:
        offsets.append(len(data))
        if flags & UTF8_FLAG:
            raw = s.encode("utf-8")
            data += _encode_length(len(raw))
            data += _encode_length(len(s))      # UTF-16 长度
            data += raw
            data += b"\x00"
        else:
            raw = s.encode("utf-16-le")
            data += struct.pack("<H", len(raw) // 2)
            data += raw
            data += b"\x00\x00"

    while len(data) % 4:
        data += b"\x00"

    strings_start = header_size + 4 * len(strings) + 4 * style_count
    total = strings_start + len(data)
    out = bytearray()
    out += struct.pack("<HHI", RES_STRING_POOL_TYPE, header_size, total)
    out += struct.pack("<IIIII", len(strings), style_count, flags, strings_start, 0)
    for o in offsets:
        out += struct.pack("<I", o)
    if style_count:
        out += b"\x00" * (4 * style_count)     # 本场景没有样式表
    out += data
    return bytes(out)


def patch_axml(buf: bytes, replacements: dict) -> bytes:
    """替换 AXML 字符串池里的字符串；返回新的整份文件。"""
    if len(buf) < 8:
        raise AxmlError("文件太短")
    magic, header_size, total_size = struct.unpack_from("<HHI", buf, 0)
    if magic != AXML_MAGIC:
        raise AxmlError("不是 AXML（magic=0x%04X）" % magic)

    pool_off = header_size
    strings, pool_end, meta = parse_string_pool(buf, pool_off)

    changed = False
    new_strings = []
    for s in strings:
        if s in replacements:
            new_strings.append(replacements[s])
            changed = True
        else:
            new_strings.append(s)
    if not changed:
        return buf

    new_pool = build_string_pool(new_strings, meta["flags"], meta["style_count"],
                                 meta["header_size"])
    rest = buf[pool_end:]

    out = bytearray()
    out += buf[:pool_off]
    out += new_pool
    out += rest
    struct.pack_into("<I", out, 4, len(out))    # 更新总长度
    return bytes(out)


# ------------------------------------------------------------------ 打包
def find_tool(android_home: str, build_tools: str, name: str) -> str:
    path = os.path.join(android_home, "build-tools", build_tools, name)
    if os.name == "nt":
        for candidate in (path + ".bat", path + ".exe"):
            if os.path.exists(candidate):
                return candidate
    if os.path.exists(path):
        return path
    raise SystemExit("找不到工具: %s（检查 --build-tools 参数）" % path)


def ensure_keystore(keystore: str, alias: str, password: str, keytool: str) -> None:
    if os.path.exists(keystore):
        return
    os.makedirs(os.path.dirname(keystore) or ".", exist_ok=True)
    subprocess.run([keytool, "-genkeypair", "-keystore", keystore,
                    "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "10000", "-storepass", password, "-keypass", password,
                    "-dname", "CN=WHX Automation, OU=sprite, O=WHX, C=CN"],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print("已生成签名密钥: %s" % keystore)


def patch_manifest_in_zip(zip_path: str, replacements: dict):
    """就地替换 zip 里的 AndroidManifest.xml（返回新字节，不写盘）。"""
    with zipfile.ZipFile(zip_path) as z:
        manifest = z.read("AndroidManifest.xml")
    patched = patch_axml(manifest, replacements)
    if patched == manifest:
        print("[警告] 清单里没有找到要替换的字符串，package/label 可能没变")
    return patched


def build_sprite(args) -> str:
    android_home = args.android_home
    aapt2 = find_tool(android_home, args.build_tools, "aapt2")
    zipalign = find_tool(android_home, args.build_tools, "zipalign")
    apksigner = find_tool(android_home, args.build_tools, "apksigner")
    keytool = os.path.join(args.java_home, "bin", "keytool.exe" if os.name == "nt" else "keytool")

    ensure_keystore(args.keystore, args.key_alias, args.key_pass, keytool)

    script_name = os.path.basename(args.script)
    with open(args.script, "r", encoding="utf-8") as fh:
        script_code = fh.read()

    bundle_json = json.dumps({
        "script": script_name,
        "appName": args.label,
        "package": args.pkg,
        "version": args.version,
        "autoStart": not args.no_autostart,
    }, ensure_ascii=False, indent=2)

    manifest_bytes = patch_manifest_in_zip(args.shell, {
        args.shell_package: args.pkg,
        args.shell_label: args.label,
    })

    tmpdir = tempfile.mkdtemp(prefix="sprite-")
    unsigned = os.path.join(tmpdir, "unsigned.apk")
    try:
        with zipfile.ZipFile(args.shell) as zin, zipfile.ZipFile(unsigned, "w") as zout:
            for item in zin.infolist():
                if item.filename == "AndroidManifest.xml":
                    zout.writestr(item, manifest_bytes)
                    continue
                if item.filename.startswith("assets/bundle/"):
                    continue        # 丢掉旧的绑定
                zout.writestr(item, zin.read(item.filename))

            zi = zipfile.ZipInfo("assets/bundle/bundle.json")
            zi.compress_type = zipfile.ZIP_STORED
            zout.writestr(zi, bundle_json)
            zi = zipfile.ZipInfo("assets/bundle/" + script_name)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(zi, script_code)

        aligned = os.path.join(tmpdir, "aligned.apk")
        subprocess.run([zipalign, "-f", "-p", "4", unsigned, aligned], check=True,
                       stdout=subprocess.DEVNULL)

        os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
        sign = subprocess.run([apksigner, "sign", "--ks", args.keystore,
                               "--ks-key-alias", args.key_alias,
                               "--ks-pass", "pass:" + args.key_pass,
                               "--key-pass", "pass:" + args.key_pass,
                               "--out", args.out, aligned],
                              capture_output=True, text=True)
        if sign.returncode != 0:
            print("[apksigner 失败]")
            print(sign.stdout)
            print(sign.stderr)
            raise SystemExit("签名失败")
        return args.out
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


def verify(apk: str, aapt2: str):
    """用 aapt2 打印清单，肉眼确认 package 与 label 已改。"""
    try:
        out = subprocess.run([aapt2, "dump", "xmltree", apk, "--file", "AndroidManifest.xml"],
                             capture_output=True, text=True, timeout=30)
        text = out.stdout
        lines = [l.strip() for l in text.splitlines()
                 if "package=" in l or "label" in l]
        for line in lines[:6]:
            print("   " + line)
    except Exception as exc:      # pragma: no cover
        print("   （aapt2 校验失败: %s）" % exc)


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    p = argparse.ArgumentParser(description="把小脚本打包成独立小精灵 APK")
    p.add_argument("--shell", required=True, help="壳 APK（我们的 app-debug.apk）")
    p.add_argument("--script", required=True, help="要绑定的脚本 .js")
    p.add_argument("--label", required=True, help="小精灵显示名（应用名）")
    p.add_argument("--pkg", required=True, help="小精灵包名，例如 com.whx.sprite.demo")
    p.add_argument("--out", required=True, help="输出 APK 路径")
    p.add_argument("--name", default="", help="任务名（默认取脚本名）")
    p.add_argument("--version", default="1.0.0")
    p.add_argument("--shell-package", default="com.whx.automation", help="壳的包名")
    p.add_argument("--shell-label", default="WHX Automation", help="壳的应用名")
    p.add_argument("--no-autostart", action="store_true", help="打开小精灵后不自动运行")
    p.add_argument("--android-home", default=os.environ.get("ANDROID_HOME", r"D:\Android\Sdk"))
    p.add_argument("--build-tools", default="34.0.0")
    p.add_argument("--java-home", default=os.environ.get("JAVA_HOME", r"D:\Java\1.JAVA"))
    p.add_argument("--keystore", default=os.path.join(os.path.dirname(__file__), ".keys", "sprite.jks"))
    p.add_argument("--key-alias", default="sprite")
    p.add_argument("--key-pass", default="whxsprite")
    args = p.parse_args(argv)

    if not os.path.exists(args.shell):
        raise SystemExit("壳 APK 不存在: %s" % args.shell)

    print("打包小精灵")
    print("  壳    : %s" % args.shell)
    print("  脚本  : %s" % args.script)
    print("  包名  : %s" % args.pkg)
    print("  名称  : %s" % args.label)

    out = build_sprite(args)
    aapt2 = find_tool(args.android_home, args.build_tools, "aapt2")
    print("完成  : %s（%.1f KB）" % (out, os.path.getsize(out) / 1024.0))
    print("清单校验：")
    verify(out, aapt2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
