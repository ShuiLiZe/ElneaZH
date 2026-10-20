# HANDOFF — 现状（只写当前状态；过程见 docs/CHANGELOG.md，规矩见 AGENTS.md）

_最后更新：2026-10-21 07:10 CST_

## 2026-10-21 07:10 单机模式：联网/付费入口**直接隐藏**（六族 native 钩子，待真机验证）

用户最终定调：单机时联网与付费入口**直接去掉——不显示、不置灰、不弹提示**。
上一版（06:15）挂上去之后是把入口"置成不可用（变灰）"，不符合要求，已按下面改成隐藏。
「入口返回 NULL 会空指针」的顾虑仍然成立，所以这一版**不出 NULL、不去设灰**，只做隐藏。

### 六族钩子（编号 = Java `OfflineGate.TARGET_*` = C `EG_TARGET_*`，两处必须一致）

| # | 名字 | 挂什么 | 单机时怎么做 |
|---|------|--------|--------------|
| 1 | PANEL | 纯「打开面板」的入口（`OpenShopPanel` 等） | 不执行，返回 NULL（返回值没人用） |
| 2 | HTTP | `HttpSystem.httpConnect`/`connectWWW`/`AddHttpQueue`/`StartHttpQueue`/`UpdateUserInfoPair` | 返回 0，一个字节都不发，交给游戏自己的通信失败/离线分支 |
| 3 | GATE | 47 个 `Add*Panel` 工厂 | 工厂照跑（拿到实例，**绝不返回 NULL**），造出来的面板立刻用面板自己的 `CloseAll` → `CloseButtonClick` → `SetActive(false)` 收掉 |
| 4 | BUTTON | 34 个 `*ButtonClick` 入口回调 | **原回调一行都不跑**，并再 `SetActive(false)` 一次（入口本该已不可见，这是双保险）；不调 `interactable`、不弹提示 |
| 5 | HIDE | `MenuCell.set_EnableFlag`/`set_ActiveFlag`/`set_IsActive`/`set_Visible`/`set_Alpha`/`SetActiveSelf`、`InfoMenuPanel.set_isActiveCheckBrokenPanelButton` | **先跑原方法**（不打断游戏自己的状态），紧接着 `GetComponent.gameObject.SetActive(false)` + 让所在 Grid/Table `Reposition()`（解析得到才调） |
| 6 | MASKS | `AbstractPanel.addMaskCollider*`/`addNowLoadingPanel` | 不加遮罩，玩家不会被困在黑幕后面 |

**隐藏而不是置灰**：入口对象走 `Component.get_gameObject()` → `GameObject.SetActive(false)`；
退一步 `set_active` / `set_visible`。**不再有任何 `set_interactable` / `set_PanelEnabled` 调用**
（`verify_offline.py` 有断言钉住这两者不出现，见下）。

**为什么 GATE 不返回 NULL**：`Add*Panel` 的返回值调用方会立刻使用（`var p = AddMailPanel(...)`），
返回 NULL 就是空指针。所以 GATE 走「照跑 + 立刻收掉」，且收的方法优先用**面板自己的**
`CloseAll`/`CloseButtonClick`（metadata 22991 行起有 `CloseAll`、`CloseButtonClick`），
最后才退到 `SetActive(false)` —— 不会留一个打不开又关不掉的框。

### 怎么做的（`native/elneagate/eg_offline.c`）
1. `dlsym` 取 `il2cpp_domain_get` → `il2cpp_domain_get_assemblies` → `il2cpp_assembly_get_image`
   → `il2cpp_class_from_name` → `il2cpp_class_get_method_from_name`；另取
   `il2cpp_object_get_class`（**从实例拿类**才能找到 `get_gameObject`/`SetActive` 这类成员）和
   `il2cpp_thread_attach`（非游戏线程调托管方法前必须 attach）。
2. 遍历所有 assembly 找类（命名空间写 `*` 表示不限），按 0..6 个参数依次试解析方法名，
   取 `MethodInfo` 的**第一个字段**当函数地址。
3. `A64HookFunction` 挂上去；`EG_MAX` 96 个槽，转发函数 `eg_hook_0..95` 用宏生成。
4. 三个隐藏用的小工具：`eg_hide_object`（`SetActive(false)`，退 `set_active`/`set_visible`）、
   `eg_close_panel`（`CloseAll` → `CloseButtonClick` → 隐藏）、`eg_relayout`（Grid/Table `Reposition`）。
   不置灰、不写 alpha、不碰 interactable。
5. 开关值 `'1'/'0'` 由 Java 侧 `push` 灌入、**每次回调现读**，切开关不用重进游戏。
6. 解析不到的条目：`resolve miss: ...` 打一行日志并跳过，**绝不崩**。

白名单全部在 `inj/.../OfflineGate.java`（`buttonTable()`/`gateTable()`/`hideTable()`/
`panelTable()`/`httpTable()`/`masksTable()`），改名单只改这一个文件。

### 接线
- `ZhInit.onCreate`：`OfflineGate.install(getContext(), 1)`，自己的 try/catch，
  排在 `NameGate.install` / `NetBlocker.install` 之后、`SelfCheck.run` 之前。
- `Support` 支援弹窗：开关翻转时先 `OfflineGate.push(next)` + `OfflineGate.resetStats()` 再改界面；
  单机时多一行 `OfflineGate.summary()`（挂了几个点、已挡几次请求/隐藏几次入口）。
  文案写的是「联网/付费入口已隐藏」。
- `SelfCheck`：`offline native: ...`、`offline native api: n/5`、六族条目数、`offline native per target: [...]`。

### 真机怎么确认生效
`adb logcat -s ElneaOffline`，认这几条（`verify_gate.py` 在 .so 里钉住了这些字符串）：
- `offline: skip entry ... (not shown at all)` —— PANEL 族挡了打开入口；
- `offline: drop request ... (report comm failure)` —— HTTP 族没发请求；
- `offline: ... skipped (panel not created)` —— GATE 族收面板；
- `offline: ... blocked (entry hidden=1)` —— BUTTON 族藏掉了入口；
- `offline: ... hidden on refresh` —— HIDE 族在刷新时又藏了一次（`hidden=0` 说明 `get_gameObject`/`SetActive` 没解析上）；
- `resolve miss: *	类	方法` —— 该条没解析上（跳过，无害）。

### 边界（刻意保留的，别改）
- **不伪造服务器回包**：native 侧没有任何组包/造响应对象的逻辑；`verify_offline.py` 有断言钉住
  「`eg_offline.c`/`OfflineGate.java` 里不许出现 `il2cpp_object_new` / `il2cpp_string_new('{')`」。
- 不做破解内购、不改设备码、不删服务器标记、不绕反作弊。
  已购/已解锁内容沿用本机上次记录（`Offline.purchaseHint()`），不向服务器查询。
- **资源下载不动**：下载走 `AssetBundleManager`，不带身份信息，边玩边下照旧。
- **armv7 没有 hook 引擎**（And64InlineHook 只有 AArch64）：armv7 上 `nativeInstall` 返回 -4，
  整族跳过，只剩 Java 层 `NetBlocker` 兜底。真机以 arm64 为准。
- **关掉单机后恢复**：所有族回原样转发/执行，不再隐藏；下次界面重新构建时入口自然回来。
  已经在屏幕上的那个界面要重新进一次才回来（我们不主动 `SetActive(true)`，避免和游戏的显隐逻辑打架）。

### 必须先确认的（真机才知道，我不擅自改）
1. **`get_gameObject` / `SetActive` / `CloseAll` / `CloseButtonClick` / `Reposition` 能否在运行期解析到**：
   名字来自 `global-metadata.dat`（`AbstractPanel` 22949 行起、`MenuCell` 27487 行起），
   但具体实例上能不能解析只有真机能确认。日志里 `hidden=0` 就说明没解析上。
2. **GATE 族收面板会不会打断游戏自己的流程**：如果某个面板被收掉后游戏状态异常，
   把该条从 `gateTable()` 删掉（改由 HIDE/BUTTON 族隐藏入口）即可，不用改代码结构。
3. **`httpConnect` 的返回值语义**：返回 0 对 `IEnumerator`（协程不跑）/`void`（直接返回）合法，
   对 `bool` 是 false。真机若表现为「一直转圈等网络」，把 `httpTable()` 清空，
   只留 Java 层 `NetBlocker`（`Offline.allowWhileOffline` 那条闸）。
4. **启动是否强依赖登录回包**：若开单机后卡在标题/维护画面，说明登录流程必须拿到服务器回包。
   那就**不要伪造回包**，改为只挡写操作（`Offline.WRITE` 表里的 `save2`/`save`/`kpi` 等），
   读类放行 —— 这条限制从第一版就写在 `Offline.limits()` 里。

## 2026-10-21 05:55 单机模式改走 native（已被上面 07:10 版取代，只留结论）

事实与 06:15 版相同，不再重复：Java 层反射不到 `MenuCell.EnableFlag`/`AbstractPanel`；
`libil2cpp.so` 里 grep 不到这些名字（只在 `global-metadata.dat`）；
`base.apk` 的 dex 里 `AndroidUuidPlugin`/`AndroidTools`/`GetUUID` **0 命中**。→ UI 与网络只能走 il2cpp。
那版的「面板族返回 NULL」已被证明不够安全，见 06:15 节表格。

## 2026-10-21 03:40 单机模式 + 分享/导出（分支 feat/offline-share，待真机验证）

### 单机模式：开关，默认关，开了就真的一个字节都不出手机
入口：游戏内 `Support`（汉化设置/支援弹窗）→「单机模式」开关。状态存
`shared_prefs/elneazh_settings.xml`（键 `offline`），`Backup` 会跟着存档一起备份/恢复。

判定「游戏服务器」：`Offline.isGameServer()` 按 **host 后缀**认 `althi.co.jp` 及其所有子域
（**不用 contains** —— 会把 `althi.co.jp.evil.com` 误判成自家服务器），且路径含 `/sns04/`。
Java 层闸门 `NetBlocker`（`URL.setURLStreamHandlerFactory` + `ProxySelector`）每个请求现读开关，
命中就本地接住、不发出去。native 闸门（上一节）是更强的一层。

### 不做 / 边界（刻意保留的）
- 不伪造服务器业务数据（存档、角色、物品都不是编的）、不改设备码、不删服务器标记、不绕反作弊。
- `Offline.limits()` 里写清了单机时不可用的东西：云存档、好友、邮件、PVP、礼物。

### 扩展包 / 资源下载在单机模式下的处理（2026-10-21 补充，别再改回去）
**结论：资源下载放行。** 依据（写进代码注释与 `Offline` 的类注释，勿推翻）：
- 下载走 `AssetBundleManager.downloadAndCache` → `get_Url` → `UnityWebRequest`，
  由 `getEnvironmentURL()`/`getAssetBundleURL()`/`getManifestURL()` 拼地址，**不经过 HttpSystem**；
- `AssetBundleManager` 整块的字段/成员里 `user_number`/`crypt_key`/`UserNumberOnServer`/
  `CryptKeyOnServer`/`key_header_info`/`addUniqArg`/`uniq`/`uuid`/`playerId`/`token`
  **全部 0 次命中** —— 即这条下载路径不带任何账号或设备标识；
- 身份参数只存在于 `HttpSystem`（即 `/sns04/*.php`）。

放行/拦截规则（`Offline.allowWhileOffline`）：
- **放行**：非游戏服务器 且 非资源宿主的地址；althi 宿主上「资源形状」的路径
  （`.bundle`/`.unity3d`/`.ab`/`.assetbundle`/名字含 `assetbundle`/末段是 `manifest` 或 `*.manifest`）；
  `*.php` 被显式排除在「资源形状」之外。
- **拦截**：althi 宿主 + `/sns04/` + `*.php`（登录、存档上传、排行、邮件、PVP…）。

已知限制：若某个版本改成「先请求带身份的 php 拿临时 URL 再下载」，那条 php 会被挡。
届时应把它加进放行例外并在本节登记，**不伪造回包**。

### 分享 / 导出（第三批）
入口：`Support` 弹窗「分享 / 导出」区，三个按钮，各自独立 try/catch，出错只 Toast。
- 导出存档：`Backup.make()` 现做一个 zip → `Share.stage()` 落到 `files/image_provider/`
  → `FileProvider`（authority `jp.co.althi.elnea.en.zhfiles`）→ `ACTION_SEND` + `createChooser`。
- 导入存档：`ACTION_OPEN_DOCUMENT` 选 zip → **先自动备份当前存档** → 走已有 restore。
- 导出名字对照表：`elneazh_names` 别名表（中文↔拼音）→ `艾尔尼亚名字对照表.txt` → 分享。

## 离线验证（CI 里跑，不需要手机）
- `python tools/verify_offline.py` —— **172 项**源码级护栏（含六族表的格式/互不冲突、
  资源下载不在拦截名单、「不置灰（`set_interactable`/`PanelEnabled` 不许出现）」、
  「必须用 `SetActive` 隐藏」、GATE 必须用 `CloseAll` 收面板、隐藏后必须 `Reposition`、
  不伪造回包的负向断言）。
- `tools/offline_test/` 的 `OfflineTest`（JDK 直跑）—— **200 项**：六族编号、表格式、
  去重、交叉冲突、覆盖面、`TARGET_HIDE` 名单、native 未加载时「安静失败不抛」。
- `python tools/verify_gate.py` —— .so 里必须含 `ElneaOffline` 标签、7 个 JNI 导出、
  六族钩子的日志字符串（`skip entry` / `drop request` / `panel not created` /
  `blocked (entry hidden=` / `hidden on refresh` / `no mask for`；只在 arm64 检查，
  armv7 没有 hook 引擎）。

## 现在能用的版本
- Release `latest` 的 ElneaZH.apk（每次推送自动构建）。装前先在游戏「Support → 存档数据」备份，卸载原版后安装汉化版（签名不同）。
- 已包含：11,093 条资源文本中文；NGUI 位图字库补齐 GB2312 6,763 字；10 张中文贴图（ETC2 红变蓝已修，mip 保留）；global-metadata.dat 补丁（取名白名单加汉字）。

## 固定签名 / 免卸载更新（21:05 加入，待真机验证）
- 签名本来就固定（app/keystore/elneazh.p12），只有第一次从商店版换汉化版要卸载。
- App 现在会：检测已装游戏是否是自己签名的汉化版 → 是就提示直接覆盖安装；第一次汉化成功后把原版 APK 存到 App 私有目录（filesDir/orig），以后用它打补丁。没存过的话让用户手动选原版 APK（仓库 base/apk 有）。

## 2026-10-21 03:20 上传前名字闸门：改 EncryptRJ256 的明文（单向）

**问题**：存档里只要带中文名，上传到 `sns04/*.php` 就是汉字。服务器只认原版字形，中文名是「非原版客户端」的硬证据。

**落点（真机 frida 取样确认，不是猜的）**：`Assembly-CSharp` 的 `Cipher::EncryptRJ256(string)`

| 项 | 值 |
| --- | --- |
| 函数入口 | VA `0x18ad7e4` = ELF file offset `0x18a97e4`（入口 `str x30,[sp,#-0x30]!`，x0 = 明文） |
| 返回 | `0x18ad97c` 处 `b` 跳到 il2cpp String 构造，所以「返回值」就是下一次调用 EncryptRJ256 的入参 |
| 兄弟函数 | `0x18ad984` = `DecryptRJ256`（服务器回包解密） |
| 初始化块 | `0x18ad804` `tbnz` → `0x18ad840` 是「首次调用才走」的静态初始化分支（`bl il2cpp_codegen_initialize_runtime_metadata` + 置标志） |
| 跳板位置 | `0x18ad834`，8 条指令 32 字节，正好塞在初始化块里；`0x18ad854` 回到原流程 |

**为什么跳板敢放在初始化块里**：这段代码只在第一次调用时执行，只写 x8 与 `[x21+0xef9]` 标志位；而 EncryptRJ256 一进来（`0x18ad7e4..0x18ad830`）就把 x19/x20/x21 存到了栈上，跳板只用 x9/x10，且三条分支目标（`0x18ad6e0` / `0x18ad840` / 原地）全部落在原有入口内部 → 不动栈帧、不动活值。

**改什么**：`Cipher.EncryptRJ256` 收到的明文是 JSON，其中 `ws_profiles` / `ws_familys` / `ws_animals*` 这些字段的值是 `base64(zlib(...))`，解开后名字是 `0x01 + 1 字节长度 + UTF-8` 的小槽。`inj/UploadNames.java` 只改这些槽：

- 汉字名 → 固定拼音（同一汉字永远同一拼音，靠 SharedPreferences `elneazh_names` 记住），长度字节同步改写；
- 其它字节（id、链接、25 字节属性、噪声）逐字节保持；
- 解不开 / 不是 zlib / 不是 base64 / 替换反而更长 → **原样不动**，宁可漏改也不写坏存档；
- 纯 ASCII 请求体直接跳过（原版玩家走路不变）。

**验证**（`tools/verify_upload_names.py`，Python 复刻；`UploadNamesSample` + 真 JVM 跑同一套夹具）：

```
python tools/verify_upload_names.py     -> RESULT: PASS （19 项）
javac/d8 inj -> javac OK, 57 classes
JVM 直跑 Harness: filterNames OK / blob 96 -> 88 roundtrip OK notLonger OK -> RESULT PASS
```

**风险（真机未验证）**：跳板的机器码（`tools/net_gate_so.py`，尚未落地）本机没有真机跑过；`bl` 到 Java 的 JNI 往返（`NetGuard.ensure`）还没接完，目前 `NetGuard.install()` 只探测、返回 false 并写明「闸门未激活」，绝不阻断启动。跳板接好之前，这一层是**死代码**，不会影响游戏。

**解密工具**：`tools/decrypt_acc.py`（`--key/--iv` 支持 hex 与 base64，`--mode cbc|ecb`，`--selftest` 自检全绿），用来核对抓包里的 `acc=`。

## 2026-10-21 00:30 名字：本地存档保持中文，只在上传时换成拼音（单向）
策略定稿：**不改存档、不改显示**。玩家和 NPC 的中文名照旧留在本地、照旧在界面上显示中文；
只在数据离开设备（上传 `sns04/*.php`）时把明文里的中文名换成固定拼音；服务器响应里回来的拼音原样显示，不做反向还原。

- 停用旧的「老存档人名转中文」：删除 `inj/.../SaveNames.java`，`ZhInit.onCreate` 里不再调用它。
  （旧版 CI #62 / 909e224e 那套把存档里的人名改成中文，正是被标记的来源之一。）
- 名字表不再做英文化：`tools/bmfont_zh.py`、`tools/metadata_zh.py`、`tools/build_patch.py` 保持原样
  （即 Name 字体与取名白名单仍放行汉字、NPC 名字表仍是中文译文），因为中文现在只留在本地。
- 新增 `inj/.../NameFix.java`：中文名 → **恒定**拼音。别名表（`SharedPreferences` `elneazh_names`，键 `n:<拼音>`）优先，
  未命中则用 `names.tsv` 的中文→英文原文，再没有就逐字拼音（纯函数，同输入同输出），并写回别名表；
  别名表在 `shared_prefs/` 下，`Backup` 已会随备份/恢复一起带走。
- 新增 `inj/.../Pinyin.java`：`android.icu.text.Transliterator("Han-Latin; Latin-ASCII")`（反射调用，API 29+），
  API 24–28 退回打包的 `assets/elneazh/data/pinyin.tsv`（GB2312 6763 字，`tools/pinyin_tables.py` 生成）。
- `inj/.../NetGuard.java` 重写为**单向**闸门：`body()` 只清洗请求体键值里的非 ASCII，`resp()` 原样返回。
  **代码位置已找到但 Java 侧挂不上**（证据见下），所以闸门目前是「备用+可离线自测」状态，
  真正的防线在「名字进入游戏前就已经是 ASCII 之外无他」这条路上——见下一条。
- 新增 `inj/.../SelfCheck.java`：启动时把词表规模、拼音恒定性、闸门行为写进 `files/elneazh_selfcheck.txt`
  并打 `ElneaZH-SELF` 日志，便于真机一次 `adb logcat -d -s ElneaZH-SELF ElneaZH-NET` 验证。

### 网络栈与加密入口的静态结论（找不到可挂点，记录以免重复挖）
- 游戏 HTTP 走 `UnityWebRequest`（APK 的 `ScriptingAssemblies.json` 含 `UnityEngine.UnityWebRequestModule.dll`，
  `libunity.so` 有 41 个 `UnityEngine.Networking.UnityWebRequest::*` 符号）；传输是 native curl，
  **不经过 okhttp / java.net**——所以拦 Java 的 `CallServerInterceptor`、`HttpURLConnection` 一律无效。
- `libil2cpp.so`（61MB）里 `acc=` / `lang=` / `sns04` / `althi` / `POST` **0 命中**；`libunity.so` 同样 0 命中。
  请求体是 C# 层用 `il2cpp_string_new` 内联拼的代码（例：`pvp.php` 唯一引用点 VA 0x17fc6a8 是
  `mov x8,wzr; stp x21,xzr,[x0]; adrp x20,#0x8d6000; add x20,#0x27c; mov w19,#0x15` 这种对象构造），
  没有可替换的命名导出函数；il2cpp 的字符串表指针（`0x389c9a8`/`0x389c9b0`）静态初值为 0、运行时才填，
  **静态定位不到加密入口**。要真正在加密前截明文，只能真机动态 hook（Frida/LSPatch）+ 一条抓包 `acc=` 样本。
- 已知的接口名（`tools/metadata_zh.py --dump` 可从字面量表取到）：`http://app-elnea.althi.co.jp/sns04/`（字面量 #12315）、
  `res-elnea`(#12320)、`cdn-elnea`(#12316)，参数名 `acc`(#10187) / `lang`(#12806)，接口 `read.php`/`save2.php`/
  `recov2.php`/`pvp.php`/`rename.php`/`token.php` 等 27 个 `.php`。

### 未验证 / 风险
- 闸门在 Java 侧无挂点（上面已说明），所以**上传时是否真的没有汉字，取决于名字进入游戏前是否已转 ASCII**；
  当前版本没有强制转换玩家输入（取名白名单仍放行汉字），这一点是有意保留的（用户要求本地保持中文）。
- 真正要收敛风险，下一步应做动态 hook 或要求玩家取名用英文/拼音；等用户给 `acc=` 样本后可继续做解密侧验证。

## 2026-10-20 23:30 已移除：888 好友上限（防封）
- 移除 tools/so_patch_zh.py 里的 FRIEND_MIN/FRIEND_MAX/FRIEND_LIMIT 与 `mov w0,#888`/`mov w1,#888` 两个补丁字节；VA 0x19a48d4 / 0x19a48d8 现已恢复原指令 `mov w0,#52` / `mov w1,#102`（脚本里只留断言，输入 .so 若已被改过会直接报错）。
- 原因：原版客户端交友上限就是 102，888 是服务器侧一眼可辨的非法值；get_limitFriendCount 又是每次存档/上传统计都会走到的数据来源，属于典型的“客户端数据与原版不一致”特征。本地多交友的收益远小于封号风险。
- 国王补丁（CreateNewKing 首个国王「杨广」）与 SelectTalkFriend 补丁保留。注意：补丁本身不含中文，“杨广”两个汉字是 libil2cpp 里 `il2cpp_string_new` 从 so 内字面量构造的，不走存档名字段；若以后发现服务器会看到王的名字，需要再改。

## 2026-10-20 19:30 真机卡维护 / 强制输入账号（OurPlay 正常）
- 根因：UuidPlugin.GetUUID 在 PlayerPrefs 没缓存时采用 AndroidUuidPlugin 按设备算出的 ID（清数据、卸载重装都不变），服务器对该身份下发了维护锁（规则模式→维护页；全局模式→登录失败 10 次弹账号框）。OurPlay 沙盒设备 ID 不同所以正常。
- 修复：tools/so_patch_zh.py 新增 VA 0x1c8f490 `tbz` → nop，不再采用设备 ID，首次随机 makeUUID 后缓存。装新版后需清一次游戏数据（或全新安装）才会换身份。v1.0.81 仍卡维护：新身份 = String.Format(设备串 VA 0x18a2db4, 随机串).Substring(0,41)，设备串在前，截断后仍绑设备。
- v1.0.82（1ba8d74）再加 VA 0x1c8f4a8 `bl 0x18a2db4` → `mov x0,xzr`，只用随机串。2026-10-20 20:00 用户真机确认可进游戏（重新汉化 + 清一次数据）。
- 不做"免清数据"：改 PlayerPrefs 键会让没被锁的人丢档；收到维护自动换身份则官方真维护时会误伤。卡维护的人清一次即可。

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

## 2026-10-20 11:50 标题画面「支援」里加「交流群与支持」（待真机验证）
- 原版「联系我们」（TitlePanel.ContactButtonClick）是游戏内网页面板 AddWebPanel(URLType.Contact)，不走安卓 Intent，Java 层拦不到。
- 做法：libil2cpp 补丁（tools/so_patch_zh.py）把 TitlePanel 和游戏内 InfoMenuPanel 的 ContactButtonClick 改成 `Application.OpenURL("elneazh://support")`；
  manifest_zh.py 加透明 `SupportActivity`（intent-filter elneazh://support，exported=true）；它弹 `inj/.../Support.java` 画的原版风格对话框（米黄底、棕边、橙按钮）：
  加入 QQ 群（打开群分享链接，失败就复制群号）、复制群号、微信收款码、免费/自愿说明、「联系原作者（英文邮件）」（mailto elnea_support_en@althi.co.jp，没邮件 App 就复制邮箱）、关闭。
- 按钮字 SystemText s367「联系我们」→「交流群与支持」。账号设置里忘记密码的「联系」（AccountSettingPanel）保持原版网页表单，方便找回账号。
- 首次启动弹窗也换成同一个对话框，KEY 改 shown_v2（老用户更新后再看到一次）。
- 测：标题画面右下「支援」→「交流群与支持」应弹出对话框；点「加入 QQ 群」应跳 QQ/浏览器。若点了没反应：`adb logcat | grep -i -E "ElneaZH|elneazh|Unity"` 看 OpenURL 是否发出 intent。

## 2026-10-20 12:45 新开档初始国王 =「杨广」（libil2cpp 补丁，待真机验证）
- 新世界的国王不是开档时建的，而是每日阶段 dailyPhaseSupplementKing → KingData.CreateNewKing 里 CreateAdult(随机男/女名, 随机家族, NextSex) 新建（6 岁成年、单身）。
- tools/so_patch_zh.py 新增：CreateNewKing 里的 `bl CreateAdult`（VA 0x1942590）→ king_stub（VA 0x1c43db0，TitlePanel 联系按钮函数的死代码尾部）。世界第一位国王（GenerationData.currentGeneration<0）改为 CreateAdult("杨广", 原家族, 男) + 肤色 Asia(黄)；其余情况原样。
- 年龄 6（游戏成年年龄常量 6；5 岁是儿童，当国王风险大），出生日期游戏自己算；未婚有保证（新建人物，setupNewKing 无婚配）。老存档不受影响。详情 docs/king_research.md §8。
- 测：装新版 → 标题「新游戏」开新档，捏完人进游戏，过完第一天（或开档后看国王/王宫、人物名册），国王应叫「杨广」、男、黄皮、6 岁、未婚。若开档就闪退或国王还是随机名：`adb logcat -b crash` 发来。


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

## 2026-10-21 04:10 上传前名字闸门：EncryptRJ256 入口把中文名换拼音（待真机验证）

### 为什么
上一版只有 Java 侧的过滤函数，没有任何挂点 —— 请求照样带着中文名出门，防封等于没做。
这一版把闸门真正挂到游戏自己的加密函数上。

### 加密入口（真机确认）
- `Assembly-CSharp` 的 `Cipher::EncryptRJ256(string)`，`libil2cpp.so` VA `0x18ad7e4`（Decrypt 在 `0x18ad984`）。
- 入参是**完整明文 JSON**，返回值就是抓包里的 `acc=`（base64）。
- 明文里 `save2.php` 的 `savedata` 字段是「字符串里套 JSON」，内层每个 `ws_*` 的值是 `base64(zlib(二进制存档))`，
  中文名就躺在 zlib 解出来的记录里；`login/check/pvp/mail/gift/kpi` 等请求本来就没有中文。

### 实现（native/elneagate → libelneagate.so，arm64-v8a + armeabi-v7a）
- `elneagate.c`：`JNI_OnLoad` 里对 `0x18ad7e4` 做 inline hook（vendor/And64InlineHook），
  原函数执行前拿明文，过滤后再交给原函数；hook 不上就什么都不做，只打日志。
- `eg_names.c`：
  - `eg_filter_raw()`：扫存档记录里的名字槽（`0x01` + 1 字节长度 + UTF-8 字节），按表替换；
    长度字节同步改写，替换只许变短或等长，超长/格式不符**原样不动**；
  - `eg_filter_zlib()`：inflate → 过滤 → deflate(9)，结果比原 base64 长就放弃；
  - `eg_rewrite_nested()` / `eg_filter_json()`：在明文里找 `\"key\":\"<base64>\"` 这种嵌套字面量，
    只碰长得像 zlib+base64 的值，其它字段（uuid、lang…）一个字节都不动；纯 ASCII 明文直接返回 NULL（不处理）。
  - 映射表 = `assets/elneazh/data/names.tsv`（NPC 中文名 → 英文原文）+ 玩家别名（中文 → 拼音），
    由 Java 侧 `NameGate.pushNames()` 在 `NameFix.load()` 之后整表灌进来，两边同一份。
- Java 侧：`NameGate.java`（把 `assets/elneagate/lib<abi>/libelneagate.so` 解到 `files/lib/` 后 `System.load`，
  后台线程重试到 `libil2cpp` 加载完）、`NameFix.pairs()`（映射快照）、`NameFix.ascii()` 里每新增一条别名就同步给 native。
- 日志标签 `ElneaGate` / `ElneaZH-NET`。

### 只改请求，不改本地
本地存档、界面、服务器回包都不经过这道闸门 —— 游戏里看到的名字仍然是中文，
只有加密前那一刻的中文名被换成拼音。同一个名字每次换出的拼音固定，服务器侧看起来就是稳定用英文名。

### 验证
- `python tools/build_gate.py`：双 ABI 编译 + strip，arm64-v8a 27,272 B / armeabi-v7a 15,044 B。
- `python tools/verify_gate.py`：26 项全 PASS（C 夹具 5 个名字逐字节、幂等、zlib 路径、
  JSON 层 uuid/lang 不动、解压回 EXPECT、纯 ASCII 零改动、产物 ELF + 含日志标签）。
- `python tools/ci_inj.py`：javac OK，60 个类。
- `python tools/verify_upload_names.py`：29 项全 PASS。
- 本地 `gradle assembleRelease` BUILD SUCCESSFUL，APK 内含 `assets/elneagate/{arm64-v8a,armeabi-v7a}/libelneagate.so`。
- CI：`.github/workflows/build.yml` 与 `build-check.yml` 都加了 `tools/build_gate.py` + `tools/verify_gate.py` 两步。

### 未验证 / 风险
- **真机没验证**：hook 能否在游戏进程里成功装上（`il2cpp` 加载时机、arm64 指令改写、内存页权限）只有真机能确认。
  装失败的表现是「日志里有 ElneaGate 的失败原因、游戏照常跑、请求仍带中文名」，不会崩。
- 明文里如果出现非 `\"k\":\"v\"` 形态的中文名（例如直接写在顶层），这道闸门不处理 —— 目前抓包没见到。
- 名字槽扫描是启发式的（`0x01` + 长度 + UTF-8 名字），存档格式若变体会漏掉，不会改坏。

## 2026-10-20 23:55 更换游戏身份：先备份 + 清空本地数据（待真机验证）

- 抓包结论：旧存档 + 新 uuid → login.php 回 `already login!` → 弹账号框。**只抹 uuid 没用**，服务器是拿存档里的账号信息认人的，必须整套本地数据重来。
- inj/Identity.java（重写 `reset`）：
  1. 后台线程跑 `Backup.backupForIdentity(a)`：`make(a,"换身份前")` 返回非 null，且再校验这份 zip 里真有 `name="uuid"` 或 `name="login_key"`，两者都成立才返回 uri；否则**中止，一个字节都不动**，只弹「备份失败，未更换」。
  2. `Backup.wipeForIdentity(a, uri)`：按备份里 `elneazh_backup.txt` 的文件清单删 —— `files/`、`shared_prefs/`、`ext/` 下登记过的文件，外加 `shared_prefs/<pkg>.v2.playerprefs.xml`（uuid/login_key）、`databases/` 里的 login/token/user 类文件、`sv1_*` 与 `*.ntmp/*.rtmp`。**保留**：汉化主表 22 张（`zh/Contents` 里我们投放的 c_* 表）、我们自己的 `shared_prefs/elneazh_*`（含 `elneazh_names`）、`UnityCache`/`il2cpp` 缓存。
  3. 2 秒后 `finishAffinity` + `killProcess`，重开即新人物新身份。
- 备份侧（inj/Backup.java）：
  - 新增 `prefsName(ctx)` = `<pkg>.v2.playerprefs`（文件名依据：`libunity.so` 字面量 `.v2.playerprefs`，Unity 的 SharedPreferences 名就是 `<pkg>.v2.playerprefs`，落盘为 `shared_prefs/<pkg>.v2.playerprefs.xml`）。
  - `addPrefsFile()`：即使真实 prefs 文件不在 `/data/data/<pkg>/shared_prefs`（可能在 `/data/user_de/<n>/` 或 `/data/user/<n>/`），也用 `findPrefsFile()` 找到原文件整份写进 zip；再找不到就按 SharedPreferences 的 xml 结构从 `getAll()` 手写一份。zip 里重复条目合法，读取方按后者为准。
  - `addIdentFiles()`：`databases/`、`no_backup/`、`files/` 根下名含 login/token/user/account/uuid/session 的文件一并备份。
  - `elneazh_backup.txt` 增加 `identity=uuid=xxxx…xxxx login_key=true keys=N`（uuid 打码），列表里显示在大小后面，方便一眼分清哪份是哪个身份。
  - `restore()` 结束后调用 `restoreIdentityFile()` 把备份里的 `uuid`/`login_key` 等键 `commit()` 回 SharedPreferences（**旧身份解封后恢复即回去**）；同时把「当前存在、但备份没登记」的 `sv1_*` 清掉，避免新旧两份存档混在一起。
- `tools/verify_identity.py`：复刻 wipe 的保留/删除判定，实测 22 张汉化表 keep、`sv1_*`/playerprefs/login 库 delete、`elneazh_*` keep → `wipe rules OK`。
- 编译：`python tools/ci_inj.py`（等价 CI 的 `javac --release 8 -cp android.jar`）→ `javac OK, 58 classes`。
- 未验证：真机。风险点：某些机型 `shared_prefs` 目录被 SELinux 保护读不到时只能走「从内存手写 xml」分支（格式与 SharedPreferences 一致，恢复时由 `restoreIdentityFile` 重新 commit，理论可行）；`databases/` 里的登录库若被游戏打开着，删除后需重开进程生效（本流程本来就会 kill）。

## 2026-10-21 00:05 更换身份：改成最小删除集合（绝不碰数据包）

- 上一版（commit db2e97e）按备份清单整目录删，风险太大：`ext/UnityCache` 就是下载的 AssetBundle 缓存，删了要重下数据包。
- 现在 `Backup.wipeForIdentity` 只删「明确是登录凭证 / 旧存档」的东西，**白名单式最小删除**：

| 类别 | 处理 | 依据 |
| --- | --- | --- |
| `files/{zh,Contents,save,saves}` 及 ext 同位置的 `sv1_*`、`*.ntmp/*.rtmp` | 删 | 存档本体（旧身份的角色/家族/世界状态） |
| PlayerPrefs 登录键：`uuid` `login_key` `althi_uniq` `token` `token_id` `user_id` `user_uniq` `user_pass` `user_name` `user_rename` `account` `session` `session_id` `device_id` | 逐键 remove | global-metadata 字面量表：`althi_uniq`=10282、`login_key`=12937、`uuid`=15217、`token`=14859、`user_id`=15196、`user_uniq`=15205，与 `already login!`(10275)、`login.php`(12935)、`create.php`(11131) 同属登录流程；libil2cpp.so 与 dex 里 0 命中 → 确认是 C# 字面量（只存在于 metadata） |
| `shared_prefs/althi_uniq.xml`、`files/althi_uniq` 等独立凭证文件 | 删 | `althi_uniq` 有时单独存一个 xml / 无扩展名文件 |
| `databases/` 里名字含 login/account/token/user/session/althi 的 `.db*` | 删 | 账号库 |
| **`UnityCache/Shared/<bundle>/<hash>/{__data,__info}`** | **保留** | libunity.so 原生 AssetBundle 缓存（字面量 `UnityCache` / `__data` / `__info`），与登录无关 |
| **汉化主表 `c_*`（22 张）** | **保留** | 我们投放的 `zh/Contents` 表 |
| **`shared_prefs/elneazh_*`（含 `elneazh_names`、`elneazh_backup`）** | **保留** | 我们自己的设置 |
| **PlayerPrefs 资源/版本键（`assetbundle_version`=10436 等）** | **保留** | 只 remove 登录键，不整体清空 prefs |

- 附带修复：`Backup.add()` 原来的 `SKIP_DIRS` 只跳第 1 层，`ext/UnityCache` 这种深一层的仍会被打进备份 zip（备份因此巨大且慢）→ 改成任意层按名字跳。
- `restore()` 里"清掉备份没登记的 sv1_*"也收窄为**只碰 `sv1_*`**，不碰缓存与汉化表。
- 自检：`python tools/verify_identity.py` → `minimal wipe OK`（14 条文件规则 + 9 条 prefs 键规则，含 `UnityCache/__data` keep、`assetbundle_version` keep、`althi_uniq` delete）。
- 编译：`python tools/ci_inj.py` → `javac OK, 58 classes`。
- 目标达成路径：删掉登录键后 `UuidPlugin.GetUUID`（so_patch_zh.py 补丁：无缓存 id 就生成随机）会给新 uuid，`althi_uniq` 清空后重新取，AssetBundle 缓存仍在 → **重开不需要重新下载数据包，直接新建人物**。
- 未验证：真机。若某些机型把资源版本键也放进了上述独立凭证 xml（`althi_uniq.xml`），那份会被整个删掉 → 最坏是重新校验一次版本，不会重下整包（`UnityCache` 在）。
