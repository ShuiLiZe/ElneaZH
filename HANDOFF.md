# HANDOFF — 现状（只写当前状态；过程见 docs/CHANGELOG.md，规矩见 AGENTS.md）

_最后更新：2026-10-20 10:50 CST_

## 现在能用的版本
- Release `latest` 的 ElneaZH.apk（每次推送自动构建）。装前先在游戏「Support → 存档数据」备份，卸载原版后安装汉化版（签名不同）。
- 已包含：11,093 条资源文本中文；NGUI 位图字库补齐 GB2312 6,763 字；10 张中文贴图（ETC2 红变蓝已修，mip 保留）；global-metadata.dat 补丁（取名白名单加汉字）。

## 固定签名 / 免卸载更新（21:05 加入，待真机验证）
- 签名本来就固定（app/keystore/elneazh.p12），只有第一次从商店版换汉化版要卸载。
- App 现在会：检测已装游戏是否是自己签名的汉化版 → 是就提示直接覆盖安装；第一次汉化成功后把原版 APK 存到 App 私有目录（filesDir/orig），以后用它打补丁。没存过的话让用户手动选原版 APK（仓库 base/apk 有）。

## 已知问题（详情和截图在 Issues）
- **大块英文（物品/料理/服装名、教程、地图图片）来自服务器下发的主数据和运行时下载的 bundle，不在 APK 里**。分析与方案见 docs/CHANGELOG.md 2026-10-18 21:10（推荐：Cloudflare Worker 中转代理替换译文）。**更新：用户旧方法可本地完成（改 files/Contents/c_* 表 + map bundle + libil2cpp.so 两处补丁），见 CHANGELOG 2026-10-18 21:20，优先走这条。** 用户 v6 数据已入库（content_zh/，解密工具 tools/contents_codec.py），但文本用了借字码位，需旧 APK 还原映射，见 CHANGELOG 2026-10-18 21:40。
- 现有 NPC 名字存在存档里，新生成的人才是中文名。
- 真机仍有不少英文：多半是代码里写死的字符串（global-metadata.dat），候选在 `translations/metadata_candidates.txt`，要按截图逐句填 `translations/metadata_literals.json`。
- 取中文名：白名单和名字字库都已加汉字，待真机确认；不行就 Il2CppDumper 看 CheckCharacterName。
- 字号一大一小：待 #48 之后的版本真机确认；备选方案是把 NGUIFont 切成动态 TTF（见 CHANGELOG）。
- 战斗字（Counter Break! 等，在 Main_Atlas_jp）和 icon_talk 小字仍是英文。

## 下三步
1. 用户真机测最新版，截图贴到对应 issue。
2. 按截图翻 metadata_literals.json（只翻游戏可见文字）。
3. 取名若仍失败：Il2CppDumper 反查校验逻辑。

## 2026-10-18 21:40 方向确认
- 用户明确：**不要借壳**（旧王国助手的 Root/Xposed/bind mount、/sdcard/ElneaAssistant 重定向都不用）。
- 只复用 v6 译文；由本仓库汉化器打进游戏包、免 Root 生效。
- 待解决：① 借字已还原（tools/unborrow.py，侘鱼→岩鱼）；下一步校对译文；② 免 Root 让游戏读到汉化表的方式（例如把表打进 APK + 改 libil2cpp 读游戏自身目录，配合禁止删表补丁 0x1ab3588，见 base/so/contents_redirect.elsp）。

## 2026-10-18 22:30 当前进行
- 川味：c_message/c_guide 对白/部分 c_quest 已川味化并回写（22:10）。c_quest 大部分 NPC 完成语（分组不规则）尚未处理。
- 免 Root 投放已写好（见 CHANGELOG 22:30），待 CI 通过与真机验证：装新版后看物品名/地图是否中文，账号存档是否还在。
- 地图 content_zh/map_large_en/__data 已是中文（1237×1062，md5 2c729c7b…），直接随包投放。

- 22:25 真机闪退已定位为 libil2cpp 跳板在只读段，已改（见 CHANGELOG）。若仍闪退，需 logcat（adb logcat -b crash）。


## 2026-10-18 22:20
- 闪退修复 c0ea96df，Actions #56 成功，待真机验证。
- 用户反馈 v6 地图 P 得不好需重做：标题乱、尼夫之丘/多尔梅坑道/药师森林擦除模糊、卡尔内遗迹旁英文残影、字体太平、希弦妮神殿应为希兹妮神殿。已完成重做（见下）。

## 2026-10-18 22:30 地图重做
- 用户给了英文原图（images_zh/map/map_large_en_orig.png）。`tools/map/remap.py en.png out.png [LXGWWenKai-Bold.ttf] [MaShanZheng-Regular.ttf]`：保留原图光晕，只抹英文笔画（Telea），霞鹜文楷 Bold 20px 加字距重写 56 个地名，标题马善政体「艾尔涅亚王国」。地名按 c_dungeon/表内用法（盖纳森林、魔兽森林、深林、山地兵团村、亚诺市场、拉达小屋…）。
- 输出 images_zh/map/map_large_zh.png，ASTC 5x5（format 49，astc-encoder THOROUGH，上下翻转后编码）写回 content_zh/map_large_en/__data，UnityPy 回读误差 1.9。
- 待真机看效果。

## 2026-10-18 23:05 真机反馈（闪退已好，能进游戏）
- 能力页 Speed/Strength/Spirit 仍英文：SystemText_en 第 74-76 行键 Agility/Strength/Spirit 在 DATA_KEYS 里被跳过。已加 elnea_text.DISPLAY_OVERRIDE，并补译 s145 速度 / s147 力量 / s149 心灵。
- NPC/配偶名仍英文（Conan、Dexter、Kyle Antigua…）：名字表已译，但已有居民的名字存在存档里，只影响新生成角色。待分析存档格式后做“存档名字转换”（要先备份）。
- 教程图（"If you tap on their Name Plate…"、Talk、FastTravel）是运行时下载的 bundle（UnityCache/Shared/tutorial*），不在 APK；需用户从 Android/data/jp.co.althi.elnea.en/files/UnityCache/Shared/ 提供原 bundle，再像地图一样重 P。
- 教程图名 fig_XXX_en（c_guide 引用 107 张），按需下载到 UnityCache/Shared。喷泉广场手写拉丁文气泡也是下载的场景/特效 bundle（APK 内 258 张贴图都没有）。已请用户截 Shared 全部文件夹名以定位。

## 2026-10-20 01:30 战斗字 + 旧工作区盘点
- CI #61（cc13a84e 交流与支持卡片/游戏内说明）、#62（909e224e 老存档人名转中文）均成功。
- 战斗字：英文版的 Guard Break!/Counter Break!/Counter Damage!/Guard/Miss! 在 `Main_Atlas_jp`（EN 图集没覆盖），之前从没汉化。
  新增 `tools/imgzh/battle_marks.py` → `images_zh/Main_Atlas_jp_patch/*.png`（破防！/破解反击！/反击伤害！/格挡/落空！，保留左侧图标，预乘 alpha）；
  `tools/bmfont_zh.py` 重建 Main_Atlas_jp 时按 `atlas_sprites.json` 坐标贴入（出错只打日志不中断）。
- 旧工作区盘点：`docs/old_workspace_inventory.md`（DSH 只读产出）。要点：旧 metadata 无译文；教程图无中文版；气泡文本已在 translations/TalkData_en.json；旧存档改名有 ID 错位 bug 不可照搬；借壳字 glyph_subst 属缺陷不沿用。
- 仍在跑：下载包（bundles/README.md）、国王（king.md，要求：杨广、男、黄/白皮、游戏约 5 岁、未婚、川味台词）。

## 2026-10-20 01:40 国王调查结论（docs/king_research.md）
- 初始国王名随机取自 NameMale 表，无固定名；改名只能改存档。国王 id 疑在 `sv1_ws_info`@0x28（高置信，未证实），profile flag King=4。
- profiles 每条 25B：off23 性别(2=男)，off12 肤色(0 白/1 黄/2 拉丁/3 棕)，off24 年龄(独立整数)，off1..3 出生日编码（age=5 → `c0 5d 01`，仅对该旧档 t≈5746 有效）。
- 婚姻在 FamilyData.dicPersons 的 Partner(1) + HumanData flag 0x26c/0x26d；familys 解析仍有矛盾 → **暂不改存档**。
- 台词按身份匹配：c_message speaker/listener=Monarch(35) 可做"国王专属川味台词"（DSH 会话 bE 正在列条目）。

## 2026-10-20 02:00 国王川味台词
- 新增 `tools/nrbf.py`（BinaryFormatter 解析器，c_message 4285 条完整解析，枚举存为字符串）。
- c_message 中 speaker=Monarch 的 22 条台词改成更浓的四川话（任何在位国王都会说），listener=Monarch（臣民对国王）保持标准中文。
  同步改了 `translations/contents/c_message.json` 和加密的 `content_zh/Contents/c_message`（解析校验通过）。
- 台词系统按身份匹配，不能按名字绑定"杨广"；改名/年龄/肤色要改存档，婚姻结构未证实前不动。

## 2026-10-20 10:50 下载包贴图（bundle）汉化
- 14 张下载包贴图已汉化（教程图 5 张 + 活动/扭蛋/琥珀横幅 9 张；地图之前已做 = 共 15 张，缓存里带英文的全部做完）。脚本 `tools/imgzh/bundle_tex.py`，成品 `content_zh/bundles/`，注入 `inj/.../Bundles.java` 启动时覆盖 UnityCache（所有已有 hash 目录）。
- 待真机验证：装新版后看新手引导图/扭蛋横幅是否中文；若游戏校验 CRC 而重新下载英文版，logcat 看 `ElneaZH: bundles:`，再考虑 hook 下载 URL。
- 下一步：教程图共 107 张（c_guide 引用），缓存只有 5 张。需要用户在游戏里翻一遍「指南」各页、看看活动页，再把手机 Android/data/jp.co.althi.elnea.en/files/UnityCache/Shared 整个拷到电脑，交给 bundle_tex.py 补框。
