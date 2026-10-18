#!/usr/bin/env python3
"""Export every display string of the *_en TextAssets into one translation sheet.

usage: python tools/export_xlsx.py <data.unity3d> <out.xlsx> [translations_dir]
If translations_dir (translations/*.json) is given, existing Chinese is filled in.
"""
import sys, os, json, re, collections
import UnityPy
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment
from openpyxl.worksheet.datavalidation import DataValidation

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import elnea_text as E

PH = re.compile(r'\$\([^)]*\)\$|\{\d+(?::[^}]*)?\}|\\n')

CATEGORY = [
    ('SystemText_en', '界面/系统'), ('AreaData_en', '地名'), ('ZoneHelp_en', '地点/采集'),
    ('Name', '人名(随机名库)'), ('TalkData_en', '对话'), ('Talk', '对话'), ('evt', '事件剧情'),
    ('Lesson', '学校课程'), ('Dinner', '晚餐对话'), ('Achievement', '成就'), ('EventName', '日程/事件名'),
]


def category(n):
    for p, c in CATEGORY:
        if n.startswith(p):
            return c
    return '其他'


def load_assets(bundle):
    env = UnityPy.load(bundle)
    out = {}
    for o in env.objects:
        if o.type.name == 'TextAsset':
            d = o.read()
            s = d.m_Script
            out[d.m_Name] = s.encode('utf-8', 'surrogateescape') if isinstance(s, str) else bytes(s)
    return out


def jp_lookup(en_name, jp_raw):
    if jp_raw is None:
        return {}
    t, _ = E.decode(jp_raw)
    seen = collections.Counter()
    m = {}
    for u in E.units(en_name, t):
        key = u['path'] if en_name not in E.LINE_FILES else u['uid']
        seen[key] += 1
        m[(key, seen[key])] = u['value']
    return m


def main():
    bundle, out = sys.argv[1], sys.argv[2]
    tdir = sys.argv[3] if len(sys.argv) > 3 else None
    assets = load_assets(bundle)
    wb = Workbook()
    ws = wb.active
    ws.title = '全部文本'
    head = ['ID(勿改)', '文件', '分类', '路径/键', '日文(参考)', '英文(原文)', '中文译文', '状态', '备注']
    ws.append(head)
    rows = 0
    stats = collections.Counter()
    for name in sorted(n for n in assets if n.endswith('_en')):
        t, _ = E.decode(assets[name])
        us = E.units(name, t)
        if not us:
            continue
        done = {}
        if tdir and os.path.exists(os.path.join(tdir, name + '.json')):
            done = json.load(open(os.path.join(tdir, name + '.json'), encoding='utf-8'))
        jp = jp_lookup(name, assets.get(E.jp_name(name)))
        seen = collections.Counter()
        for u in us:
            key = u['path'] if name not in E.LINE_FILES else u['uid']
            seen[key] += 1
            jv = jp.get((key, seen[key]), '')
            note = []
            phs = sorted(set(PH.findall(u['value'])) - {'\\n'})
            if phs:
                note.append('保留: ' + ' '.join(phs))
            if name in E.LINE_FILES and name.startswith('Name'):
                note.append('随机名库,可不译')
            if name == 'Replace_en':
                note.append('旧存档替换表,可不译')
            zh = done.get(u['uid'], {}).get('zh', '') if isinstance(done.get(u['uid']), dict) else done.get(u['uid'], '')
            ws.append([f"{name}|{u['uid']}", name, category(name), u['path'], jv, u['value'], zh,
                       '已译' if zh else '', '；'.join(note)])
            rows += 1
            stats[category(name)] += 1
    widths = [22, 20, 12, 30, 45, 60, 60, 8, 24]
    for i, w in enumerate(widths):
        ws.column_dimensions[chr(65 + i)].width = w
    hf = Font(bold=True, color='FFFFFF')
    for c in ws[1]:
        c.font = hf
        c.fill = PatternFill('solid', fgColor='4F6D7A')
    for row in ws.iter_rows(min_row=2):
        for c in row[3:7]:
            c.alignment = Alignment(wrap_text=True, vertical='top')
        row[6].fill = PatternFill('solid', fgColor='FFF7D6')
    ws.freeze_panes = 'E2'
    ws.auto_filter.ref = f'A1:I{rows + 1}'

    info = wb.create_sheet('说明')
    lines = [
        ['Elnea Kingdom 2.10.7 汉化对照表'],
        [f'共 {rows} 条，来自 {len({r[0].value.split("|")[0] for r in ws.iter_rows(min_row=2)})} 个 *_en 文本资源。'],
        ['只填「中文译文」列；留空 = 保持英文。不要改 ID 列和行顺序无关（按 ID 回填）。'],
        ['必须原样保留：$(xxx)$ 变量、{0} {1:D} 格式符、\\n 换行（也可以在单元格里直接换行）。'],
        ['ZoneHelp 里不要用 ^ 符号；人名库(Name*)和 Replace_en 可以不译。'],
        ['日文列来自游戏自带的 *_jp 资源，供参考；AchievementData_en 和部分 EventName_en 英文版本身就是日文。'],
        [''], ['分类', '条数']] + [[k, v] for k, v in stats.most_common()]
    for l in lines:
        info.append(l)
    info.column_dimensions['A'].width = 90
    info['A1'].font = Font(bold=True, size=14)
    wb.move_sheet('说明', offset=-1)
    wb.active = 1
    wb.save(out)
    print(f'{rows} rows -> {out}')


if __name__ == '__main__':
    main()
