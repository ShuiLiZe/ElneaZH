# 历史记录（原 HANDOFF.md 全文，按时间追加；现状看根目录 HANDOFF.md）

> 这是给"接手的人或下一次会话"的交付文件：项目现在做到哪、怎么继续。每次有进展都会更新。

## 2026-10-21 07:10 单机模式的联网/付费入口改成「直接隐藏」（Hark 审查 1faeed8 的修正）

**改了什么**：06:15 版是「入口照常显示 + 原生层置成不可用（变灰）」。Hark 审查指出这不符合
用户最终口径 —— 单机时联网/付费入口要**直接去掉：不显示、不置灰、不弹提示**。这一版全改：

1. **BUTTON 族**（34 个 `*ButtonClick`）：单机时**原回调一行都不跑**，并把入口对象
   `SetActive(false)` 再藏一次；不再调 `set_interactable`（入口已不可见，这层是双保险）。
2. **HIDE 族**（原 FLAG 族，`TARGET_FLAG`→`TARGET_HIDE`，编号仍是 5）：`MenuCell.set_EnableFlag`/
   `set_ActiveFlag`/`set_IsActive`/`set_Visible`/`set_Alpha`/`SetActiveSelf`、
   `InfoMenuPanel.set_isActiveCheckBrokenPanelButton` —— **先跑原方法**（不打断游戏自己的显隐判断），
   紧接着 `get_gameObject().SetActive(false)`，再让所在 Grid/Table `Reposition()`（解析得到才调，
   避免留个空洞）。不再改 `EnableFlag`/`ActiveFlag` 的字段值。
3. **GATE 族**（47 个 `Add*Panel`）：**工厂照跑、绝不返回 NULL**（调用方会立刻用返回值），
   造出来的面板立刻用面板自己的 `CloseAll` → `CloseButtonClick` → `SetActive(false)` 收掉，
   不留一个打不开又关不掉的灰面板。
4. **关单机后恢复**：所有族回原样转发/执行、不再隐藏，下次界面重建时入口自然回来。
   不主动 `SetActive(true)`，避免和游戏自己的显隐逻辑打架。

**新增 native 工具函数**（`native/elneagate/eg_offline.c`）：
`eg_hide_object()`（`Component.get_gameObject()` → `GameObject.SetActive(false)`，退 `set_active`/`set_visible`）、
`eg_close_panel()`（`CloseAll` → `CloseButtonClick` → 隐藏）、`eg_relayout()`（Grid/Table `Reposition`）。
全部走 `eg_invoke`（`il2cpp_object_get_class` 从实例拿类 + 按名解析），解析不到就静默跳过。
**没有任何置灰调用**：`set_interactable` / `set_PanelEnabled` 从代码与名单里删除。
`armv7` 分支的 `eg_dispatch` 标了 `__attribute__((unused))`，编译无噪音。

**先合并了 `fix/anti-flag`**：`git fetch origin 5b1fe59` → merge 进 `feat/offline-share`
（`5b1fe59 ci: 测试分支自动发布非 latest 预发布（test-antiflag / test-offline）`）。
唯一冲突 `.github/workflows/build-check.yml`：两边步骤全留，取 anti-flag 版的
「Publish test prerelease」步骤（`feat/offline-share` → tag `test-offline` 预发布）。

**离线验证**：`verify_offline.py` 165→**172 项**（新增/改成：不出现 `set_interactable`、
不出现 `PanelEnabled`、必须用 `SetActive`、GATE 必须用 `CloseAll`、隐藏后 `Reposition`、
native 有 `EG_TARGET_HIDE` 且不再有 `EG_TARGET_FLAG`）；
`OfflineTest` 198→**200 项**（`TARGET_HIDE=5`、`hideTable` 断言、按钮族不含 `interactable`）；
`verify_gate.py` 的日志字符串改成新的六条；`build_gate.py` 双 ABI 成功
（arm64 strip 后 55,448 B / armeabi-v7a 20,076 B）；CI 同款 `javac -Xlint:all --release 8`
全 inj 源通过（仅已知 `overridePendingTransition` deprecation）+ `d8 --min-api 21`
→ classes.dex **126,368 B**；`manifest_zh.py` 往返 `manifest ok 32056`。

**没变**：HTTP 族不伪造回包、资源下载放行（边玩边下照旧）、分享/导出三功能。

## 2026-10-21 06:15 单机模式改成六族 native 钩子：入口照常显示但置成不可用

**为什么改**：05:55 那版把 `Add*Panel` 一律 `return NULL`。玩家的反馈口径是「提示单机模式不可用」，
不是「入口消失」；更要紧的是调用方常写成 `var p = AddMailPanel(...); p.xxx()`，返回 NULL 就是空引用。
05:55 版 HANDOFF 的「必须先确认」第 1 条也正是这个风险，这次直接消除它。

**六族钩子（Java `OfflineGate.TARGET_*` ≡ C `EG_TARGET_*`）**
1. `PANEL`（12 条 `Open*Panel`）：纯副作用入口，返回值没人用 → 仍返回 NULL，入口不创建。
2. `HTTP`（`HttpSystem.httpConnect`/`connectWWW`/`AddHttpQueue`/`StartHttpQueue`/`UpdateUserInfoPair`）：
   返回 0，一个字节都不发，交给游戏自己的通信失败/离线分支；**不伪造回包**。
3. `GATE`（47 条 `Add*Panel`）：面板照常创建，创建后立刻 `set_PanelEnabled(false)` ——
   对象合法存在，不会空引用，也不留半初始化实例。
4. `BUTTON`（34 条 `*ButtonClick`）：原回调照常跑（游戏自己的显隐判断一概不动），
   再 `set_interactable(false)`，点了没反应。
5. `FLAG`（`MenuCell.set_EnableFlag`/`set_ActiveFlag`、`InfoMenuPanel.set_isActiveCheckBrokenPanelButton`）：
   原逻辑跑完把 EnableFlag 置 0、ActiveFlag 清空。
6. `MASKS`（`AbstractPanel.addMaskCollider*`/`addNowLoadingPanel`）：单机时不加遮罩，
   避免玩家被困在「不可用」提示后面那层黑幕里。

**native 侧改动**（`native/elneagate/eg_offline.c`，重写）
- 新增 `il2cpp_object_get_class`（从实例拿类，静态类型常是 `object`）与 `il2cpp_thread_attach`
  （非游戏线程调托管方法前必须 attach）；
- `eg_invoke(self, method, argc, a0, a1)` 按名字调 setter 的小工具；
  `eg_suppress_panel`/`eg_suppress_button`/`eg_suppress_menu` 三个语义化包装；
- `eg_dispatch` 按 `target_kind` 分派六族；`EG_MAX` 64→96，`eg_hook_0..95` 用宏生成；
- stats 扩成 7 槽 `[已挂, 入口不创建, 请求拦截, 置灰, 遮罩, 解析失败, 回调异常]`；
- 新增导出 `eg_of_reset_stats()` / `eg_of_class_present()`；`eg_of_call` 换成只解析不调用的
  `eg_of_resolvable()`（自检更安全）。

**JNI 导出**（`elneagate.c`）：`nativeResetStats`、`nativeClassPresent` 新增；
`nativeStats` 返回 7 个 int；`nativeResolvable` 改走 `eg_of_resolvable`（不再调托管方法）。

**Java 侧**：`OfflineGate.java` 重写 —— 六族常量与六张表、`table(target)`/`groups()`/`countLines()`、
`installAll()` 逐族独立 try/catch、`installRetry()` 只对 -1（il2cpp 还没可见）重试、`resetStats()`、
`summary()` 一句话现状。`Support` 切开关时 `push` + `resetStats`；`SelfCheck` 报六族条目数与每族挂载结果。
`offlineText(on)` 的措辞从「入口直接不创建」改成「照常显示但被置成不可用」。

**验证**（全绿）：`verify_offline.py` 165 项；`OfflineTest` 198 项（新增六族编号/格式/去重/交叉冲突/
覆盖面/native 未加载安静失败）；`verify_gate.py` PASS（含 7 个 JNI 导出 + 六族日志字符串）；
`build_gate.py` 双 ABI 成功（arm64 strip 后 54,904 B / armv7 19,900 B）；
`javac --release 8 -Xlint:all` 全绿；`d8 --min-api 21` → `classes.dex` 126,992 B；
`manifest_zh.py` 往返 `manifest ok 32056`。

## 2026-10-21 05:55 单机模式补上 native 闸门：il2cpp 按名解析 + inline hook（另合并 fix/anti-flag）

**背景**：`03:40` 那版单机模式只有 Java 侧 `NetBlocker`（`URLStreamHandlerFactory`），
而游戏的 HTTP 走 `UnityWebRequest` + native curl，静态看不经过 `java.net`；UI 更是全在 il2cpp 里，
Java 反射够不到 `MenuCell.EnableFlag` / `AbstractPanel.SetPanelEnabled`，dex 里也没有可用的 JNI 出口
（`AndroidUuidPlugin`/`AndroidTools`/`GetUUID` 全 0 命中）。

**合并**：把 `fix/anti-flag`（上传前中文名转拼音的名字闸门 `native/elneagate` + `NameGate.java` +
`UploadNames.java` + `tools/{build,verify}_gate.py` + `tools/verify_upload_names.py` 等）并进
`feat/offline-share`，9 个冲突文件全部两边保留：`ZhInit`（两个 install 都在）、`SelfCheck`
（2b/2c 名字槽自检 + 3/4 单机段都在）、`Backup`（保留 `uriOf`）、`NameFix`（保留
`NameGate.remember` 与 `pairs()`）、`NetGuard`（取 anti-flag 的新版）、`tools/pinyin_tables.py`
（取 anti-flag 的回退版）、`HANDOFF.md`、`.github/workflows/build-check.yml`（两边步骤都留）。

**新增 native 侧** `native/elneagate/eg_offline.c` + `eg_offline.h`：
- `dlsym` 取 `il2cpp_domain_get` / `il2cpp_domain_get_assemblies` / `il2cpp_assembly_get_image` /
  `il2cpp_class_from_name` / `il2cpp_class_get_method_from_name` / `il2cpp_image_get_name`；
- 遍历所有 assembly 找 `ns::class`（命名空间 `*` = 不限），按 0..6 个参数试解析，
  取 `MethodInfo` 首字段当函数地址；
- `A64HookFunction` 挂 hook，`EG_MAX_HOOKS=64`，`EG_DECL_HOOK(N)` 宏生成 64 个转发函数；
- 回调现读开关（`eg_of_set_state` 灌 `'1'/'0'`）：面板族 `return NULL`（入口不创建）、
  请求族 `return NULL`（不发，走游戏自己的通信失败/离线分支）；关掉立刻回原函数；
- **解析不到就跳过并打 `ElneaOffline` 日志**，绝不崩、绝不空指针调用；
- 没挂上时 `eg_dispatch` 原样转 `orig`，行为与未安装一致。

**新增 Java 侧** `inj/.../OfflineGate.java`：加载/解包 `.so`（自带一份，测试环境不依赖 `NameGate`）、
重试安装（最多等 `waitMs`，不拖住启动）、`push(boolean)` 推开关、`stats()`/`describe()`/`probe()`/
`resolvable()`、两张白名单表（面板 41 条 `Add*Panel`、请求 3 条 `HttpSystem.{httpConnect,connectWWW,AddHttpQueue}`）。

**接线**：`ZhInit` 加 `OfflineGate.install(getContext(), 1)`（独立 try/catch）；
`Support` 开关翻转先 `push` 再改界面、单机时多一行原生闸门现状；
`SelfCheck` 加 `offline native: ...` / `offline native api: n/8` / 两族条目数。

**测试**：
- `tools/verify_offline.py` 88 → **130** 项：新增 E 段（`OfflineGate` 两族表、`ZhInit` 接线带 try/catch、
  native 源码用 dlsym 不用硬编码偏移、日志 TAG `ElneaOffline`、**「不许伪造回包」断言**：
  `eg_offline.c` 里不得出现 `il2cpp_object_new` / 造 JSON、`build_gate.py` 编进了 `eg_offline.c`、
  CI 有 build/verify_gate 步骤）。
- `tools/offline_test/.../OfflineTest.java` 132 → **149** 项：native 未加载时 `push`/`stats`/`probe`/
  `resolvable`/`uninstall`/`install(null,..)` 全部「安静失败不抛异常」，以及两张白名单表的格式/去重/
  覆盖面（23 个必须挡的面板名逐个点名）。为此补了 `android/os/Build`、`android/content/res/AssetFileDescriptor`
  两个 stub，`AssetManager.openFd` 改成抛异常（正好验证「解包失败也不崩」），并加了 `NameGate` stub。
- `tools/verify_gate.py`：新增 `.so` 里必须有 `ElneaOffline` 标签与 `OfflineGate_nativeInstall`/
  `OfflineGate_nativePush` 符号（被 strip 掉就等于没装）。

**本地验证**：`verify_offline` 130/130、`OfflineTest` 149/149、`verify_gate` PASS、
`build_gate.py` 成功（arm64-v8a strip 后 52,000 B / armeabi-v7a 21,432 B）、
CI 同款 `javac -Xlint:all --release 8` + `d8 --min-api 21` → **classes.dex 120,956 B**。

**边界**：不伪造服务器回包、不改设备码、不删服务器标记、不绕反作弊；armv7 无 hook 引擎
（`nativeInstall` 返回 -4，只剩 Java 层兜底）。

## 2026-10-21 05:20 单机模式：资源下载改为放行（用户纠正上一版）

上一版（`baebd16`）把所有资源下载也拦掉了，理由是「拿不到不带身份的地址」。用户指出实际是
**边玩边下载**，必须照常可用。回头重查 global-metadata，上一版的判断是错的：

- 下载走 `AssetBundleManager.downloadAndCache` → `get_Url` → `UnityWebRequest`，
  由 `getEnvironmentURL()` / `getAssetBundleURL()` / `getManifestURL()` 拼地址，**不经过 `HttpSystem`**。
- 把 `AssetBundleManager` 整块字段名数一遍，`user_number` / `crypt_key` / `UserNumberOnServer` /
  `CryptKeyOnServer` / `key_header_info` / `addUniqArg` / `uniq` / `uuid` / `playerId` / `token`
  **全部 0 次**。身份参数只存在于 `HttpSystem`（即 `/sns04/*.php`）那条路。

**改动**：
- `Offline.allowWhileOffline()`：新增 `if (isAssetDownload(url)) return true;`，
  资源下载在单机时**放行**；其余（`/sns04/*.php` 带身份的接口）照旧拦。
- `Offline.isAssetDownload()`：显式排除 `.php`（`ReadResourceInfo.php` 这类资源清单接口带身份，必须拦），
  `manifest` 只认末段（避免误判路径里含 manifest 的接口）。
- `Offline.isAssetHost()` 新增：限定 althi 宿主（`res-` / `cdn-` / 裸域 / 任意子域）。
- `Offline.downloadHint()` 改为「该资源需关闭单机模式下载：已缓存的资源照常可用，关掉单机后即可补下。」
- `Offline.purchaseHint()` 新增：已购/已解锁内容单机时沿用本机上次记录，不向服务器查询。
- `Offline.limits()` / `summary()` 文案改为「只挡带身份的接口，资源照常下载」。
- `NetBlocker`：新增 `ASSET_OK` / `assetAllowed()`；`allow()` 改走 `Offline.allowWhileOffline`；
  放行的下载记 `offline: pass through ASSET download ... (no identity info, allowed)`；
  `assetHits()` 只剩「带身份的资源接口被拦」时才加一。
- `Support.java`：单机时那行提示改成绿色「资源/扩展包：单机时照常下载…」，
  只有真拦到带身份的资源接口才变红棕显示 `NetBlocker.downloadTip()`。
- `SelfCheck.java`：probe 换成真实资源 URL（`res-elnea` / `cdn-elnea`），
  新增 `offline asset downloads allowed:`。

**测试**：`OfflineTest` 99 → **132 项**（资源放行 6 例、带身份接口仍拦 8 例×3 断言、提示语、已购说明）；
`verify_offline.py` 75 → **88 项**（断言 `isAssetDownload(...)→return true`、`.php` 被排除、
放行/拦截统计与日志接线）。
写测试时自己踩了一个坑：断言写成 `ok(!NetBlocker.allow(u, true), "闸门放行")`，
把「放行」写成了「不放行」——测试红出来才发现是断言错，不是代码错，已改成 `ok(NetBlocker.allow(...))`。

本地验证：`verify_offline.py` 88/88、`OfflineTest` 132/132、
`javac --release 8 -Xlint:all` 全绿（66 classes，仅已知 deprecation 警告）、
`d8 --min-api 21` → `classes.dex` 101,844 字节、`manifest_zh.py` 往返 `manifest ok 32056`。

## 2026-10-21 04:10 单机模式：扩展包 / 资源下载的放行规则（feat/offline-share）

用户补充需求：已下载的扩展包单机必须照常可用；纯静态 CDN 下载可放行，带身份信息的一律拦并提示。

**先查了事实，再定规则**（避免凭猜测开口子）：
- `Assets/Script` 782 个 `.cs` 里网络相关的只有 `HttpSystem` / `HttpRequest` / `AssetBundleManager` /
  `Unity5Purchase` / `UnityIAPManager`，全部走同一个 `HttpSystem`——**没有独立的扩展包下载通道**。
- 资源清单与 bundle 基址**由服务器下发**（`HttpSystem.listURL` / `assetBundleURL` / `cacheFlag`，
  `AssetBundleManager.getAssetBundleURL()`），客户端没有硬编码 CDN 基址。
- `/sns04/` 四个入口都要 `key_header_info` + `addUniqArg` 鉴权头（含账号与设备标识）。
- 游戏自身有「离线领取」分支（`OfflineData` / `IsOfflineActive` / `successOfflineHttp` 等），
  说明它本来就预期「离线时沿用上次数据」。

**结论**：client 拿不到「不带身份信息的纯静态下载地址」，所以**不给下载开口子**——
开了只会变成「先发 listURL（带设备码）拿地址、再放行 bundle」的隐性上传通道。
规则集中在 `Offline.allowWhileOffline(String)`：只放行「非游戏服务器 且 非资源下载形状」的地址。

**改动**：
- `Offline.java`：新增 `isAssetDownload()`（`.bundle`/`.unity3d`/`.ab`/`.assetbundle`/`assetbundle`/`manifest`）、
  `downloadClass()`（`DL_ASSET`/`DL_SNSPHP`/`DL_OTHER`）、`allowWhileOffline()`、`downloadHint()`
  （*「单机模式下不能下载资源/扩展包：请关闭单机模式，下载完再开启。已下载的内容可以照常游玩。」*）、
  `host()`/`path()` 抽成独立纯函数；`limits()` 加一条「已下载的资源/扩展包照常可用」；WRITE 表补 `ad.php`。
- `NetBlocker.java`：新增 `ASSET_HITS` 计数、`assetHits()`、`downloadTip()`；`note()` 里区分
  ASSET 下载并单独打日志（`offline: intercepted ASSET download ... (先关单机模式再下载)`）。
- `Support.java`：单机开着时多一行红棕提示「资源/扩展包：已下载的照常可用；要下载新的，请先关闭单机模式。」
  开/关时同步显隐；`offlineText()` 单机时附带 `NetBlocker.downloadTip()`。
- `SelfCheck.java`：probe 输出加 `asset=`，新增 `offline asset downloads blocked:` 与 `offline download hint:`。
- 测试：`OfflineTest` 79 → **99 项**（新增资源下载拦截/识别/分类/提示语/非游戏服务器放行）；
  `verify_offline.py` 65 → **75 项**（新增 allowWhileOffline 无「无条件放行」分支、闸门统计与提示接线）；
  顺手修了 `verify_offline.py` 里一条因 `isGameServer` 重构后失效的小写规整断言
  （改成查它调用的 `host()`）。
- `HANDOFF.md`：新增「扩展包/资源下载在单机模式下的处理」一节，**写明放行/拦截的域名与路径清单**、
  不开口子的理由、以及「游戏若每次启动都校验 bundle 版本则退回只拦写请求」的限制。

本地验证：`verify_offline.py` 75/75、`OfflineTest` 99/99；`javac --release 8 -Xlint:all` 全绿；
`d8 --min-api 21` 出 `classes.dex`（100,488 字节）；`manifest_zh.py` 对原版 axml 往返通过。

## 2026-10-21 03:40 第二批「单机模式」+ 第三批「分享 / 导出」（feat/offline-share）

分支 `feat/offline-share`，从 `fix/anti-flag`（25d3f3e）切出，**不合并 main、不发 Release**。

### 单机模式（开关，默认关）
- 需求中途变过一次：起初是「只拦上传」，后改为**真单机**——开启后游戏完全不连服务器，登录也不发，
  设备码一个字节都不出手机。按后者实现。
- 新增 `inj/.../Offline.java`：开关（`shared_prefs/elneazh_settings.xml` 键 `offline`，随备份走）、
  `isGameServer()`、`intercept()`、`isWriteRequest()/isReadRequest()`（只用于日志归类）、
  `offlineReply()`（本机回包）。
  - `isGameServer` 第一版写成 `url.contains("althi.co.jp") && contains("/sns04/")`，
    离线测试当场打出一个**真实缺陷**：`https://althi.co.jp.evil.com/sns04/save2.php` 会被当成游戏服务器。
    改为解析 host、按 `.althi.co.jp` 后缀判定（`host == althi.co.jp || host.endsWith(".althi.co.jp")`），
    顺带处理 port / userinfo / 尾点。
  - 同一轮测试还打出 `isReadRequest()` 循环里 `return false` 写错（应 `true`），一起修了。
- 新增 `inj/.../NetBlocker.java`：`URL.setURLStreamHandlerFactory` 接管 http/https + `ProxySelector` 兜底。
  命中时不建 socket，`LocalConnection` 就地回本机生成的字节；上传方向 `getOutputStream` 吞掉。
  放行用 ThreadLocal 保镖 + `new URL(proto,host,port,file)` 回默认 handler（否则 https 自递归）。
  编译时踩到 `URLConnection` 没有 `getResponseCode/getResponseMessage`（那是 `HttpURLConnection`），已删。
- `Support.java` 加开关 UI（原版米黄/棕/橙风格，复用 `button()`/`round()`），文案用 `Offline.limits()`。
- **结论性限制（写进 HANDOFF）**：Unity 用 `UnityWebRequest` + native curl，静态看不经 java.net；
  若真机 logcat 没有 `offline: intercepted`，说明 Java 闸门拦不到，单机开关只是界面声明。
  不做伪造业务回包、不改设备码、不删服务器标记、不绕反作弊。

### 分享 / 导出
- 新增 `inj/.../Share.java`：`ACTION_SEND` + FileProvider（authority `jp.co.althi.elnea.en.zhfiles`）；
  暂存目录最初写成 `cacheDir/out` + 新建 `file_paths.xml`，核对原包资源后发现 **`xml` 只有三个 id**，
  且 7f100002 就是 `image_share_filepaths`（`files-path path="image_provider/"`），
  于是改成共用它、暂存到 `filesDir/image_provider/` —— 不新增资源、也不怕被清缓存。
- `Backup.uriOf()` 新增；`Support.java` 加三个入口按钮；导入沿用 `Backup.pick`/`confirmRestore`（先自动备份）。
- `tools/manifest_zh.py`：加 FileProvider provider + meta-data、`SupportActivity` 的 `noHistory`、
  Application 的 `requestLegacyExternalStorage`，并在末尾 assert。

### 离线验证（接进 build-check）
- `tools/verify_offline.py`（65 项，源码级护栏）+ `tools/offline_test/`（JDK 直跑的 79 项 Java 测试，
  android 类用极小 stub）。两条都进 `.github/workflows/build-check.yml`，push 就拦。
- 本地实测：`javac --release 8 -cp android.jar` 全绿（14 源文件 / 67 classes）；`tools/ci_inj.py` OK；
  `manifest_zh.py` 对原版 axml 往返通过。

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


## 2026-10-20 14:00 国王更常主动来找玩家（只对国王，libil2cpp 补丁，待真机验证）
- DSH 会话 session-eb6185c8 调查（_hark\king_visit.md）：NPC 主动找人由 HumanData.SelectTalkFriend（VA 0x19c924c）在自己的好友里掷骰，玩家得分 30/60(+30)；国王没被排除，只是概率不高。送礼物品的选择函数未确认。
- tools/so_patch_zh.py：0x19c93c8 `add w9,w8,#30` → `bl king_visit_stub`（VA 0x1c05b18，InfoMenuPanel 联系函数死代码尾部 0x3c 字节）：正在挑人的 NPC 是国王、对象是玩家时得分 100（必选玩家），其余 NPC 不变；仍受 IsAlreadyTalk 每日一次限制，不会刷屏。
- 前提：玩家要在国王的好友表里（先去和国王搭过话）。送礼是否发生取决于原版对话分支。


## 2026-10-20 14:10 存档备份 / 恢复（多份，卸载不丢，待真机验证）
- inj/Backup.java：备份 = zip（files/ 里的存档等 + shared_prefs/，跳过 UnityCache/il2cpp/zh_backup 和我们自带的主数据表），写到手机公共目录「文档/艾尔尼亚存档备份/存档_<类型>_<时间>.zip」（API29+ 走 MediaStore，无需权限），卸载游戏不会清。
- 自动：ZhInit 最先执行，距上次超过 6 小时且有存档就备份一次（改名处理之前），自动的只留最近 10 份；手动、恢复前的备份永不删。
- 「交流与支持」框加「备份存档」「从备份恢复存档」：恢复用系统文件选择器选 zip（重装后也能选旧备份）→ 确认 → 先备份当前 → 覆盖 → 关闭游戏，重开生效。只接受带 elneazh_backup.txt 的汉化版备份，路径防穿越。
- 用户要求加入旧工作区的「假维护一键救援」（删服务器下发的作弊标记）/ 去掉维护检测：拒绝，属于绕过反作弊封禁。
