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
- [x] `tools/import_xlsx.py`：表格 → translations/*.json
- [x] `tools/build_patch.py`：译文回填已在沙盒实测通过（SystemText 改两条 → 重新打包 LZ4 → UnityPy 读回正确）；换字体、贴图回填代码已写，**未实测**
- [ ] 安卓 App + CI
- [ ] 翻译：由 AI 人工式翻译，台词四川味、界面标准中文，规范见 `translations/TRANSLATION_GUIDE.md`，术语表 `translations/glossary.json`；进行中
- [ ] 中文贴图（用户重画中）

## 已知事实
- Unity 6000.3.17f1, IL2CPP, NGUI；资源全在 `assets/bin/Data/data.unity3d`（单个 BundleFile）。
- 字体：Font `default_android`（path_id 1512）只有拉丁字形 → 替换 `m_FontData` 为中文 TTF。
- `Main_Atlas_jp` 下半部是 NGUI 日文位图字体；`meal_*`/`call_en` 是 ETC2_RGBA8。
- 重签后与商店版签名不同：必须先卸载原版，**卸载前先在游戏里「サポート/Support → 存档数据」备份**。

## 下一步（接手照做）
1. ~~上传原版资源~~ 已完成：`base/data.unity3d`（原版，补丁基准）、`base/images_en/`（待重画的英文贴图）、`base/SHA256SUMS`（含原版 APK 哈希，用于校验）。整包 APK（98.6 MB）不进 git，接手时从商店/原文件重新获取并对照哈希即可。
2. **选字体**：开源中文字体放 `fonts/zh.ttf`（建议思源黑体 SC / Noto Sans SC Medium，OFL），build_patch 会按用到的字裁剪。
3. **测字体与贴图**：`python tools/build_patch.py base/data.unity3d out/data.unity3d --font fonts/zh.ttf --images images_zh`，手动塞回 APK 装机看效果（字号是否统一）。若 NGUI 标签仍用 `Main_Atlas_jp` 的位图字体，需要另做位图字体（BMFont 生成新图集 + 改 UIFont 的 MonoBehaviour 字形表）。
4. **生成补丁**：`bsdiff base/data.unity3d out/data.unity3d app/src/main/assets/elnea_zh.patch`（Java 端用 `io.sigpipe:jbsdiff` 应用），同时写入原版 SHA-256 做校验。
5. **安卓 App `app/`**（Kotlin，minSdk 24，包名 `com.shuilize.elneazh`，App 名「艾尔尼亚汉化器」）：
   - UI 风格照 Hark 网页：暖白底、大圆角卡片+柔和阴影、近黑色文字、黑色胶囊主按钮、大量留白。
   - 流程：找到已装游戏（PackageManager 查包名，读 `applicationInfo.sourceDir`；也支持手动选 APK）→ 校验 data.unity3d 哈希 → 应用补丁 → 复制其余条目（去掉 META-INF 旧签名，data.unity3d 用 STORED）→ 4 字节对齐（.so 16K 对齐）→ `com.android.tools.build:apksig` 用内置 keystore 做 v1+v2+v3 签名 → 提示先备份存档并卸载原版 → FileProvider 调起安装。
   - 需要权限：`QUERY_ALL_PACKAGES`（或 `<queries>` 指定游戏包名）、`REQUEST_INSTALL_PACKAGES`。
6. **CI `.github/workflows/build.yml`**：ubuntu + Python（UnityPy、fonttools、bsdiff4）跑 import → build_patch → bsdiff，然后 JDK17 + Gradle `assembleRelease`，签名 keystore 放 Secrets，产物上传 Actions artifact 和 Release。
7. **翻译**：等用户回复是自己翻还是机翻初稿；机翻时按文件分批，每批写进 translations/*.json 并立刻提交。

## 环境备忘
- 沙盒是 aarch64；JDK 要下 aarch64 版。推送用 Git Data API 经 gh-relay（`work/ghpush.py`）。

## 当前状态（2026-10-18 19:35 CST）
### 翻译：全部完成
- `translations/` 下 110 个 *_en 资源全部有中文（11,093 条），`tools/check_tr.py` 全部通过；`build_patch.py` 全量实测：11,093 条回填 + 字体替换成功。
- 术语表 `translations/glossary.json`（545 条，依日文汉字定名：魔铳师会、守护龙巴格威尔、货币“贝”、时间“朝1刻”等）。
- 风格：日常台词四川味，典礼/葬礼/议会正式；UI 标准中文。
- 待润色：英文原文有几处与日文不符，已按日文翻（交接说明见各提交信息）。

### App：已建好，CI 绿
- `app/`（Kotlin + Compose，包名 com.shuilize.elneazh，名称 艾尔尼亚汉化器），游戏包名 `jp.co.althi.elnea.en`。
- 流程：找到已装游戏/选 APK → 校验 data.unity3d SHA-256 → 应用 bsdiff 补丁（自写流式 patcher）→ 重建 APK → apksig v1/v2/v3 签名（`app/keystore/elneazh.p12`，密码 elneazh）→ 提示备份存档、卸载原版、安装。
- CI：`.github/workflows/build.yml`，每次推送生成补丁、构建 App，发布到 Release `latest`（ElneaZH.apk）。
- 字体：`fonts/zh.ttf` = Noto Sans SC Medium（OFL），构建时按用到的字裁剪。

### 未验证 / 下一步
1. 真机测试：App 能否识别游戏、安装后游戏能否启动、中文是否显示（NGUI 位图字体 Main_Atlas_jp 可能仍需处理；若出现方块或英文，查 UIFont/BMFont）。
2. 商店版若是分包 APK（split），重签名安装路径未测。
3. 图片汉化：`images_zh/` 还空，等用户提供重画的图（meal/call 需 ETC2_RGBA8）。
4. 补丁约 10 MB（UnityPy 整包重压缩），可接受。

### 2026-10-18 19:55 真机反馈（重要）
- 真机已能显示中文，但简体特有字（应、费、游、确…）全部消失；取中文名提示“含有无效字符”。
- 原因：界面用 NGUI 位图字体（Main_Atlas_jp 下半部分，只有日文 JIS 汉字），缺字直接丢弃，取名也按这张字形表校验。
- 修法（进行中）：解析 UIFont MonoBehaviour 的 glyph 表，保留原假名/拉丁字形，用 fonts/zh.ttf 重新渲染「译文用字 + GB2312 6763 字」，必要时把贴图扩到 2048x4096；集成进 tools/build_patch.py（每次构建自动重生成），CI 开启。
- 图片汉化（进行中）：images_zh/ 下 Main_Atlas_en、Sub1_Atlas_en、now_loading、call、meal_1-6 抹英文重绘中文；Main_Atlas_jp 只归字体任务改。
- 已做：tools/build_patch.py 的 TTF 子集已含 GB2312 全集。

## 当前状态（2026-10-18 20:15 CST）
### 缺字修复：已完成（ac09be8、02116d8，run #38 成功）
- `tools/bmfont_zh.py` 重做 NGUI 位图字体（BitmapFont-Area / BitmapFont-Name）：保留原假名/拉丁字形，用 fonts/zh.ttf 渲染 GB2312 6,763 字 + 译文用字，Main_Atlas_jp 扩到 2048x4096。build_patch 每次构建自动执行。
- 取中文名能否通过校验仍待真机确认。

### 图片：已打包进构建
- `images_zh/` 10 张：Main_Atlas_en、Sub1_Atlas_en（RGBA32）、now_loading_en（RGB24）、call_en、meal_1..6_en（ETC2_RGBA8）。
- 之前以为没推送的 texture_io.py 不需要：仓库里的 build_patch.py 本身就会写回图片（数据改为内嵌，m_StreamData 清空，Unity 可正常读取）。
- **已修 bug**：UnityPy 1.10.18 编 ETC2 前把 RGBA 换成 BGRA，而 etcpak 0.9.15 本来就收 RGBA，结果红色变蓝（call_en、meal_1、meal_6 误差大就是这个原因，不是透明像素）。另外 set_image 把 mip 数从 8/9 降成 1。
  现在 build_patch.py 的 `etc2_rgba_mips()` 自己用 etcpak 编码，保留原 mip 数和原数据大小。实测所有 ETC2 图可见像素平均误差 3–8，正常。
- 还剩英文：Main_Atlas_jp 里的战斗字（Counter Break!、Guard Break!、Counter Damage!、Miss!、Guard）、icon_talk 卡片上的小字。

### 下一步
1. 真机测试：缺字、取中文名、游戏启动、ETC2 图颜色正常（红字不能变蓝）。装前先备份存档。
2. 战斗字和 icon_talk 小字（要动 Main_Atlas_jp，注意它现在由 bmfont_zh 重生成，图片要在字体生成后再贴进同一张图）。

### 2026-10-18 20:25 用户反馈
- 用户反映"不是少字就是一大一小"，问能否不用借壳（重打包重签）方案。结论：问题来自两套字体（NGUI 位图字 Main_Atlas_jp + TTF default_android），与打包方式无关；运行时注入（BepInEx/MelonLoader）同样要重打包且更不稳。
- 待做：bmfont_zh 渲染中文时按原 JIS 汉字字形的格子尺寸、基线、advance 对齐（量原字形平均 width/height/offset 再定字号），等用户发 #41 版的截图确认是哪处一大一小。
