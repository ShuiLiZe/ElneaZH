# AGENTS.md — 接手本仓库的 AI / 人请先读

本项目：艾尔尼亚汉化器（ElneaZH），把 Elnea Kingdom 2.10.7 英文版一键汉化的安卓 App。仓库私有，自用。

## 开工前
1. 读 `HANDOFF.md`（只写现状：能用的版本、已知问题、下三步）。历史过程在 `docs/CHANGELOG.md`，需要时再查。
2. 看 GitHub Issues：每个未完成的事一个 issue，真机截图贴在 issue 里。
3. 环境：`pip install -r requirements.txt`（版本已锁，别随手升级：etcpak 升级曾导致 ETC2 红变蓝）。

## 规矩
- **每完成一步就推送，再汇报**。不要把成果只留在自己的沙盒里。推送方式见下。
- 推送后看 Actions 结果（build.yml 会发布 Release `latest` 的 ElneaZH.apk），失败要修到绿。
- 只改了文档/数据、不需要构建时，提交信息加 `[skip ci]`。
- 提交信息写清楚改了什么、为什么；解决了 issue 就写 `fixes #编号`。
- 有进展就更新 `HANDOFF.md` 的现状部分；过程追加到 `docs/CHANGELOG.md`。
- 不要改：`base/`（原版文件与哈希）、`app/keystore/`（换签名会导致已装用户无法覆盖安装）。
- 文本规则见 `translations/TRANSLATION_GUIDE.md`；术语表 `translations/glossary.json`。

## 仓库地图
- `translations/*.json`：游戏资源文本译文（权威）；`translations/metadata_literals.json`：代码里写死的字符串译文。
- `images_zh/`：重画的中文贴图；`fonts/zh.ttf`：Noto Sans SC。
- `tools/build_patch.py`：译文+字体+贴图+位图字库 → 汉化版 data.unity3d。
- `tools/bmfont_zh.py`：重做 NGUI 位图字库（Main_Atlas_jp）。
- `tools/metadata_zh.py`：改 IL2CPP global-metadata.dat 字符串（含取名白名单）。
- `tools/verify_textures.py`：检查重编码贴图的格式/mip/误差。
- `tools/ci_verify.py`：CI 里自动验证构建产物并输出预览图。
- `base/data.unity3d`、`base/apk/*.part*`（原版 APK 分段，`cat` 合并后核对 `base/SHA256SUMS`）。
- `app/`：Kotlin/Java 安卓 App；`.github/workflows/build.yml`：CI。

## 推送方式（沙盒直连 GitHub 受限时）
经中转 `https://gh-relay.aap20020709.workers.dev`（等同 api.github.com，带 `Authorization: Bearer <token>` 和 `User-Agent`）。
单文件用 Contents API PUT；多文件一次提交用 Git Data API（blobs → tree → commit → 更新 refs/heads/main），参考 `tools/ghpush.py`。
uploads.github.com 不经中转，所以大文件不要放 Release 附件，分段进仓库。
