# 历史记录（原 HANDOFF.md 全文，按时间追加；现状看根目录 HANDOFF.md）

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
1. ~~上传原版资源~~ 已完成：`base/data.unity3d`（原版，补丁基准）、`base/images_en/`（待重画的英文贴图）、`base/SHA256SUMS`（含原版 APK 哈希，用于校验）。整包 APK 已分 4 段放在 `base/apk/`（见其中 README，`cat` 合并后校验 SHA-256）。
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
- 方案 B（全用 TTF）：NGUIFont 有动态字体字段（mBMFont/mAtlas 之后的 mDynamicFont PPtr<Font> + mDynamicFontSize + mDynamicFontStyle）。把 BitmapFont-Area / BitmapFont-Name 的 mDynamicFont 指向 Font default_android（path_id 1512）、size 24，所有引用这两个字体的标签就改用 TTF，字形和大小统一。bmFont 字形表照旧保留，防止取名校验只查字形表。资源无 typetree，需按 bmfont_zh.py 的 raw 布局继续往后解析 suffix 定位字段。先测 #41：位图字已用同一 TTF 按原汉字格子渲染，可能已无大小不一。

### 2026-10-18 20:30 真机反馈：很多没汉化 + 名字不能取中文
- 已排查 data.unity3d：630 个 TextAsset 里 *_en 都已译；prefab（MonoBehaviour）里几乎没有可见英文 UILabel 文本（只有方法名）。所以剩下的英文多半写死在 IL2CPP 代码里（APK 的 assets/bin/Data/Managed/Metadata/global-metadata.dat 字符串字面量），取名校验也在代码里（libil2cpp.so）。
- 需要原版 APK：用 Il2CppDumper 导出，找取名校验函数和硬编码字符串；汉化 global-metadata.dat 的字符串（同样做 bsdiff 进补丁，App 端要扩展成多文件补丁）。

### 原版 APK 已进仓库（2026-10-18 20:35）
- `base/apk/ElneaKingdom_2.10.7.apk.part00..03`，合并：`cat base/apk/*.part* > ElneaKingdom_2.10.7.apk`，SHA-256 5117bbaa…cf90c（与 base/SHA256SUMS 一致）。
- 下一步：Il2CppDumper（lib/arm64-v8a/libil2cpp.so + global-metadata.dat）找取名校验和硬编码英文。

### 2026-10-18 20:45 程序文本（IL2CPP metadata）汉化 + 取中文名
- 游戏是 IL2CPP，metadata 版本 v39（Unity 6000.3）。写死在代码里的字符串都在 `global-metadata.dat` 的 string literal 表（16,145 条）。v39 结构：头 = magic、version、31 组 (offset,size,count)；第 0 段是每条 literal 的 uint32 dataIndex，长度 = 下一条 index 之差；第 1 段是 UTF-8 数据。
- 新工具 `tools/metadata_zh.py`：按 `translations/metadata_literals.json`（英文原文 → 中文）改 literal，重排数据段并平移后续段偏移，自带回读校验。`--dump x.json` 可导出全部 literal。
- 取名：literal `abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ' 0123456789-!:` 判断为英文版取名白名单（代码里有 CheckCharacterName / checkCharacterNameFont / NameInputErrorInvalidCharacter），工具自动在后面追加 GB2312 全部汉字。字形检查（checkCharacterNameFont）靠 bmfont_zh 给 BitmapFont-Name 补汉字。**未经反汇编确认**，真机若仍报无效字符，下一步用 Il2CppDumper（libil2cpp.so + metadata）看 CheckCharacterName 的实现。日文版白名单是片假名那条 literal，英文版不用。
- CI：合并 base/apk 分段 → 取出原 metadata → metadata_zh.py → bsdiff 生成 `app/src/main/assets/elnea_meta.patch`，哈希写进 elnea_zh.json（meta_new_sha256）。
- App：Pipeline 新增可选 metaPatch，ApkRebuilder.rebuild 支持一次替换多个条目（Map），MainViewModel 有 elnea_meta.patch 就一起打。**这些 Java/Kotlin 改动只靠 CI 编译验证**。
- 候选英文：`translations/metadata_candidates.txt`（启发式筛出约 1,400 条，噪音多）。需要用户发未汉化界面截图，对照原文填进 metadata_literals.json。框架字符串（语言名、日期格式、异常信息）别翻。

### 2026-10-18 21:10 真机截图分析（docs/shots/20261018_1..5.jpg）
1. 地图全英文：是贴图 `map_large_en`，不在 APK 里，是运行时从 CDN 下载的 AssetBundle（`http://cdn-elnea.althi.co.jp/sns04/AssetBundle/` + `*.android.unity3d`）。
2. 骑士锦标赛人名英文：人名表 NameMale/Female/Family_en 已译，但现有 NPC 的名字是开局时生成、存在存档里的；新出生/新来的人才会是中文名。老存档要改只能改存档数据。
3. 食谱材料名（Gheat Flour、Milk…）、5. 装备名（Black Wellington Glasses）：APK 和 metadata 里都没有这些字符串。代码里有 master_item / master_food / master_costume / master_accessory / master_guide 等，判断为**服务器下发的主数据**（`http://app-elnea.althi.co.jp/sns04/`），缓存在手机 Android/data/jp.co.althi.elnea.en/files。
4. 教程图 "Action Switch" + 说明文字：同上，教程是 master_guide（服务器下发）+ 下载的贴图。
- 结论：剩下的大块英文不在安装包里。方案候选：
  A. **中转代理**（推荐）：把 metadata 里的服务器地址（app-/cdn-/res-elnea.althi.co.jp/sns04/，明文 http）改成自己的 Cloudflare Worker；Worker 转发到官方服务器，对主数据响应做译文替换、对 map_large_en 等 bundle 返回汉化版。全部在 Worker + 仓库里维护，App 不用碰 Android/data。风险：官方接口格式/签名校验未知，要先抓一份真实响应看格式。
  B. 改手机上的缓存文件：Android 11+ 普通 App 无法写 Android/data，且会被重新下载覆盖，不推荐。
- 需要：主数据和 bundle 的真实样本（从手机 Android/data/jp.co.althi.elnea.en/files 导出，或让 Worker 先做透明转发并记录响应）。

### 2026-10-18 21:15 修复：已装汉化版时手动选原版 APK 仍被拦
- MainViewModel.start()：拦截条件加 `!g.fromFile`，手动选的文件直接生成（输出校验会挡住选错的文件）。

### 2026-10-18 21:20 用户提供的旧汉化方法（不用服务器）
- 地图：`Android/data/jp.co.althi.elnea.en/files/UnityCache/Shared/map_large_en.android/<hash>/__data`（单张贴图 bundle，812,786→818,205 字节）。
- 物品等数据表：`files/Contents/c_item`（物品名+说明，主要）、`c_weapon`、`c_trap`、`c_guidebook`、`c_item_fusion`（少量）。
- `libil2cpp.so` 两处补丁：① 读表路径改到 `/sdcard/ElneaAssistant/Contents/`；② “版本变化就删表重下”的判断直接 return false。否则下次启动表被删回英文。
- 字体图集补字（1,122 字）是共用改动，我们的新字库已覆盖。
- 待办：拿到原始 c_* 表与 map __data 样本 → 写 tools 解析/回写；在 libil2cpp.so 找到这两处并做成补丁；汉化器负责把译好的表写到外部目录（需存储权限）。

### 2026-10-18 21:40 拿到用户旧汉化 v6 的服务器数据（content_zh/）
- `content_zh/Contents/c_*`：22 张主数据表（用户旧汉化版，加密原样）。`content_zh/map_large_en/__data|__info`：汉化地图 bundle（UnityCache/Shared/map_large_en.android/9e41473f5b511831105ea0f1695a843f/）。
- **解密已打通**：`tools/contents_codec.py`。Rijndael-256（32 字节块）ECB，key=`7+22#bbtrm898j8z5q14lkirwf89q=45`（metadata 字面量），内容是 .NET BinaryFormatter `List<MasterXxx>`；字符串记录 `0x06 <id> <7bit长度> <utf8>`，按 id 替换后重新加密，22 张表 round-trip 全部一致。
- `translations/contents/c_*.json`：导出的中文串（record id → 文本），共 36,015 条。
- **重要**：旧汉化用了“借字”——字库不全时把缺的简体字画在别的日文汉字码位上（例：`煥`=种、`曹硲`=收获），表里共有 569 个非 GB2312 的借用字。我们的新字库是真 GB2312，必须先还原映射表才能用。需要用户旧版 APK（里面的字库图集 + libil2cpp.so 两处补丁）来还原。
- 待办：① 拿到旧 APK → 从旧字库对比得出借字映射 → 批量还原为正常简体并校对译文；② 在 libil2cpp.so 实现读表路径重定向 + 禁止删表；③ 汉化器部署表和地图。

### 2026-10-18 21:55 分析用户旧「王国助手 0.0.7」(gitee water-conservancy-ze/ElneaAssistant)
- 是 Root + Xposed + bind mount 方案：把 22 张表写进 /data/data/.../files/Contents 或挂载 /sdcard/ElneaAssistant/Contents，地图写 UnityCache/Shared。
- `base/so/contents_redirect.elsp`（原 assets/so_patch.bin）：libil2cpp.so 补丁，格式 `ELSP` v1, n, 每条 (u32 offset, u32 len, orig, u32 len, new)。5 条，与我们 2.10.7 的 libil2cpp.so 逐字节匹配：
  - 0x8f0b18 写入路径串 `/sdcard/ElneaAssistant/Contents/`（32 字节空洞）；0x8f0b48–0x8f0b57 一段 stub（adrp/add x0 指向该串 + bl + ret）
  - 0x18aa994 `sub sp` → `bl stub`（读表路径改为该串）
  - 0x1ab3588 → `mov w0, wzr; ret`（禁止版本变化删表）
- 非 Root 方案要点：游戏读 /sdcard 需要“所有文件访问”或旧版存储权限；manifest 已有 READ/WRITE_EXTERNAL_STORAGE，无 requestLegacyExternalStorage。可能需要在 AndroidManifest 加 MANAGE_EXTERNAL_STORAGE 并让用户在设置里授予。
- 借字字库不在助手里，应在用户的 base.zh.apk（/sdcard/ElneaAssistant/base.zh.apk）里。

### 2026-10-18 21:50 借字还原
- 用户提供 `tools/borrow_map_flat.json`（1220 条，真字→借字；键值不相交可安全反查）。
- `tools/unborrow.py`：还原 `translations/contents/*.json` 与加密表 `content_zh/Contents/c_*`（约 3.3 万条字符串改动，回读校验通过）。
- `侘鱼` 映射表缺，按描述判为「岩鱼」（unborrow.py EXTRA）、`焗`（正常字）。

### 2026-10-18 22:30 免 Root 主数据表/地图投放（未真机验证）
- 思路：游戏自己有权写自己的目录。往游戏包里注入一个 ContentProvider（`inj/com/shuilize/elneazh/inj/ZhInit.java`，CI 用 javac+d8 编成 classes3.dex），游戏启动前把 `assets/elneazh/Contents/c_*` 拷到 `files/zh/`，把地图 `__data/__info` 拷到内外两处 `UnityCache/Shared/map_large_en.android/9e41…/`；首次把 `files/Contents` 里的其它文件（ws_account 等）复制过去保住账号。
- `tools/so_patch_zh.py`：沿用旧助手 5 处 libil2cpp 补丁，路径改为 `/data/data/jp.co.althi.elnea.en/files/zh/`（43 字节，空洞 48 字节）。补丁在手机端按原字节校验后应用（Pipeline.applyElsp）。
- `tools/manifest_zh.py`（pyaxml）：给 AndroidManifest 加 provider；CI 用 aapt2 dump 检查。手机端先核对原清单 sha256。
- `ApkRebuilder.rebuild` 支持追加新条目；`Pipeline.setZhDir` + `addZh`；`MainViewModel` 解出 assets/elneazh。
- 风险：多用户（/data/data 仅 user 0）；游戏若重下地图会覆盖缓存（下次启动再拷回）。

### 2026-10-18 22:10 主数据表台词川味化
- c_message 全部、c_guide 对白、c_quest 部分完成语共约 8,600 条改为四川味普通话（界面/物品名不动，典礼/议会/公告保持庄重）。已回写加密表并逐条回读校验；变量/格式符/换行数量不变。
- 顺手修：艾娜之子「中奖名单」→「中选名单」；c_guide 12566/12674 颜色标签补全。
- 免 Root 投放版 CI #54 通过（f79a74fb）。

### 2026-10-18 22:25 修闪退（用户反馈：打开即闪退）
- 原因：沿用的旧助手补丁把跳板放在 libil2cpp 文件偏移 0x8f0b48，那里属于只读段（LOAD#0, R），且分支按文件偏移算、代码段 VA=偏移+0x4000 → 一启动就崩。
- 修法：不用跳板，直接把 0x18aa994 函数开头改为 `adrp x0,0x8f0000; add x0,x0,#0xb18; b 0x172cedc`（全按 VA 计算，capstone 验证）。

## 2026-10-20 夜间
- cc13a84e：汉化器 App「完全免费 · 交流与支持」卡片（QQ 群 790184852、微信收款码）；游戏首次启动弹一次说明。CI #61。
- 909e224e：老存档人名自动转中文（inj/SaveNames.java，原件备份 files/zh_backup/）。CI #62。
- 58fb495a：战斗字（Main_Atlas_jp 的 Guard Break / Counter Break / Counter Damage / Guard / Miss）汉化。CI #63。
- be7ba74c：国王/存档结构调查 docs/king_research.md；旧工作区盘点 docs/old_workspace_inventory.md。CI #64。
- 667d29c3：国王（Monarch）22 条台词改川味；tools/nrbf.py。CI #65。
- 下载包：URL 含运行时版本串，静态无法拼出；改为用已缓存的 bundle 汉化后覆盖 UnityCache（见下）。

### 2026-10-20 10:50 下载包贴图汉化 + 注入覆盖 UnityCache
- 盘点（DSH，_hark/bundles/README §11）：旧工作区缓存 239 个唯一 bundle（Shared 196 + _delta 43），452 张 Texture2D；带英文字的只有 15 张（都叫 *_en），其余是场景/道具/特效图。地图 map_large_en 之前已汉化。
- 新增 `tools/imgzh/bundle_tex.py`：其余 14 张逐框抹字（中值背景差分 → OpenCV Telea，含 alpha 通道）后用思源黑/宋重写，写回 bundle（统一 RGBA32，LZ4 重新打包，UnityPy 回读误差 0）。
  - 教程图 fig_000/001/009/010/062：HUD 按钮（限时活动/任务/支援/公告/礼物箱/转移魔法/好友/指南/自动移动，与 Main_Atlas 用词一致）、自动移动地名（同地图）、摘花/爬上/动作按钮、人名塞缪尔/博杜、操作说明。
  - 横幅：淘金热！、琥珀兑换所（服装和装备 / 道具和染料）、扭蛋广告 6 张（点这里查看详情！、成人/儿童/伊姆和婴儿服装、装备、十连抽保底★★★★以上、零度冰/冰晶、贝戈拉银闪管）。
- 原件 `content_en/bundles/<bundle>~<hash>.data|.info`，成品 `content_zh/bundles/`，预览 `images_zh/bundles/*_zh.png`。
- 注入 `inj/.../Bundles.java`（ZhInit 启动时调用，独立 try/catch）：按 assets/elneazh/bundles/index.tsv 写 UnityCache/Shared/<bundle>/<hash>/__data(+__info)，外部和内部 files 目录都写；同名 bundle 已有的其他 hash 目录也覆盖（运行时版本串决定 hash）；大小相同即跳过。
- CI：build.yml 把 content_zh/bundles 拷到 assets/elneazh/bundles 并生成 index.tsv；Pipeline 把 bundles/ 打进游戏包。
- 已知：大字抹除处在人物图上有涂抹痕迹；缓存里没有 fig_002…fig_107（教程图共 107 张）和其他活动横幅，要用户在手机上把对应教程看一遍后再拷 UnityCache/Shared 才能继续做。



## 2026-10-20 11:50 「支援」弹窗接入交流群与支持
- 调查：TitlePanel.ContactButtonClick（VA 0x1c43d74）= playSE + Root.AddWebPanel(URLType.Contact=5)，即 gree unity-webview 的游戏内网页，不经过 startActivity，Java 注入无法拦截。
- 方案：改 libil2cpp 两处函数体（TitlePanel 0x1c43d74、InfoMenuPanel 0x1c05ae4）为 `il2cpp_string_new("elneazh://support")` → `Application.OpenURL`（VA 0x333c0e8）；Unity 6000.3 的 OpenURL 发 ACTION_VIEW intent，由新加的透明 `SupportActivity` 接住，弹 `Support.java` 对话框。AccountSettingPanel（忘记密码）的联系不动。
- 「联系原作者」改为 mailto 原作者支持邮箱（原网页表单无法从 Java 调起；邮箱取自 metadata 字面量 elnea_support_en@althi.co.jp）。
- s367 联系我们 → 交流群与支持；About 首次弹窗复用 Support 对话框，KEY shown_v2。CI 增加 aapt2 检查 SupportActivity。


## 2026-10-20 12:45 新开档初始国王「杨广」
- 反汇编（文件偏移 = RVA − 0x4000，旧 king.md 用错偏移导致“符号错配”结论作废）：初始国王由 dailyPhaseSupplementKing(0x1952b24) → KingData.CreateNewKing(0x194249c) → GameSystem.CreateAdult(0x1a756d4) 新建，age=AgeAdult=6，外观随机，setupNewKing 无婚配。
- so_patch_zh.py：0x1942590 bl CreateAdult → bl king_stub(0x1c43db0)；king_stub 判断 [[KingData+0x18]+0x18]<0（首代）时用 il2cpp_string_new("杨广")、Male、再 HumanData.ChangeSkinColor(Asia)；否则尾调用原 CreateAdult。stub 放在 TitlePanel.ContactButtonClick 被支援补丁替换后剩下的死代码里（已扫全 so 无外部分支跳入）。
- 需求「约 5 岁」按游戏最小成年年龄 6 实现（5 岁为儿童）。DSH 复核会话 session-fb9f4490（Elnea-Localization 分组，11:55 开始）。


## 2026-10-20 13:00 玩家交友上限 888（libil2cpp 补丁，待真机验证）
- DSH 会话 session-a5a3246b 调查（旧工作区 _hark\friends.md），Hark 复核反汇编：上限在 HumanData.get_limitFriendCount（VA 0x19a485c），玩家分支 = Range(52, 102, 52 + 好友栏扩充道具 4021 数量)，NPC 固定 32；纯本地常量，调用链里没有 Premium/服务器。
- tools/so_patch_zh.py：VA 0x19a48d4 `mov w0,#52`→`#888`、0x19a48d8 `mov w1,#102`→`#888`，玩家上限恒 888；NPC 32 不动。好友表是 addFriend 动态扩容的 List，无固定缓冲。
- 注意：DSH 报告里把 VA 当成文件偏移（应 −0x4000），且建议整函数改 ret，未采用。好友栏扩充道具此后无意义。
- 测：装新版 → 人物「朋友」页上限显示 888，交友超过 52/102 不再挤掉旧朋友。
