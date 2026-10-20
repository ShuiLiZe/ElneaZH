# HANDOFF — 现状（只写当前状态；过程见 docs/CHANGELOG.md，规矩见 AGENTS.md）

_最后更新：2026-10-20 12:45 CST_

## 现在能用的版本
- Release `latest` 的 ElneaZH.apk（每次推送自动构建）。装前先在游戏「Support → 存档数据」备份，卸载原版后安装汉化版（签名不同）。
- 已包含：11,093 条资源文本中文；NGUI 位图字库补齐 GB2312 6,763 字；10 张中文贴图（ETC2 红变蓝已修，mip 保留）；global-metadata.dat 补丁（取名白名单加汉字）。

## 固定签名 / 免卸载更新（21:05 加入，待真机验证）
- 签名本来就固定（app/keystore/elneazh.p12），只有第一次从商店版换汉化版要卸载。
- App 现在会：检测已装游戏是否是自己签名的汉化版 → 是就提示直接覆盖安装；第一次汉化成功后把原版 APK 存到 App 私有目录（filesDir/orig），以后用它打补丁。没存过的话让用户手动选原版 APK（仓库 base/apk 有）。

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
