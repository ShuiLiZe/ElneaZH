# HANDOFF / 交付说明

> 这是给"接手的人或下一次会话"的交付文件：项目现在做到哪、怎么继续。每次有进展都会更新。

## 目标
艾尔尼亚汉化器（ElneaZH）：一个安卓 App，一键把手机上已安装的 **Elnea Kingdom 2.10.7** 打成中文版并重新安装。
自用，不分发游戏本体。

## 架构
1. **汉化资源**（本仓库）
   - `sheet/ElneaKingdom_汉化对照表.xlsx`：全部 11,093 条显示文本（日文参考 / 英文原文 / 中文译文）。
   - `translations/<资源名>.json`：中文译文的权威存档（`{uid: "中文"}`），由 `tools/import_xlsx.py` 从表格生成，便于 diff。
   - `images_zh/`：用户原位原尺寸重画的中文贴图（文件名同 `*_en.png`）。
   - `fonts/`：中文字体（开源许可），构建时按用到的字裁剪。
2. **补丁构建**（`tools/build_patch.py`，CI 运行）
   原版 `data.unity3d` + 译文 + 字体 + 图片 → 汉化版 `data.unity3d` → 与原版做二进制差分 `elnea_zh.patch`。
3. **一键汉化 App**（`app/`，Kotlin）
   读取已安装游戏的 APK → 校验原版哈希 → 打补丁替换 `data.unity3d` → 对齐 + 用内置密钥 v1/v2/v3 签名（apksig）→ 引导卸载原版（先备份存档）→ 安装汉化版。
4. **GitHub Actions**：推送即构建补丁和 App，产物在 Actions / Releases。

## 文本规则（tools/elnea_text.py）
- 每条文本用 `文件|uid` 定位：类 JSON 用第 N 个字符串字面量 `sN`；ZoneHelp 用 `L行.F字段`；人名/Tips 用 `L行`。
- 只替换字符串内部内容，键名、数字、scene/bgm、文件格式逐字节不变。
- 必须保留 `$(xxx)$`、`{0}`/`{1:D}`、`\n`。
- 跳过：TalkNew/TalkSpot（纯 ID）、NGWord（屏蔽词）。

## 进度
- [x] 对照表导出（11,093 条）
- [x] 私有仓库 ShuiLiZe/ElneaZH
- [ ] import_xlsx / build_patch（回填 + 换字体 + 贴图）
- [ ] 安卓 App + CI
- [ ] 翻译（等用户决定：自己翻 or 机翻初稿）
- [ ] 中文贴图（用户重画中）

## 已知事实
- Unity 6000.3.17f1, IL2CPP, NGUI；资源全在 `assets/bin/Data/data.unity3d`（单个 BundleFile）。
- 字体：Font `default_android`（path_id 1512）只有拉丁字形 → 替换 `m_FontData` 为中文 TTF。
- `Main_Atlas_jp` 下半部是 NGUI 日文位图字体；`meal_*`/`call_en` 是 ETC2_RGBA8。
- 重签后与商店版签名不同：必须先卸载原版，**卸载前先在游戏里「サポート/Support → 存档数据」备份**。
