# HANDOFF — 现状（只写当前状态；过程见 docs/CHANGELOG.md，规矩见 AGENTS.md）

_最后更新：2026-10-18 21:00 CST_

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
