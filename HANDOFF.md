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
- [ ] 翻译（等用户决定：自己翻 or 机翻初稿）
- [ ] 中文贴图（用户重画中）

## 已知事实
- Unity 6000.3.17f1, IL2CPP, NGUI；资源全在 `assets/bin/Data/data.unity3d`（单个 BundleFile）。
- 字体：Font `default_android`（path_id 1512）只有拉丁字形 → 替换 `m_FontData` 为中文 TTF。
- `Main_Atlas_jp` 下半部是 NGUI 日文位图字体；`meal_*`/`call_en` 是 ETC2_RGBA8。
- 重签后与商店版签名不同：必须先卸载原版，**卸载前先在游戏里「サポート/Support → 存档数据」备份**。

## 下一步（接手照做）
1. **上传原版资源给 CI**：把原版 `assets/bin/Data/data.unity3d`（28 MB，从 APK 解压）放到仓库 `base/data.unity3d`（私有仓库，自用）。
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
