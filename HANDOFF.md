# HANDOFF — 现状（只写当前状态；过程见 docs/CHANGELOG.md，规矩见 AGENTS.md）

_最后更新：2026-10-18 21:00 CST_

## 现在能用的版本
- Release `latest` 的 ElneaZH.apk（每次推送自动构建）。装前先在游戏「Support → 存档数据」备份，卸载原版后安装汉化版（签名不同）。
- 已包含：11,093 条资源文本中文；NGUI 位图字库补齐 GB2312 6,763 字；10 张中文贴图（ETC2 红变蓝已修，mip 保留）；global-metadata.dat 补丁（取名白名单加汉字）。

## 已知问题（详情和截图在 Issues）
- 真机仍有不少英文：多半是代码里写死的字符串（global-metadata.dat），候选在 `translations/metadata_candidates.txt`，要按截图逐句填 `translations/metadata_literals.json`。
- 取中文名：白名单和名字字库都已加汉字，待真机确认；不行就 Il2CppDumper 看 CheckCharacterName。
- 字号一大一小：待 #48 之后的版本真机确认；备选方案是把 NGUIFont 切成动态 TTF（见 CHANGELOG）。
- 战斗字（Counter Break! 等，在 Main_Atlas_jp）和 icon_talk 小字仍是英文。

## 下三步
1. 用户真机测最新版，截图贴到对应 issue。
2. 按截图翻 metadata_literals.json（只翻游戏可见文字）。
3. 取名若仍失败：Il2CppDumper 反查校验逻辑。
