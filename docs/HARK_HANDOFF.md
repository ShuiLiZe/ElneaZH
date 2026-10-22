# Hark 交接（2026-10-21 19:58 CST，换 Hark 账号前写）

## 目标
艾尔尼亚王国汉化版：本地存档/界面保持中文；上传前把中文名换成 NPC 英文原名 / 玩家拼音（单向，回包不还原）；单机模式；分享/导出。不做绕过反作弊。

## 分支 / 规矩
- 工作分支 **feat/offline-share**（含 fix/anti-flag 全部改动）。main = 正式版，**合并/发版前必须先问用户**。feature 分支可随便提交推送。
- 测试包：prerelease `test-offline` → https://github.com/ShuiLiZe/ElneaZH/releases/download/test-offline/ElneaZH-test-offline.apk（test-release workflow 生成；build-check 的 artifact 缺汉化补丁，不能当测试包）。
- 改 build.yml 时同步 test-release.yml。
- 用户偏好：中文沟通；做完直接给成品测试包，不要中途汇报；真机只有用户能测，别催；工程活由 Hark 亲自做（DSH 输出质量差，不再用）。
- 抓日志命令（logcat -s 会漏 Unity CRASH 行）：
  ```sh
  logcat -d > /sdcard/Download/elnea_all.txt
  cat /data/data/jp.co.althi.elnea.en/files/elneazh_selfcheck.txt \
      /data/data/jp.co.althi.elnea.en/files/elneazh_native.log >> /sdcard/Download/elnea_all.txt
  ```

## 关键技术事实（真机证实）
- 设备：OnePlus PJD110，Android 16，arm64，PAC 开；游戏 2.10.7（base APK sha256 5117bbaa…，仓库 base/apk 分片）；Unity 6000.3.17f1，global-metadata **v39**（Il2CppDumper 不支持）。
- 所有上传唯一加密出口：`Cipher::EncryptRJ256(string)`，libil2cpp VA +0x18ad7e4（文件偏移 = VA − 0x4000），入参 = 明文 JSON。真实序言 `fe0f1df8 f65701a9 f44f02a9 f50f0190 b4fc0090 …`（旧代码里的 40B 表从 0x10 起是 DSH 编的）。
- 只有 save2.php 带中文：`savedata` 里 `ws_profiles / ws_familys / ws_animals0` 是 base64(zlib(二进制))，名字槽 = `0x01 + 1字节 UTF-8 长度 + UTF-8`。外层 JSON 是纯 ASCII。
- 游戏**没有原生离线路径**：点「进入游戏」→ TitlePanel::accountCheck → 无账号则 openAccountPanel（建号），有账号则发 login.php。单机把这些全拦 → 卡标题。
- native 只能在 il2cpp 初始化完后调 class API（判据 il2cpp_get_corlib() 非空 + 2s）；否则后台线程 class_from_name 空指针崩。
- MethodInfo 槽替换拦不住 IL2CPP 直接 bl 调用；inline hook 对短函数/邻近桩/分支回跳不安全，自动退回槽替换。
- 崩溃保护 NativeGuard：连崩 2 次自动禁 native；只认 60s 存活计时器（不再按 activity resumed 清标记）。

## 今天的提交（feat/offline-share）
- 62799e0：enc 兜底闸不再往代码页写指针（首启 SEGV_ACCERR）；inline 安全检查；native 持久日志 files/elneazh_native.log；QQ 群行链接样式。
- 7932ad6：修无限闪退（eg_runtime_ready）；崩溃保护真正生效；**防封三大 bug**——eg_filter_json「明文没汉字就跳过」导致从没换过名字、eg_filter_raw 把 map 返回值判反、玩家名无拼音兜底（新增 nativeSetChars 单字表）。本地用真机 save2 ws_profiles 复现：修前 0 个名字被换，修后 118/118。CI 全绿。
- 360d07b：名字闸门改用 il2cpp API 按名字解析 Cipher::EncryptRJ256，只校验前 12 字节；单机兜底闸只看名字闸门是否挂上。（已推，尚未看 CI、未发用户）

## 真机最新状态（19:43，7932ad6）
- 不闪退了；单机 18 个 hook 装上（含 HttpSystem 5 个、TitlePanel::openAccountPanel/AccountInputButtonClick/requestErrorAlert）。
- 名字闸门没挂上（序言表错误 → 360d07b 已修）。
- 单机开时点「进入游戏」没反应（原因见上）。

## 待用户决定（19:55 已问，未答）
- **A**：单机只放行登录/版本检查（login/check/version/read/create/token/load），拦所有上传（save2/savekpi/kpi/pvp/mail/gift/rename/recov2）。缺点：被标记身份登录仍卡维护，需换身份 + 「只恢复存档（保留当前身份）」。
  实现：去掉 TitlePanel 族 hook；HttpSystem 族按 URL 参数只拦写接口；EncryptRJ256 单机不再一律返回 NULL（兜底：明文含 `"savedata"` 才拦）。
- **B**：联网正常登录时缓存一份真实 login 回包，单机时本地重放，完全不连服务器（被封的档也能玩）。工作量大，需多轮真机测。

## 其它待办
- docs/UPLOAD_AUDIT.md 是 DSH 臆造的（说 kpi.php 是存档上传、acc 解不开），需按真机抓包重写。
- native/elneagate/gate_test.c 自身段错误，CI 没跑它；修好接进 CI。
- IdentityKeys 宽匹配 "uid" 可能误伤 guide 类键（待评估）。
- Java NameFix（ICU 拼音）与 native 单字表（GB2312 表）多音字可能不一致。
- 用户真机通过后再问是否合并 main 发 v1.0.87。

## 本地检查方法（Hark 沙盒 aarch64）
- C 编译检查：`python3 -m ziglang cc -target aarch64-linux-gnu -D_GNU_SOURCE -Istub -Izlib -Inative/elneagate -c x.c`（stub 放 jni.h + android/log.h）。
- 跑测试：`-target aarch64-linux-musl -mcpu=generic -fno-sanitize=all`（默认 cpu 会 Illegal instruction）。
