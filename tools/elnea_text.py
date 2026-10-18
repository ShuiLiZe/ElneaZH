"""Elnea Kingdom text-asset parser shared by export and apply scripts.

Every translatable unit is addressed by (file, uid). The uid is stable as long
as the original *_en asset is unchanged:
  - JSON-like / TalkData files: "s<N>" = N-th string literal in the file
  - ZoneHelp (^-separated):     "L<line>.F<field>"
  - one-entry-per-line lists:   "L<line>"
Writing back only replaces the inner text of those units, so keys, numbers,
scene/bgm ids and file layout stay byte-identical.
"""
import re

# Keys whose values are engine data, never display text.
DATA_KEYS = {
    'Camera', 'Scene', 'Wait', 'Target', 'Need', 'Motion', 'Function', 'Face', 'Mode',
    'Goal', 'Time', 'scene', 'bgm', 'bgm_night', 'WeatherType', 'map_x', 'map_y', 'Play',
    'type', 'Effect', 'index', 'map_type', 'icon', 'CameraType', 'SE', 'keyword', 'price',
    'collider_r', 'finished', 'view', 'sort_type', 'RightHand', 'LeftHand', 'FreeHand',
    'target', 'count', 'prefab', 'cancel', 'spectator', 'End', 'Agility', 'Strength',
    'Spirit', 'course', 'teacher', 'BGM', 'face', 'discount', 'ZoneTable', 'Next',
    'priority', 'condition', 'item', 'automove_x', 'automove_y', 'Plant', 'Panel', 'guide',
    'DateSpot', 'Fishing', 'Delivery', 'Mofu', 'Herb', 'Mine', 'Fullname', 'area',
    'RiverCollect',
}
# Files that hold no display text (id lists, profanity filter).
SKIP_FILES = {'TalkNew_en', 'TalkSpot_en', 'NGWord_en'}
LINE_FILES = {'NameFamily_en', 'NameFemale_en', 'NameMale_en', 'Tips_en'}
CARET_FILES = {'ZoneHelp_en'}
ZONE_TEXT_FIELDS = {1, 2, 3}          # name, action, help text
ZONE_FIELD_NAMES = {0: 'id', 1: '名称', 2: '动作', 3: '说明', 4: '图标'}
TALKDATA = 'TalkData_en'
PLACEHOLDER_ONLY = re.compile(r'^(\s*(\\n)*\s*\$\([^)]*\)\$\s*(\\n)*\s*)+$')
HAS_LETTER = re.compile(r'[A-Za-z\u3040-\u30ff\u4e00-\u9fff]')
NUMERIC = re.compile(r'^[\s\d.,:+\-]*$')

BOM = '\ufeff'


def decode(raw: bytes):
    s = raw.decode('utf-8')
    bom = s.startswith(BOM)
    return (s[1:] if bom else s), bom


def encode(s: str, bom: bool) -> bytes:
    return ((BOM if bom else '') + s).encode('utf-8')


def is_display(value: str, key) -> bool:
    if key in DATA_KEYS:
        return False
    if not value or not HAS_LETTER.search(value):
        return False
    if value in ('true', 'false') or PLACEHOLDER_ONLY.match(value) or NUMERIC.match(value):
        return False
    if key is None and re.fullmatch(r'[a-z0-9_]+', value):  # e.g. "stand", "happy" in TalkData
        return False
    return True


def lex_strings(s: str):
    """Yield (ordinal, start, end, value, path, is_key) for each "..." literal.
    start/end delimit the inner text (quotes excluded)."""
    stack = [{'key': None, 'idx': 0}]
    pending_key = None
    i, n, ordn = 0, len(s), 0
    while i < n:
        c = s[i]
        if c == '"':
            j = i + 1
            while j < n and s[j] != '"':
                j += 2 if s[j] == '\\' else 1
            val = s[i + 1:j]
            k = j + 1
            while k < n and s[k] in ' \t\r\n':
                k += 1
            is_key = k < n and s[k] == ':'
            top = stack[-1]
            path = [e['key'] if e['key'] is not None else f"[{e['idx']}]" for e in stack[1:]]
            if is_key:
                pending_key = val
            else:
                key = pending_key
                full = path + ([key] if key is not None else [f"[{top['idx']}]"])
                yield ordn, i + 1, j, val, full, key
                pending_key = None
            if is_key:
                yield ordn, i + 1, j, val, path + [val], '__KEY__'
            ordn += 1
            i = j + 1
            continue
        if c == '{':
            stack.append({'key': pending_key, 'idx': 0})
            pending_key = None
        elif c == '}':
            if len(stack) > 1:
                stack.pop()
            pending_key = None
        elif c == ',':
            stack[-1]['idx'] += 1
            pending_key = None
        i += 1


def units(name: str, text: str):
    """Return list of dicts: uid, path, value, (start,end) for display units."""
    out = []
    if name in SKIP_FILES:
        return out
    if name in LINE_FILES:
        for ln, line in enumerate(text.split('\n')):
            v = line.rstrip('\r')
            if v.strip():
                out.append({'uid': f'L{ln + 1}', 'path': f'第{ln + 1}行', 'value': v})
        return out
    if name in CARET_FILES:
        for ln, line in enumerate(text.split('\n')):
            fields = line.rstrip('\r').split('^')
            if len(fields) < 2:
                continue
            for fi, v in enumerate(fields):
                if fi in ZONE_TEXT_FIELDS and v.strip():
                    out.append({'uid': f'L{ln + 1}.F{fi}',
                                'path': f'{fields[0]} / {ZONE_FIELD_NAMES.get(fi, fi)}',
                                'value': v})
        return out
    talk_line_id = {}
    if name == TALKDATA:
        pos = 0
        for line in text.split('\n'):
            m = re.match(r'\s*\{\s*(\d+)', line)
            talk_line_id[pos] = m.group(1) if m else '?'
            pos += len(line) + 1
        starts = sorted(talk_line_id)
    for ordn, a, b, val, path, key in lex_strings(text):
        if key == '__KEY__':
            continue
        if name == TALKDATA:
            import bisect
            ls = starts[bisect.bisect_right(starts, a) - 1]
            tid = talk_line_id[ls]
            # count string fields on that line before this one
            field = text[ls:a].count('"') // 2 + 1
            label = {2: '台词', 3: '选项/回应'}.get(field, f'字段{field}')
            p = f'ID {tid} / {label}'
            if not is_display(val, None):
                continue
        else:
            p = '/'.join(path)
            if not is_display(val, key):
                continue
        out.append({'uid': f's{ordn}', 'path': p, 'value': val, 'span': (a, b)})
    return out


def esc_json(v: str) -> str:
    """Translator text -> literal inner text. Real newlines become \\n, bare quotes are escaped."""
    v = v.replace('\r\n', '\n').replace('\r', '\n').replace('\n', '\\n')
    v = re.sub(r'(?<!\\)"', '\\"', v)
    return v


def apply(name: str, text: str, tr: dict):
    """tr: uid -> translated string. Returns new text."""
    if not tr:
        return text
    if name in LINE_FILES:
        lines = text.split('\n')
        for uid, v in tr.items():
            ln = int(uid[1:]) - 1
            cr = '\r' if lines[ln].endswith('\r') else ''
            lines[ln] = v.replace('\n', ' ') + cr
        return '\n'.join(lines)
    if name in CARET_FILES:
        lines = text.split('\n')
        for uid, v in tr.items():
            l, f = uid.split('.')
            ln, fi = int(l[1:]) - 1, int(f[1:])
            cr = '\r' if lines[ln].endswith('\r') else ''
            fields = lines[ln].rstrip('\r').split('^')
            fields[fi] = v.replace('^', '＾').replace('\r', '').replace('\n', '\\n')
            lines[ln] = '^'.join(fields) + cr
        return '\n'.join(lines)
    spans = {u['uid']: u['span'] for u in units(name, text)}
    pieces, last = [], 0
    for uid in sorted(tr, key=lambda u: spans[u][0]):
        a, b = spans[uid]
        pieces.append(text[last:a])
        pieces.append(esc_json(tr[uid]))
        last = b
    pieces.append(text[last:])
    return ''.join(pieces)


def jp_name(en_name: str) -> str:
    return en_name[:-3] + '_jp'
