"""造一份假的 sv1_ 存档，用来离线验证名字槽扫描 / 还原逻辑（不上真机）。

记录格式来自 inj/SaveNames.java 与 Backup.java 的实测：
  [u32 count][9 字节头] 然后每条 [u32 id][0x01][u8 len][UTF-8 name][25B 零]
  除最后一条外，每条后面再跟 9 字节 link
文件整体 zlib 压缩（首字节 0x78）。

用法：
    python tools/make_save_sample.py <outdir>
    python tools/verify_names.py --save <outdir>
"""
import argparse
import os
import struct
import zlib


def rec(rid, name):
    b = name.encode('utf-8')
    assert 1 <= len(b) <= 64, name
    return struct.pack('<I', rid) + b'\x01' + bytes([len(b)]) + b + b'\x00' * 25


def build(names):
    body = b''
    for i, (rid, nm) in enumerate(names):
        body += rec(rid, nm)
        if i != len(names) - 1:
            body += b'\x00' * 9
    header = struct.pack('<I', len(names)) + b'\x00' * 9
    return header + body


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('out')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    profiles = [(1, 'Serge'), (2, 'Edward'), (3, '铁蛋'), (4, '王小明')]
    familys = [(1, 'Smith'), (2, 'Serkis'), (3, '李')]
    for name, rows in (('sv1_ws_profiles', profiles), ('sv1_ws_familys', familys)):
        raw = build(rows)
        p = os.path.join(a.out, name)
        with open(p, 'wb') as f:
            f.write(zlib.compress(raw, 9))
        print(f'{p}: {len(rows)} records, {os.path.getsize(p)} bytes (zlib)')


if __name__ == '__main__':
    main()
