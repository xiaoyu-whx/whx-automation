#!/usr/bin/env python3
"""PC 端可视化调试：在电脑上完成取色、找色、找图，并生成可直接粘贴的脚本代码。

为什么不复用设备端的识别：调试要的是"看得见"——
把命中点画在截图上、把模板位置框出来、把颜色值打印出来，
这些在设备上都要先写脚本才能看到，在 PC 上一条命令就够。

用法：
    python tools/vision_cli.py pick --at 360,640
    python tools/vision_cli.py color --hex "#FF5722" --tol 8
    python tools/vision_cli.py color --hex "#FF5722" --all --max 10
    python tools/vision_cli.py template --tpl tpl.png --threshold 0.9
    python tools/vision_cli.py crop --region 100,200,120,80 --out tpl.png
"""
from __future__ import annotations

import argparse
import os
import sys

import numpy as np
from PIL import Image


# ------------------------------------------------------------------ 基础
def load_rgb(path: str) -> np.ndarray:
    with Image.open(path) as im:
        return np.asarray(im.convert("RGB")).astype(np.int32)


def parse_hex(text: str) -> tuple[int, int, int]:
    t = text.strip().lstrip("#")
    if len(t) != 6:
        raise SystemExit("颜色要写成 #RRGGBB，收到: %s" % text)
    return int(t[0:2], 16), int(t[2:4], 16), int(t[4:6], 16)


def to_hex(rgb) -> str:
    return "#%02X%02X%02X" % (int(rgb[0]), int(rgb[1]), int(rgb[2]))


def parse_region(text: str | None, shape) -> tuple[int, int, int, int] | None:
    if not text:
        return None
    parts = [int(v) for v in text.replace("，", ",").split(",")]
    if len(parts) != 4:
        raise SystemExit("区域要写成 x,y,w,h，收到: %s" % text)
    x, y, w, h = parts
    x = max(0, min(x, shape[1] - 1))
    y = max(0, min(y, shape[0] - 1))
    w = max(1, min(w, shape[1] - x))
    h = max(1, min(h, shape[0] - y))
    return x, y, w, h


def annotate(img: np.ndarray, cx: int, cy: int, box: int = 6) -> np.ndarray:
    """在命中点画一个红框 + 十字，方便肉眼确认找得对不对。"""
    out = img.copy()
    h, w = out.shape[:2]
    x0, x1 = max(0, cx - box), min(w - 1, cx + box)
    y0, y1 = max(0, cy - box), min(h - 1, cy + box)
    out[y0:y0 + 2, x0:x1] = (255, 0, 0)
    out[y1 - 1:y1 + 1, x0:x1] = (255, 0, 0)
    out[y0:y1, x0:x0 + 2] = (255, 0, 0)
    out[y0:y1, x1 - 1:x1 + 1] = (255, 0, 0)
    out[max(0, cy - 10):cy + 10, cx] = (0, 255, 0)
    out[cy, max(0, cx - 10):cx + 10] = (0, 255, 0)
    return out


# ------------------------------------------------------------------ 取色
def cmd_pick(img, args) -> int:
    x, y = [int(v) for v in args.at.split(",")]
    h, w = img.shape[:2]
    if not (0 <= x < w and 0 <= y < h):
        raise SystemExit("坐标 %d,%d 超出截图 %dx%d" % (x, y, w, h))
    rgb = img[y, x]
    print("坐标 (%d, %d) 颜色 %s  rgb%s" % (x, y, to_hex(rgb), tuple(int(v) for v in rgb)))
    print()
    print("脚本里这样用：")
    print("  var c = getColor(%d, %d);            // → '%s'" % (x, y, to_hex(rgb)))
    print("  log(isColor(%d, %d, '%s', 6));       // 容差判断" % (x, y, to_hex(rgb)))
    return 0


# ------------------------------------------------------------------ 找色
def cmd_color(img, args) -> int:
    target = np.array(parse_hex(args.hex), dtype=np.int32)
    region = parse_region(args.region, img.shape)
    if region:
        x, y, w, h = region
        view = img[y:y + h, x:x + w]
    else:
        x = y = 0
        view = img

    diff = np.abs(view - target)
    mask = (diff <= args.tol).all(axis=2)
    count = int(mask.sum())
    if count == 0:
        print("没找到 %s（容差 %d）。" % (args.hex, args.tol))
        print("建议：放宽 --tol，或换一个更有辨识度的颜色（用 pick 命令先取样）")
        return 1

    ys, xs = np.nonzero(mask)
    if args.all:
        order = np.lexsort((ys, xs))          # 按 x 再按 y，与设备端 COLUMN_FIRST 接近
        idx = order[: args.max]
        print("命中 %d 个点，前 %d 个：" % (count, len(idx)))
        for i in idx:
            print("  (%d, %d)" % (xs[i] + x, ys[i] + y))
    else:
        order = np.lexsort((xs, ys))          # 行优先：先 y 后 x
        i = order[0]
        cx, cy = int(xs[i] + x), int(ys[i] + y)
        print("命中 %d 个像素，第一个在 (%d, %d)" % (count, cx, cy))
        print()
        print("脚本里这样用：")
        print("  var pt = findColor('%s', %d);   // → '%d,%d'" % (args.hex, args.tol, cx, cy))
        print("  var xy = pt.split(','); click(Number(xy[0]), Number(xy[1]));")
        if args.save:
            out = annotate(img, cx, cy)
            Image.fromarray(out.astype(np.uint8)).save(args.save)
            print("\n命中位置已画到: %s" % args.save)
    return 0


# ------------------------------------------------------------------ 找图（FFT 归一化互相关）
def _box_sum(a: np.ndarray, th: int, tw: int) -> np.ndarray:
    """滑动窗口求和（用积分图，O(1) 每个窗口）。"""
    ii = np.zeros((a.shape[0] + 1, a.shape[1] + 1), dtype=np.float64)
    ii[1:, 1:] = np.cumsum(np.cumsum(a, axis=0), axis=1)
    return (ii[th:, tw:] - ii[:-th, tw:] - ii[th:, :-tw] + ii[:-th, :-tw])


def match_template(img: np.ndarray, tpl: np.ndarray):
    """返回 (得分矩阵, 最高分位置, 最高分)。归一化互相关，用 FFT 加速。"""
    gray = img.astype(np.float64).mean(axis=2)
    t = tpl.astype(np.float64).mean(axis=2)
    th, tw = t.shape
    H, W = gray.shape
    if th > H or tw > W:
        raise SystemExit("模板 %dx%d 比截图 %dx%d 还大" % (tw, th, W, H))
    if t.std() < 1e-6:
        raise SystemExit("模板几乎没有纹理变化（方差≈0），无法可靠匹配")

    n = th * tw
    # 互相关：circular convolution 的合法区域正好是滑动相关
    f_img = np.fft.rfft2(gray)
    padded = np.zeros_like(gray)
    padded[:th, :tw] = t
    f_tpl = np.fft.rfft2(padded)
    corr = np.fft.irfft2(f_img * np.conj(f_tpl), s=gray.shape)[:H - th + 1, :W - tw + 1]

    sum_i = _box_sum(gray, th, tw)
    sum_i2 = _box_sum(gray * gray, th, tw)
    mean_i = sum_i / n
    var_i = np.maximum(sum_i2 / n - mean_i * mean_i, 0.0)
    mean_t = t.mean()
    std_t = t.std()

    denom = np.sqrt(var_i) * std_t
    with np.errstate(divide="ignore", invalid="ignore"):
        score = np.where(denom > 1e-9, (corr / n - mean_i * mean_t) / denom, 0.0)

    idx = int(np.argmax(score))
    sy, sx = np.unravel_index(idx, score.shape)
    return score, int(sx), int(sy), float(score[sy, sx])


def cmd_template(img, args) -> int:
    tpl_img = load_rgb(args.tpl)
    _, sx, sy, best = match_template(img, tpl_img)
    th, tw = tpl_img.shape[:2]
    cx, cy = sx + tw // 2, sy + th // 2

    print("模板 %s 尺寸 %dx%d" % (args.tpl, tw, th))
    print("最佳匹配: 左上 (%d, %d)  中心 (%d, %d)  得分 %.3f" % (sx, sy, cx, cy, best))
    if best < args.threshold:
        print("得分低于阈值 %.2f —— 设备上 findImage 会返回 null。" % args.threshold)
        status = 1
    else:
        status = 0
        print()
        print("脚本里这样用（模板需先放进脚本包）：")
        print("  var pt = findImage('%s', %.2f);" % (os.path.basename(args.tpl), args.threshold))
        print("  var xy = pt.split(','); click(Number(xy[0]), Number(xy[1]));")

    if args.save:
        out = img.copy()
        out[sy:sy + th, sx:sx + 2] = (255, 0, 0)
        out[sy:sy + th, sx + tw - 2:sx + tw] = (255, 0, 0)
        out[sy:sy + 2, sx:sx + tw] = (255, 0, 0)
        out[sy + th - 2:sy + th, sx:sx + tw] = (255, 0, 0)
        Image.fromarray(out.astype(np.uint8)).save(args.save)
        print("\n匹配位置已画到: %s" % args.save)

    if args.max_hits > 1:
        score, _, _, _ = match_template(img, tpl_img)
        flat = score.ravel()
        top = np.argsort(flat)[::-1][: args.max_hits * 8]
        picked = []
        for idx in top:
            y, x = np.unravel_index(idx, score.shape)
            if all(abs(int(x) - px) > tw // 2 or abs(int(y) - py) > th // 2 for px, py in picked):
                picked.append((int(x), int(y)))
            if len(picked) >= args.max_hits:
                break
        print("其它候选（去重后）:")
        for x, y in picked:
            print("  (%d, %d)  得分 %.3f" % (x + tw // 2, y + th // 2, score[y, x]))
    return status


def cmd_crop(img, args) -> int:
    region = parse_region(args.region, img.shape)
    if not region:
        raise SystemExit("需要 --region x,y,w,h")
    x, y, w, h = region
    tpl = img[y:y + h, x:x + w]
    Image.fromarray(tpl.astype(np.uint8)).save(args.out)
    print("已裁出模板 %s（%dx%d）" % (args.out, w, h))
    print("下一步：python tools/vision_cli.py template --tpl %s" % args.out)
    return 0


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    p = argparse.ArgumentParser(prog="vision_cli", description="PC 端识别调试")
    p.add_argument("--shot", default="dist/screen.png", help="截图路径（默认 dist/screen.png）")
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("pick", help="取某点颜色")
    sp.add_argument("--at", required=True, help="x,y")
    sp.set_defaults(func=cmd_pick)

    sp = sub.add_parser("color", help="找色")
    sp.add_argument("--hex", required=True, help="#RRGGBB")
    sp.add_argument("--tol", type=int, default=8)
    sp.add_argument("--region", default=None, help="x,y,w,h")
    sp.add_argument("--all", action="store_true", help="列出多个命中点")
    sp.add_argument("--max", type=int, default=20)
    sp.add_argument("--save", default=None, help="标注后保存的图片路径")
    sp.set_defaults(func=cmd_color)

    sp = sub.add_parser("template", help="找图")
    sp.add_argument("--tpl", required=True)
    sp.add_argument("--threshold", type=float, default=0.9)
    sp.add_argument("--max-hits", type=int, default=1)
    sp.add_argument("--save", default=None)
    sp.set_defaults(func=cmd_template)

    sp = sub.add_parser("crop", help="从截图裁出模板")
    sp.add_argument("--region", required=True, help="x,y,w,h")
    sp.add_argument("--out", required=True)
    sp.set_defaults(func=cmd_crop)

    args = p.parse_args(argv)
    if not os.path.exists(args.shot):
        raise SystemExit("截图不存在: %s（先跑 python tools/whx.py shot %s）" % (args.shot, args.shot))
    return args.func(load_rgb(args.shot), args)


if __name__ == "__main__":
    sys.exit(main())
