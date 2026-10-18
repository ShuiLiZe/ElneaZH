# fonts

- `zh.ttf` — **Noto Sans SC Medium** (static TrueType instance at wght=500 of `NotoSansSC[wght].ttf`
  from https://github.com/google/fonts/tree/main/ofl/notosanssc, instanced with fontTools.varLib.instancer).
- License: SIL Open Font License 1.1, see `OFL.txt`.
- `tools/build_patch.py --font fonts/zh.ttf` subsets it to the characters actually used and puts it into
  Unity Font `default_android`.
