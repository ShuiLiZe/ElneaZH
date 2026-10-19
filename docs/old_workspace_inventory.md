# ElneaZH 旧工作区可用资产清单（_hark 盘点）

调查对象：`D:\workspaces\Elnea-Localization`（旧汉化工作区，约 1.9 万文件）。
调查方式：只读。用 `Get-ChildItem -Recurse` 按名/扩展名筛选 + Python/UnityPy 定点抽验，未逐个打开 `_scratch` 下几千个杂物文件。
产出时间：本次会话。所有条数均为实测计数，不是估计；未找到的项直接写「未找到」。

## 0. 结论速查

| 类别 | 旧方案是否有可用资产 | 可用程度 |
|---|---|---|
| ① global-metadata.dat 写死字符串 | **无译文**。只有 IL2CPP dump（16,137 条 stringliteral + 24 MB dump.cs + 66 MB script.json），metadata 本体与原始版**逐字节相同**（0 diffs）。已有 UI 词典只覆盖其中 84–102 条 | 只能当「字符串在哪」的地图，没有中文 |
| ② 教程图 fig_XXX_en | **只缓存到 1 张** fig_001_en（英文原图，未汉化）；另有 4 张只在 `_delta` 增量缓存里（fig_000/009/010/062_en），未解包、无中文 | 原文件齐全度低，无译文 |
| ③ 战斗字 / icon_talk | **原图齐全**：Main_Atlas_jp.png（2048x2048，含 Counter Break! / Guard Break! / Counter Damage! / Miss! 等）+ 精确精灵坐标 json；icon_talk 精灵坐标齐全。旧方案只汉化了 Main_Atlas_**en**，没动 jp 图集 | 原图+坐标可直接复用，无中文战斗字 |
| ④ 喷泉广场拉丁文气泡 | 已定位：文字不在下载的场景 bundle 里（无 TextAsset / 无文字贴图），而在 APK 的 `data.unity3d` 文本表与 `c_message`；旧方案有 `交付记录_气泡文本来源定位.md` 完整结论 + 已翻译的 `TalkData_en.txt`(2100 行) / `TalkActivity_en.txt`(71 行) | **可用**：原文件+译文+来源定位三者齐全 |
| ⑤ 其他界面零散英文 | en2zh.json 16,716 条（16,652 条含中文）、c_en2zh.json 20,898 条（20,884 条含中文）、`_zh` 整文件译文 631 个；另有 1220 条「借壳字」映射表 | **可用程度最高**，但借壳字方案本身是缺陷 |
| 人名译名对照 | NameMale/NameFemale/NameFamily 各 600 行英→中；`.tmp\name_map.json` 1,728 条日→中；旧方案存档 NPC 改名有 ID 错位 bug（见 §4） | 可用，但要防错位 |

## 1. 旧方案到底汉化了什么、产物在哪

### 1.1 交付记录 / 验证记录文件（任务第 1 项）
根目录 4 个交付记录：
- `D:\workspaces\Elnea-Localization\交付记录-v2签名打通-v0.0.5.md`（7711 B）
- `D:\workspaces\Elnea-Localization\交付记录-v3签名与上机整链-v0.0.6.md`（12376 B）
- `D:\workspaces\Elnea-Localization\交付记录-免root全汉化-v0.0.7.md`（9289 B）
- `D:\workspaces\Elnea-Localization\交付记录-真汉字字形方案-v2.10.7.md`（8418 B）

`docs\` 下的交付/验证记录（UTF-8），与本任务直接相关的：
- `D:\workspaces\Elnea-Localization\docs\交付记录_气泡文本来源定位.md`（10365 B）
- `D:\workspaces\Elnea-Localization\docs\交付记录_双引擎汉化_20261016.md`（5768 B）
- `D:\workspaces\Elnea-Localization\docs\交付记录_伊姆动画与气泡对话框_20261015.md`（3389 B）
- `D:\workspaces\Elnea-Localization\docs\交接文档_电脑端汉化研测战役_v5.md`（6361 B）
- `D:\workspaces\Elnea-Localization\docs\会诊_claude.md`（18033 B）
- `D:\workspaces\Elnea-Localization\docs\发布文案_汉化包_20261019.md`（7425 B）
- `D:\workspaces\Elnea-Localization\docs\worklist_stats.txt`（1094 B）
- `D:\workspaces\Elnea-Localization\docs\glyph_subst_report.txt`（6157 B）
- `D:\workspaces\Elnea-Localization\docs\cache_tex_inventory.txt`（90278 B）
- `D:\workspaces\Elnea-Localization\docs\bundle_inventory.json`（95923 B）

### 1.2 旧方案汉化范围

- **22 张 c_* 主数据表**：词典 `D:\workspaces\Elnea-Localization\02_extracted\contents\c_en2zh.json`（2181652 B），**20,898 键值对，20,884 条含中文**（与 `docs\发布文案_汉化包_20261019.md:5` 记载的「20,898 条、333,851 个中文字符」一致）。分批译文在 `D:\workspaces\Elnea-Localization\02_extracted\contents\zh_c`（52 个 batch_NNN.json，合计 1,238,879 B）。
- **资源文本（APK data.unity3d 内 630 个文本表）**：原文件 `D:\workspaces\Elnea-Localization\02_extracted\strings\_raw`（630 个），译文 `D:\workspaces\Elnea-Localization\02_extracted\strings\_zh`（631 个）。对 402 个可对比文件做中文字符增量统计，**177 个文件确认已汉化**。
  - 例：`LessonData_en.tsv` 0→15,636 个中文字；`DinnerMessage_en.tsv` 0→12,422；`AchievementData_en.tsv` 1,286→4,434；`TalkData_en.txt` 2,100 行中 3,993 处中文；`TalkActivity_en.txt` 71 行中 153 处中文。
- **字符层词典**：`D:\workspaces\Elnea-Localization\02_extracted\strings\en2zh.json` 16,716 键 / 16,652 条含中文（其中 3,149 条值含换行，说明多行对话框文本也在这）；`en2zh.subst.json` 同 16,716 键，是「借壳字形」替换版（与 en2zh.json 有 14,059 条不同）。
- **位图字库 / 图集**：`D:\workspaces\Elnea-Localization\02_extracted\assetbundle\tex_zh` 下 Main_Atlas_en.png（313,845 B，**已汉化**）、Main_Atlas_jp.png（3,328,614 B，改过但战斗字仍是英文）。
- **最终产物**：`D:\workspaces\Elnea-Localization\03_patch_output\ElneaKingdom_汉化版_glyph.apk`（115,551,995 B）；另有 `D:\workspaces\Elnea-Localization\03_patch_output\elnea_zh_full_v6.zip`（73,756,591 B）、`D:\workspaces\Elnea-Localization\03_patch_output\elnea_zh_unitycache.zip`（68,409,562 B）。
- **缺陷（必须知道）**：旧方案用「借壳字」解决字库缺字——`D:\workspaces\Elnea-Localization\docs\glyph_subst.json` 把缺字映射到字库里已有的形近字（cjk 1,220 条 + punct 3 条，例：专→丙、业→丞、你→傭、啊→墜、吗→営），共替换 48,129 个字符，命中缺字 814/1220 种。`docs\glyph_subst_report.txt` 有完整样例（`\n要参加吗？` → `\n要参加営？`）。**新仓库若已做 NGUI 位图字库，应直接使用标准简体字，不要沿用借壳字**。

### 1.3 docs\ 里的规范文件（译名/文风唯一权威源）

- `D:\workspaces\Elnea-Localization\docs\glossary.md`（3687 B）
- `D:\workspaces\Elnea-Localization\docs\翻译规范.md`（2800 B）
- `D:\workspaces\Elnea-Localization\docs\翻译规范_v2.md`（6028 B）
- `D:\workspaces\Elnea-Localization\docs\翻译规范_c.md`（5065 B）
- `D:\workspaces\Elnea-Localization\docs\文风规范.md`（7343 B）

`docs\glossary.md` 关键译名（可直接复用）：Elnea→艾尔涅亚、Ena→艾娜、Ihm→伊姆、Moff→莫夫、Mog→莫格、Rudder→拉达、Nordevon Rudder→拉达·诺尔德冯、Bagwell→巴格威尔、Miara→米亚拉、Manana→玛娜娜、Maru→玛露、Phomos→福莫斯、Shiznee→希兹妮、Dhorme→多尔梅、Roselle→罗赛尔、Soleida→索雷达、Niv→尼夫、Ihmemon→伊姆卫门；Elnea Wharf→艾尔涅亚码头、Castle Dock→城堡码头、Fountain Plaza→喷泉广场、Imperial Guardsmen→近卫兵团、Scholars Association→学者协会（`文风规范.md` 另注：日文 魔銃師会→魔铳师会，以日文为准）、Knight Corps→骑士团、Battle Arena→斗技场、Harvest→收获祭、White Night→白夜祭；Combo Bonus→连击奖励、Charge→充能、Might→战力、Support→支援、Cool→冷静、Remove→卸下；Chairman→议长、Council→议会、Hero→勇者、Knight→骑士、Scholar→学者、Partner→恋人。

## 2. 下载 bundle 原文件（UnityCache / Shared / __data）

### 2.1 标准缓存池

- 根：`D:\workspaces\Elnea-Localization\01_raw_data\files\UnityCache\Shared`
- 结构：`Shared\<bundle名>.android\<32位hex>\__data` + `__info`（每 `__info` 恒 23 B）
- **共 197 个 `__data`，合计 77,063,441 B**；`__info` 每 bundle 1 个。
- 索引文件：`D:\workspaces\Elnea-Localization\docs\bundle_inventory.json`（95923 B，键 = `summary` / `bundles`，每个 bundle 给 `path` / `size` / `types` / `textassets` / `fonts`）。summary 汇总：AssetBundle 196、Texture2D 654、TextAsset 630（TextAsset 全部来自 `data.unity3d`，**197 个下载 bundle 里 `textassets` 全为空数组**，即下载包里没有任何文本资源）。
- 贴图规格索引：`D:\workspaces\Elnea-Localization\docs\cache_tex_inventory.txt`（逐条列 bundle→Texture2D 名/尺寸/format）。

### 2.2 增量缓存 `_delta`（fig 教程图主要在这）

- 根：`D:\workspaces\Elnea-Localization\01_raw_data\files\UnityCache\_delta`
- **87 个文件，10,846,113 B**，命名格式 `Shared__<bundle>.android__<hex>____data`
- 含 fig：`fig_000_en`(hex 4b51a169facdc0e2e7770d498091df48, 485,413 B, MD5 05D247BB4A9CEE66906B0A10614D4425)、`fig_009_en`(4fdfff8abb0dadceeb2f8115e21f46be, 406,423 B)、`fig_010_en`(a288f8f55300d0403d1d8513247250ad, 751,233 B)、`fig_062_en`(0196c0acd16bf5b012390b6fc58d7d05, 736,572 B)

### 2.3 打包好的整包（免解压直接用）

- `D:\workspaces\Elnea-Localization\03_patch_output\unitycache.zip`（68405498 B；394 entries，bundle __data 197 个，fig 仅 fig_001_en 的 __data+__info）
- `D:\workspaces\Elnea-Localization\03_patch_output\elnea_zh_unitycache.zip`（68409562 B；与 unitycache.zip 完全相同的 394 entries）
- `D:\workspaces\Elnea-Localization\03_patch_output\elnea_zh_full_v6.zip`（73756591 B；416 entries，fig=2，bundle __data 197）
- `D:\workspaces\Elnea-Localization\03_patch_output\elnea_cn_module_v1.zip`（101073150 B）

### 2.4 单独另存的大地图 / 场景 bundle

- `D:\workspaces\Elnea-Localization\01_raw_data\files_extra\UnityCache\Shared\map_large_en.android\9e41473f5b511831105ea0f1695a843f\__data`（大地图 bundle，812,786 B）
- `D:\workspaces\Elnea-Localization\01_raw_data\files\UnityCache\Shared\area_fountainpark.android\0668fb9cf78d324131269a2f544315be\__data`（喷泉广场场景，3,103,780 B）
- `D:\workspaces\Elnea-Localization\01_raw_data\files\UnityCache\Shared\area_fountainstreet.android\ff53215eb1ae222f6cb34df8f3cf473a\__data`（喷泉街场景，3,012,995 B）
- area_* 系列共 **19 个**（castlegate、castlestreet、farm1、farm2、farm7_1、farmstreet、forest1_k、gatepark、marketstreet、port1、ranch、ranch2、river_k、shrine、suburbstreet、tavern1、woodenbridge 等）。

## 3. ① global-metadata.dat 相关

### 3.1 metadata 本体：**没有汉化，与原始版逐字节相同**

- `D:\workspaces\Elnea-Localization\01_raw_data\files\il2cpp\Metadata\global-metadata.dat`（9660464 B）
- `D:\workspaces\Elnea-Localization\02_extracted\apk\assets\bin\Data\Managed\Metadata\global-metadata.dat`（9660464 B）
- `D:\workspaces\Elnea-Localization\02_extracted\apktool_out\assets\bin\Data\Managed\Metadata\global-metadata.dat`（9660464 B）
- `D:\workspaces\Elnea-Localization\_work_ds41f\dev_metadata.dat`（9660464 B）

逐字节比对 `01_raw_data\files\il2cpp\Metadata\global-metadata.dat` 与 `_work_ds41f\dev_metadata.dat`：**byte diffs = 0，大小同为 9,660,464**。→ metadata 里没有任何中文替换。

### 3.2 IL2CPP dump（只有原文，没有译文）

- `D:\workspaces\Elnea-Localization\_scratch\il2cpp_dump\out\stringliteral.json`（1377141 B；list 共 **16,137 条**，每条 {value, address}；纯 ASCII 15,647 条，含 CJK（多为日文乱码样本）83 条；UI 候选（3–40 字符、首字母大写、ASCII 占比>95%）**5,266 条**）
- `D:\workspaces\Elnea-Localization\_scratch\il2cpp_dump\out\dump.cs`（24153919 B；24,153,919 B）
- `D:\workspaces\Elnea-Localization\_scratch\il2cpp_dump\out\script.json`（66741979 B；66,741,979 B）
- `D:\workspaces\Elnea-Localization\_scratch\il2cpp_dump\out\il2cpp.h`（61979266 B；61,979,266 B）
- dump 工具链：`D:\workspaces\Elnea-Localization\_scratch\il2cpp_dump`（含 bin/ tool/ sdk/ Redux/ DazedTL/ md_dazed.cs md_zzz.cs log.txt err.txt）—— 可直接重跑 dump。

### 3.3 覆盖情况（关键数字）

- stringliteral 16,137 条中，被 `en2zh.json` 精确覆盖的只有 **102 条**；按 UI 候选口径算 5,266 条里仅 **84 条**有中文。
- 未覆盖的大多是 Unity/Mono 引擎内部报错串（`A Unity Runtime error occurred!`、`A child row has multiple parents.`、`A task was canceled.`、`AED` 等），真正上屏的 UI 词（Accept/Add/Adult/Age/Apr./April/Aug./Birthday/Black/Blue…）多数**尚未翻译**。
- 全树按文件名搜 `literal` / `metadata` 的 json/csv/txt：**只有上面这些 dump，没有任何 `stringliteral_zh.json` / `literal_zh` / `literals_*` 译文产物 → 未找到。**

## 4. 任务第 4 项：人名译名对照

### 4.1 英→中姓名表（600×3，最贴合「Conan→柯南」这种需求）

- `D:\workspaces\Elnea-Localization\02_extracted\strings\_zh\NameMale_en.txt`（6215 B）
- `D:\workspaces\Elnea-Localization\02_extracted\strings\_zh\NameFemale_en.txt`（6125 B）
- `D:\workspaces\Elnea-Localization\02_extracted\strings\_zh\NameFamily_en.txt`（6430 B）

- 各 **600 行英→中**（例：Serge→塞尔吉、Edward→爱德华；Eleanor→埃莉诺、Gabriella→加布里埃拉；Lydon→莱登、Serkis→塞尔基斯）。
- 来源侧英文原件：`D:\workspaces\Elnea-Localization\02_extracted\strings\_raw\NameMale_en.txt` 等（同目录）。

### 4.2 日→中姓名表

- `D:\workspaces\Elnea-Localization\.tmp\name_map.json`（54759 B）：**1,728 条「日文片假名名 → 中文名」**。不是英→中，需与日文名表连用。

### 4.3 活动名

- `D:\workspaces\Elnea-Localization\02_extracted\strings\_zh\EventName_en.tsv`（25699 B）与 `D:\workspaces\Elnea-Localization\docs\EventName_en_zh_final.tsv`（25681 B）

### 4.4 ⚠ 已知坑：旧方案存档内 NPC 改名 ID 错位

- 证据文件：`D:\workspaces\Elnea-Localization\02_extracted\save_npc_names_check.txt`（1790 B）
- 现象：ID:24 Gerda 被译成「娜迪亚」、ID:25 Darren→阿诺德、ID:41 Alexander→维克托、ID:45 Hugo→吉纳。
- 结论：姓名表本身可用，但**按 ID 回填存档的逻辑不可照搬**，新仓库应用名字哈希/字符串匹配而非序号。

## 5. 每类数量汇总（实测）

| 类别 | 原文件数量 | 已有中文数量 | 备注 |
|---|---|---|---|
| ① metadata 字符串 | dump 16,137 条（UI 候选 5,266） | **0 条**（词典旁路覆盖 84–102 条） | metadata 本体 0 字节改动 |
| ② fig 教程图 | 标准缓存 1 张（fig_001_en），_delta 另 4 张 | **0 张** | fig_001_en 已抽到 `_hark\fig_001_en.png` |
| ③ Main_Atlas_jp 战斗字 | 图集 1 张 2048x2048，英文字样 7+ 处 | **0 处** | 坐标见 §6 |
| ③ icon_talk | 精灵 96x96（x=833 y=726） | **0 个** | 在 Main_Atlas_jp 图集内 |
| ④ 气泡/对话文本 | TalkData_en 2,100 行 + TalkActivity_en 71 行 + c_message | TalkData 已译、TalkActivity 已译 | 来源定位有专文 |
| ⑤ 界面零散英文 | en2zh 16,716 键 / c_en2zh 20,898 键 | 16,652 / 20,884 条 | 另有 `_zh` 整文件 631 个 |
| 人名 | 中文名 1,800 条（600×3）+ 日文名 1,728 条 | 全部已译 | 存档回填有错位 bug |

## 6. 可直接复用的坐标与规格（③ 战斗字 / icon_talk）

- 精灵坐标表：`D:\workspaces\Elnea-Localization\02_extracted\assetbundle\tex_zh\ngui_atlas_jp.json`（37163 B，count=289，字段含 x/y/w/h/bl/br/bt/bb/pl/pr）
- 关键精灵：`cbreak_mark` x=0 y=19 w=192 h=88；`break_mark` x=425 y=233 w=192 h=84；`counter_mark` x=512 y=540 w=191 h=84；`mark_counter` x=1611 y=469 w=56 h=56；`icon_talk` x=833 y=726 w=96 h=96；`encount_mark` x=930 y=730 w=92 h=92。
- 其他同源坐标表：`D:\workspaces\Elnea-Localization\docs\ngui_atlas.json`（104930 B）、`docs\atlas_34351.json`、`docs\atlas_34352.json`、`docs\atlas_34392.json`、`docs\atlas_40718.json`、`docs\mb_string_hunt.json`。
- 图集 PNG：`D:\workspaces\Elnea-Localization\02_extracted\assetbundle\tex\Main_Atlas_jp.png`（3,202,329 B，原版）、`D:\workspaces\Elnea-Localization\02_extracted\assetbundle\tex_zh\Main_Atlas_jp.png`（3,328,614 B，改过但战斗字仍英文）、`D:\workspaces\Elnea-Localization\02_extracted\assetbundle\tex_zh\Main_Atlas_en.png`（313,845 B，**已汉化**）。
- 同目录另有 Sub1_Atlas_jp.png（4,586,121 B）、Sub1_Atlas_en.png（617,545 B）、Font Texture.png、now_loading_en/jp、meal_*、call_*；`tex_extra\`（5 png，11,463,887 B，含 map_large_en.png 2,703,353 B）、`tex_probe\`（5 png）。

## 7. 可复用的脚本（tools\）

- `D:\workspaces\Elnea-Localization\tools\c_apply.py`（5280 B；c_* 主数据回填：解密→原位替换→重加密，带 --roundtrip 往返校验）
- `D:\workspaces\Elnea-Localization\tools\tex_repack.py`（6948 B；把 tex_zh/*.png 编码回 data.zh.unity3d（保持原格式：ETC2_RGBA8 / RGB24 / ASTC 6x6 / Alpha8））
- `D:\workspaces\Elnea-Localization\tools\repack_tex_bundle.py`（4117 B；把一张 PNG 塞回 UnityFS 包（__data）里的指定 Texture2D 并自验）
- `D:\workspaces\Elnea-Localization\tools\atlas_redraw.py`（14579 B；把 Main_Atlas 里烘焙的英文标签换成中文（按切片「文字带」铲墨迹+居中写中文））

## 8. 未找到 / 无法提供的东西

- **global-metadata.dat 的任何中文译文产物**（无 stringliteral_zh.json、无 literal 对照表）——未找到。
- **fig_XXX_en 教程图的中文版**——未找到。旧文档 `docs\交接文档_电脑端汉化研测战役_v5.md:34` 声称「新手引导教程图片完整生效」，但实测只有 1 张英文原图，**该说法没有产物支撑**。
- **Main_Atlas_jp 战斗字的中文贴图**——未找到（只有 en 图集汉化版）。
- **喷泉广场拉丁文气泡的中文贴图**——不存在也不需要：文字来自文本表，不在贴图里。

