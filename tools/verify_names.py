"""离线校验名字映射表与拼音表（不上手机就能跑）。

检查：
  1. names.tsv 英文列无 CJK（英文名必须是原版 ASCII）
  2. 显示映射 英文 -> 中文 抽样正确
  3. 反向 中文 -> 英文 可逆
  4. 拼音表覆盖与抽样（铁蛋 -> TieDan）
  5. --save 时用伪存档验证名字槽扫描能找到中文名

用法：
    python tools/verify_names.py [--save <dir>]
"""
import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NAMES = ROOT / 'app/src/main/assets/elneazh/data/names.tsv'
PINYIN = ROOT / 'app/src/main/assets/elneazh/data/pinyin.tsv'

CJK_RANGES = [(0x2E80, 0x9FFF), (0xF900, 0xFAFF), (0xFF00, 0xFFEF), (0x3000, 0x303F)]


def is_cjk(ch):
    o = ord(ch)
    return any(lo <= o <= hi for lo, hi in CJK_RANGES)


def to_pinyin(text, table):
    """复刻 Pinyin.toAscii 的兜底路径：逐字查表，首字母大写拼 camel。"""
    out = []
    for ch in text:
        if is_cjk(ch):
            py = table.get(ch)
            out.append(py.capitalize() if py else '_')
        elif ch.isalnum() or ch == '_':
            out.append(ch)
    return ''.join(out)


def tidy(s):
    """复刻 Pinyin.tidy：去非字母数字，首字母大写。"""
    s = ''.join(c for c in s if c.isalnum() or c == '_')
    return s[:1].upper() + s[1:] if s else s


def load_names():
    en2zh, zh2en, last_en2zh, last_zh2en = {}, {}, {}, {}
    for line in open(NAMES, encoding='utf-8'):
        line = line.rstrip('\n')
        if not line or line.startswith('#'):
            continue
        parts = line.split('\t')
        if len(parts) < 3:
            continue
        kind, en, zh = parts[0], parts[1], parts[2]
        if not en or not zh:
            continue
        if kind == 'L':
            last_en2zh[en] = zh
            last_zh2en.setdefault(zh, en)
        else:
            en2zh.setdefault(en, zh)
            zh2en.setdefault(zh, en)
    return en2zh, zh2en, last_en2zh, last_zh2en


def load_pinyin():
    t = {}
    if not PINYIN.is_file():
        return t
    for line in open(PINYIN, encoding='utf-8'):
        line = line.rstrip('\n')
        if not line or line.startswith('#'):
            continue
        p = line.split('\t')
        if len(p) >= 2 and p[0] and len(p[0]) == 1:
            t[p[0]] = p[1]
    return t


def scan_save(path):
    """扫 sv1_ 文件里的名字槽（0x01 + 1 字节长度 + UTF-8），统计带 CJK 的名字。"""
    import zlib
    raw = open(path, 'rb').read()
    data = zlib.decompress(raw) if raw[:1] == b'\x78' else raw
    out = []
    i = 0
    n = len(data)
    while i < n - 2:
        if data[i] != 0x01:
            i += 1
            continue
        ln = data[i + 1]
        if not (1 <= ln <= 64) or i + 2 + ln > n:
            i += 1
            continue
        try:
            s = data[i + 2:i + 2 + ln].decode('utf-8')
        except UnicodeDecodeError:
            i += 1
            continue
        if s and all(32 <= ord(c) < 0x3000 or is_cjk(c) for c in s):
            out.append(s)
            i += 2 + ln
        else:
            i += 1
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--save', help='伪存档目录（tools/make_save_sample.py 生成）')
    a = ap.parse_args()

    en2zh, zh2en, last_en2zh, last_zh2en = load_names()
    pin = load_pinyin()
    bad = 0

    print(f'names.tsv: {len(en2zh)} first, {len(last_en2zh)} last  (en keys {len(en2zh) + len(last_en2zh)})')
    print(f'pinyin.tsv: {len(pin)} chars')

    cjk_keys = [k for k in list(en2zh) + list(last_en2zh) if any(is_cjk(c) for c in k)]
    print(f'CJK english keys: {len(cjk_keys)}')
    if cjk_keys:
        bad += 1
        print('   ', cjk_keys[:10])

    print('--- display  en -> zh ---')
    for en, want in (('Serge', '塞尔吉'), ('Edward', '爱德华'), ('Ruben', '鲁本'),
                     ('Carlos', '卡洛斯'), ('Igor', '伊戈尔')):
        got = en2zh.get(en)
        ok = got == want
        if not ok:
            bad += 1
        print(f'{"OK " if ok else "BAD"} {en} -> {got} (want {want})')

    print('--- reverse  zh -> en ---')
    for zh, want in (('塞尔吉', 'Serge'), ('爱德华', 'Edward'), ('鲁本', 'Ruben')):
        got = zh2en.get(zh)
        ok = got == want
        if not ok:
            bad += 1
        print(f'{"OK " if ok else "BAD"} {zh} -> {got} (want {want})')

    print('--- pinyin ---')
    for zh, want in (('铁蛋', 'TieDan'), ('王小明', 'WangXiaoMing'), ('阿狗', 'AGou')):
        got = tidy(to_pinyin(zh, pin))
        ok = got == want
        if not ok:
            bad += 1
        print(f'{"OK " if ok else "BAD"} {zh} -> {got} (want {want})')

    if a.save:
        from pathlib import Path as P
        for f in sorted(P(a.save).glob('sv1_*')):
            names = scan_save(f)
            withcjk = [n for n in names if any(is_cjk(c) for c in n)]
            print(f'save {f.name}: {len(names)} names, {len(withcjk)} with CJK -> {withcjk}')

    print('names OK' if bad == 0 else f'names WRONG ({bad})')
    return 0 if bad == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
