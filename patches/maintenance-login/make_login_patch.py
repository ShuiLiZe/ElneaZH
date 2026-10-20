#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Elnea 汉化版「真机进不去」补丁生成器：维护锁 + 强制账号输入 + 身份缓存。

用法（在仓库根目录下）：
    python tools\\make_login_patch.py --so 02_extracted\\apk\\lib\\arm64-v8a\\libil2cpp.so --verify
    python tools\\make_login_patch.py --so <libil2cpp.so> --out 04_app\\assets\\so_patch.bin

输出格式（沿用 04_app\\assets\\so_patch.bin 的既有约定，App 侧 applyLibil2cppPatch 直接读）：
    "ELSP" | u32 ver=1 | u32 cnt | cnt x { u32 file_off, u32 orig_len, orig[], u32 new_len, new[] }

五条补丁（全部按「文件偏移 + 原字节校验」定位，原字节不符即拒绝打包，绝不盲改）：

  P_LOGIN_NO_ACCOUNT_B0  TitlePanel::accountCheck 里读 isAccountInput([x19,#0x128]) 的
                         ldrb 改成 mov w8,wzr；
  P_LOGIN_NO_ACCOUNT_B1  紧跟的 cbz 改成 nop
                         -> 无论 isAccountInput 是什么都不会被强制弹「输入账号」框。
  P_LOGIN_NO_RETRY       TitlePanel::onErrorLoginRequest1 的失败计数分支 b.le 改 b
                         -> 登录连续失败超过 9 次也不再弹账号输入框。
  P_UUID_NO_CACHE_0      UuidPlugin::GetUUID 里「PlayerPrefs["uuid"] 非空就直接返回旧值」的
  P_UUID_NO_CACHE_1      tbz 改成 nop（两处）
                         -> 服务器认的 althi_uniq 不再被 playerprefs 缓存复用，
                            每次冷启动都重新派生，服务器视为新设备。

⚠ 取舍：P_UUID_NO_CACHE_* 让每次冷启动都变成服务器端「新号」，服务器存档无法自然续上。
   想保留服务器存档的，只保留前三条（注释掉 UUID_NO_CACHE 两行）再生成。

地址纪律（Elnea 2.10.7 arm64-v8a 的 libil2cpp.so）：
    .text 段 p_offset=0x1551780 / p_vaddr=0x1555780，即 **文件偏移 = VA - 0x4000**；
    而 mscorlib 的 RVA 恰好等于文件偏移。两者不可混算，故本脚本自带 PT_LOAD 换算，
    且每条补丁都先读 4 字节比对原字节。
"""

import argparse
import struct
import sys
from pathlib import Path

# 名称、文件偏移、原字节（大写 hex）、新字节（大写 hex）
PATCHES = [
    ("P_LOGIN_NO_ACCOUNT_B0", 0x1C3E62C, "68A24439", "08008052"),  # ldrb w8,[x19,#0x128] -> mov w8,wzr
    ("P_LOGIN_NO_ACCOUNT_B1", 0x1C3E630, "A8000034", "1F2003D5"),  # cbz w8,<open>        -> nop
    ("P_LOGIN_NO_RETRY",      0x1C3FFB8, "6D000054", "6D000014"),  # b.le -> b (onErrorLoginRequest1+0x8)
    ("P_UUID_NO_CACHE_0",     0x1C8B478, "C0040036", "1F2003D5"),  # tbz w0,#0,0x1C8F510  -> nop
    ("P_UUID_NO_CACHE_1",     0x1C8B490, "80030036", "1F2003D5"),  # tbz w0,#0,0x1C8F500  -> nop
]

MAGIC = b"ELSP"
VER = 1


class Elf:
    """只做一件事：按 PT_LOAD 在 VA 与文件偏移之间换算。"""

    def __init__(self, path):
        self.d = bytearray(Path(path).read_bytes())
        if self.d[:4] != b"\x7fELF":
            raise SystemExit("不是 ELF 文件：%s" % path)
        if self.d[4] != 2:
            raise SystemExit("只支持 ELF64：%s" % path)
        e_phoff = struct.unpack_from("<Q", self.d, 0x20)[0]
        e_phentsize = struct.unpack_from("<H", self.d, 0x36)[0]
        e_phnum = struct.unpack_from("<H", self.d, 0x38)[0]
        self.loads = []
        for i in range(e_phnum):
            off = e_phoff + i * e_phentsize
            p_type = struct.unpack_from("<I", self.d, off)[0]
            if p_type != 1:  # PT_LOAD
                continue
            p_offset = struct.unpack_from("<Q", self.d, off + 0x08)[0]
            p_vaddr = struct.unpack_from("<Q", self.d, off + 0x10)[0]
            p_filesz = struct.unpack_from("<Q", self.d, off + 0x20)[0]
            self.loads.append((p_vaddr, p_offset, p_filesz))

    def off_to_va(self, off):
        for vaddr, offset, filesz in self.loads:
            if offset <= off < offset + filesz:
                return vaddr + (off - offset)
        return None

    def va_to_off(self, va):
        for vaddr, offset, filesz in self.loads:
            if vaddr <= va < vaddr + filesz:
                return offset + (va - vaddr)
        raise SystemExit("VA 0x%X 不在任何 PT_LOAD 段内" % va)


def build_entries(elf):
    """校验原字节后返回 [(off, orig_bytes, new_bytes, name), ...]；任一不符即整体失败。"""
    out = []
    for name, off, orig_hex, new_hex in PATCHES:
        orig = bytes.fromhex(orig_hex)
        new = bytes.fromhex(new_hex)
        if len(orig) != len(new):
            raise SystemExit("%s 长度不等长（%d vs %d），本方案要求等长原地替换" % (name, len(orig), len(new)))
        cur = bytes(elf.d[off:off + len(orig)])
        if cur.hex().upper() != orig_hex.upper():
            raise SystemExit(
                "%s @0x%X 原字节不符：期望 %s，实际 %s（so 版本不对？）"
                % (name, off, orig_hex, cur.hex().upper()))
        out.append((off, orig, new, name))
    return out


def pack(entries):
    buf = bytearray(MAGIC + struct.pack("<II", VER, len(entries)))
    for off, orig, new, _name in entries:
        buf += struct.pack("<II", off, len(orig)) + orig
        buf += struct.pack("<I", len(new)) + new
    return bytes(buf)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--so", required=True, help="libil2cpp.so 路径")
    ap.add_argument("--out", help="写到这个路径（不给则只校验）")
    ap.add_argument("--verify", action="store_true", help="只校验不写文件（默认行为）")
    args = ap.parse_args()

    elf = Elf(args.so)
    entries = build_entries(elf)

    for off, orig, new, name in entries:
        va = elf.off_to_va(off)
        print("[verify] %-22s off=0x%07X  va=0x%07X  %s -> %s"
              % (name, off, va or 0, orig.hex().upper(), new.hex().upper()))

    blob = pack(entries)
    print("补丁条目 %d 个，so_patch.bin 大小 %d 字节" % (len(entries), len(blob)))

    if args.out and not args.verify:
        Path(args.out).write_bytes(blob)
        print("[write] %s" % args.out)
    else:
        print("[verify] 未写文件")


if __name__ == "__main__":
    main()
