#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 tools/names.txt：名字字体字形集 + 姓名对照表（唯一来源）。

为什么需要它：本地全部显示中文（NPC 名照常翻译，玩家可以用中文取名），上传时再换回
英文原名 / 拼音（NameFix.filterNames）。所以有两张表必须严格对齐：

  1. BitmapFont-Name 得有这些汉字的字形（bmfont_zh.py 读第一行）；
  2. 取名白名单得允许这些字（metadata_zh.py 读第一行）。
     白名单有、字体没有 = 玩家打出来是方块；字体有、白名单没有 = 根本打不出来。

用哪一撮字：NPC 全名表里的所有字 + 常见人名用字。不是整张 GB2312 ——
Name 字体挤进 6000+ 字形会把 Main_Atlas_jp 顶满。

用法（改完 translations/Name*_en.json 后重跑）：
    python tools/gen_names.py
"""
import io
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

OUT = 'tools/names.txt'

# 常见人名用字（姓 + 名）。
COMMON = (
    '明华强芳敏静丽秀英军磊洋勇艳杰娟涛超霞平刚桂文辉力宁康乐志伟东亮建国王李张刘陈杨黄'
    '赵吴周徐孙马朱胡林高何郭罗郑梁谢宋唐许韩冯邓曹彭曾萧田董潘袁蔡蒋余杜叶程苏魏吕丁任沈'
    '姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛段雷侯龙史陶黎贺顾毛郝龚邵钱严覃'
    '武戴莫孔向汤'
)


def main():
    zh = set()
    for n in ('Male', 'Female', 'Family'):
        zh.update(json.load(io.open('translations/Name%s_en.json' % n, encoding='utf-8')).values())
    zh = sorted(zh)
    chars = set(''.join(zh)) | {c for c in COMMON if '\u4e00' <= c <= '\u9fff'}

    lines = [
        '# 允许取名用的字符集（除原版白名单外，这里列出必须自带字形的汉字）。',
        '# 由 tools/gen_names.py 生成：NPC 全名表 + 常见人名用字。bmfont_zh.py 与 metadata_zh.py 都读这一行。',
        ''.join(sorted(chars)),
        '',
        '# 姓名对照（kind<TAB>英文<TAB>中文），与 assets/elneazh/data/names.tsv 同源。',
        '# 显示：英文 -> 中文；上传：中文名换回英文原名。',
    ]
    rows = 0
    for n, kind in (('Male', 'F'), ('Female', 'F'), ('Family', 'L')):
        en = io.open('translations/names_en/Name%s_en.txt' % n, encoding='utf-8-sig').read().splitlines()
        tr = json.load(io.open('translations/Name%s_en.json' % n, encoding='utf-8'))
        for i, e in enumerate(en):
            z = tr.get('L%d' % (i + 1), '').strip()
            if e.strip() and z:
                lines.append('%s\t%s\t%s' % (kind, e.strip(), z))
                rows += 1
    io.open(OUT, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
    print('names.txt: %d chars, %d name rows' % (len(chars), rows))


if __name__ == '__main__':
    main()
