#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成小程序 tabBar 图标（纯标准库，无需 Pillow）。

微信小程序 tabBar 只接受本地 png/jpg，且微信开发者工具的 iconPath 不支持网络图与 svg，
因此这里用 zlib + struct 手写 PNG：先在 4 倍超采样画布上画二值遮罩，再降采样得到
抗锯齿的 alpha 通道，最后贴上纯色 RGB。

用法：python scripts/gen_tabbar_icons.py
输出：src/static/tabbar/{home,category,cart,user,service}[-active].png（81x81）
"""

import os
import struct
import zlib

SIZE = 81          # 微信 tabBar 图标推荐尺寸
SS = 4             # 超采样倍数
N = SIZE * SS

COLOR_NORMAL = (0x9C, 0xA3, 0xAF)   # #9ca3af 未选中
COLOR_ACTIVE = (0x25, 0x63, 0xEB)   # #2563eb 选中（品牌蓝）

OUT_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "src", "static", "tabbar"
)


# ── 画布 ────────────────────────────────────────────────────────
def new_mask():
    return bytearray(N * N)


def _put(mask, x, y, val=1):
    if 0 <= x < N and 0 <= y < N:
        mask[y * N + x] = val


def _sc(v):
    """设计坐标(81 基准) -> 超采样坐标"""
    return v * SS


def fill_rect(mask, x0, y0, x1, y1, val=1):
    for y in range(int(_sc(y0)), int(_sc(y1)) + 1):
        for x in range(int(_sc(x0)), int(_sc(x1)) + 1):
            _put(mask, x, y, val)


def fill_round_rect(mask, x0, y0, x1, y1, r, val=1):
    X0, Y0, X1, Y1 = _sc(x0), _sc(y0), _sc(x1), _sc(y1)
    R = _sc(r)
    for y in range(int(Y0), int(Y1) + 1):
        for x in range(int(X0), int(X1) + 1):
            # 四角圆化
            cx = min(max(x, X0 + R), X1 - R)
            cy = min(max(y, Y0 + R), Y1 - R)
            dx, dy = x - cx, y - cy
            if dx * dx + dy * dy <= R * R + R:
                _put(mask, x, y, val)


def fill_circle(mask, cx, cy, r, val=1, ymin=None, ymax=None):
    CX, CY, R = _sc(cx), _sc(cy), _sc(r)
    lo = int(_sc(ymin)) if ymin is not None else int(CY - R)
    hi = int(_sc(ymax)) if ymax is not None else int(CY + R)
    for y in range(max(0, lo), min(N - 1, hi) + 1):
        dy = y - CY
        if abs(dy) > R:
            continue
        half = (R * R - dy * dy) ** 0.5
        for x in range(int(CX - half), int(CX + half) + 1):
            _put(mask, x, y, val)


def fill_poly(mask, pts, val=1):
    P = [(_sc(px), _sc(py)) for px, py in pts]
    ys = [p[1] for p in P]
    for y in range(int(min(ys)), int(max(ys)) + 1):
        xs = []
        for i in range(len(P)):
            x1, y1 = P[i]
            x2, y2 = P[(i + 1) % len(P)]
            if y1 == y2:
                continue
            if min(y1, y2) <= y < max(y1, y2):
                t = (y - y1) / (y2 - y1)
                xs.append(x1 + t * (x2 - x1))
        xs.sort()
        for i in range(0, len(xs) - 1, 2):
            for x in range(int(xs[i]), int(xs[i + 1]) + 1):
                _put(mask, x, y, val)


def thick_line(mask, x0, y0, x1, y1, w, val=1):
    """粗细均匀的线段（沿线撒圆点，形成胶囊形）"""
    X0, Y0, X1, Y1 = _sc(x0), _sc(y0), _sc(x1), _sc(y1)
    R = _sc(w) / 2.0
    steps = max(2, int(max(abs(X1 - X0), abs(Y1 - Y0))))
    for i in range(steps + 1):
        t = i / steps
        cx, cy = X0 + (X1 - X0) * t, Y0 + (Y1 - Y0) * t
        for y in range(int(cy - R), int(cy + R) + 1):
            dy = y - cy
            if abs(dy) > R:
                continue
            half = (R * R - dy * dy) ** 0.5
            for x in range(int(cx - half), int(cx + half) + 1):
                _put(mask, x, y, val)


# ── 四个图标 ─────────────────────────────────────────────────────
def icon_home():
    """首页：屋顶三角 + 房身 + 门洞"""
    m = new_mask()
    fill_poly(m, [(40.5, 8), (3.5, 39), (77.5, 39)])
    fill_round_rect(m, 14, 36, 67, 72, 5)
    fill_round_rect(m, 33, 50, 48, 72, 3, val=0)   # 抠出门洞
    return m


def icon_category():
    """分类：2x2 圆角方块"""
    m = new_mask()
    for x0, y0 in ((9, 9), (42, 9), (9, 42), (42, 42)):
        fill_round_rect(m, x0, y0, x0 + 30, y0 + 30, 8)
    return m


def icon_cart():
    """购物车：把手 + 车篮 + 两个轮子"""
    m = new_mask()
    thick_line(m, 7, 13, 20, 13, 7)          # 横向把手
    thick_line(m, 19, 12, 27, 25, 7)         # 折向车篮
    fill_poly(m, [(20, 24), (67, 24), (58, 55), (29, 55)])   # 车篮
    fill_circle(m, 32, 64, 7)                # 左轮
    fill_circle(m, 56, 64, 7)                # 右轮
    return m


def icon_user():
    """我的：头 + 肩"""
    m = new_mask()
    fill_circle(m, 40.5, 28, 15)             # 头
    fill_circle(m, 40.5, 88, 35, ymin=53, ymax=73)   # 肩（圆顶截取）
    return m


def icon_service():
    """客服：对话气泡 + 左下尾巴 + 内部三个点（抠空）"""
    m = new_mask()
    fill_round_rect(m, 8, 12, 73, 58, 15)            # 气泡主体
    fill_poly(m, [(22, 54), (22, 74), (44, 54)])     # 左下小尾巴
    for cx in (27, 40.5, 54):                        # 三个点
        fill_circle(m, cx, 35, 4.6, val=0)
    return m


# ── PNG 输出 ─────────────────────────────────────────────────────
def downsample(mask):
    """4x4 平均 -> 抗锯齿 alpha"""
    out = bytearray(SIZE * SIZE)
    for y in range(SIZE):
        for x in range(SIZE):
            s = 0
            for dy in range(SS):
                row = (y * SS + dy) * N + x * SS
                for dx in range(SS):
                    s += mask[row + dx]
            out[y * SIZE + x] = int(s * 255 / (SS * SS) + 0.5)
    return out


def write_png(path, size, rgba):
    raw = b"".join(
        b"\x00" + bytes(rgba[y * size * 4:(y + 1) * size * 4]) for y in range(size)
    )

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def render(mask, rgb):
    alpha = downsample(mask)
    r, g, b = rgb
    buf = bytearray(SIZE * SIZE * 4)
    for i, a in enumerate(alpha):
        o = i * 4
        buf[o], buf[o + 1], buf[o + 2], buf[o + 3] = r, g, b, a
    return buf


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    icons = {
        "home": icon_home,
        "category": icon_category,
        "cart": icon_cart,
        "user": icon_user,
        "service": icon_service,
    }
    for name, fn in icons.items():
        mask = fn()
        for suffix, rgb in (("", COLOR_NORMAL), ("-active", COLOR_ACTIVE)):
            path = os.path.normpath(os.path.join(OUT_DIR, f"{name}{suffix}.png"))
            write_png(path, SIZE, render(mask, rgb))
            print("written:", path)


if __name__ == "__main__":
    main()
