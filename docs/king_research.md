# Elnea Kingdom 2.10.7 — 初始国王生成 / 存档结构 / 婚姻 / 专属对话 调查报告

只读调查，未改动任何现有文件。目标："把初始国王改名「杨广」、变年轻、建模帅、未婚、加专属台词"。

置信度三档：**【已证实】**（反汇编或存档字节实证）/ **【高置信】**（统计+字段语义自洽）/ **【未证实】**（无直接证据，禁止当结论）。

---

## 0. 路径与总体结论

| 用途 | 路径 |
|---|---|
| dump.cs | `D:\workspaces\Elnea-Localization\_scratch\il2cpp_dump\out\dump.cs`（24,153,919 B） |
| script.json | 同目录（154,864 条 ScriptMethod） |
| ARM64 | `D:\workspaces\Elnea-Localization\02_extracted\apk\lib\arm64-v8a\libil2cpp.so`（61,465,584 B） |
| metadata | `01_raw_data\files\il2cpp\Metadata\global-metadata.dat`（9,660,464 B，v39） |
| 真实存档 | `01_raw_data\app_internal\files\Contents\sv1_ws_*`（zlib `78 da`，用 `zlib.decompress` 直接解） |
| 名字表 | `02_extracted\strings\_raw\NameMale_en.txt` / `NameFemale_en.txt` / `NameFamily_en.txt` |
| 内容表 | `01_raw_data\app_internal\files\Contents\c_*`（Rijndael，解密器 `tools\rijndael_game.py` 的 `decrypt()`） |

**dump.cs 的 RVA == .so 文件偏移**（已验证：0x19D3084 处 `stp/sub sp` 指令对得上）。
**但 script.json 的 Address 有 0x30~0x90 级偏移，且方法名与函数体严重错配**（例：0x19A720C 落在 `HumanData$$AddItem` 中段；0x1970FE4 落在 `HistoryInfo$$.ctor` 中间）。凡依赖符号名的地址，都已在下面标注"已核对函数头"或"未证实"。

### 总体结论（先说你要的）

1. **初始国王的名字是随机的**，从 TextAsset `NameMale_en` 里抽，代码里没有任何"固定初始国王名"。要叫杨广只能**改存档**或**改名字表**。
2. **"谁是国王"存在 `sv1_ws_info`（不是 profiles）**，因为 `KingData` 挂在 `SaveInfo.kingData @0x38`，而 `WorldData.SaveFileName` 枚举里根本没有 King 相关条目。
3. **本存档当前没有加冕的国王**：309 条 profile 的 flag 全部 `0x01`，无一条带 `King=4`。
3. **性别、年龄、外观全在 25 字节里**：**age 在 off 24（0..25 的独立整数）**；**sex 在 off 23（1=Female / 2=Male）**；**肤色在 off 12（0=Caucasian 白 / 1=Asia 黄 / 2=Latin / 3=Brown）**；发色 off 11 + off 17；eyes/eyeBrow/mouth/hair/beard 在 off 13/14/15/16/18。off 1..3 是出生日编码，与 age 必须同改（§3.3：一年 30 天，`age` 是权威值，不是由 u24 算出来的）。
5. **婚姻状态 = 所在 FamilyData.dicPersons 里有没有 `Partner(1)` 项**，不在 ProfileData 上；`cacheHusband` 与 `IsMarriaged` 不是同一回事（详见 §5）。
6. **专属台词只能按"关系/身份"挂，不能按角色 id 或名字挂**：用 `MsgPerson.Monarch=35` 这一池即可让任何加冕者说话。

---

## 1. 新开局的初始国王怎么生成

### 1.1 名字来源：随机 NameData.Name【已证实】

`NameData`（TypeDefIndex 1048，dump.cs:55146）：

```csharp
private static List<string> listNameMale;    // 0x0
private static List<string> listNameFemale;  // 0x8
private static List<string> listNameFamily;  // 0x10
private static List<string> loader(string fname);        // RVA 0x19E1E60
public  static string Name(ProfileData.SexCode sex);     // RVA 0x19D20F4
private static string rndName(List<string> list);        // RVA 0x19E1FA8
public  static string Family { get; }                    // get_Family 0x19D2294
```

- `rndName`（0x19E1FA8 起）先调 `UnityEngine.Random.Range` 取索引，再走 `List<string>` 索引器。**纯随机抽取，无固定分支。**
- 数据源是三个 Unity TextAsset，纯文本、每行一个名字（`NameMale_en.txt` 4130 B / `NameFemale_en.txt` 4293 B / `NameFamily_en.txt` 4398 B）：
  ```
  Serge
  Edward
  Ruben
  ...
  ```
- metadata 里的 `"$(KingName)$"`（VA 0x396c358）、`"was crowned"`（0x39891f8）、`"king"`（0x3984268）、`"royal"`（0x39869a0）、`"notroyal"`（0x3985700）都是 **UI 占位符 / 判定字符串**，不是初始国王名的来源。

> 改名结论：新开局后，国王名已作为字符串写进 `sv1_ws_profiles`，**改名字表对已有存档无效**；要叫「杨广」必须改存档（§3.3 的硬约束：UTF-8 字节数必须与原槽位一致，或用 `ProfileData.Rename` 0x19D0DD0 的重排逻辑）。

### 1.2 国王实体：`KingData`【结构已证实，生成时机部分未证实】

`KingData`（TypeDefIndex 764，dump.cs:39704）：

```csharp
private static readonly int initGeneration;  // 0x0
private bool         coronation;             // 0x10
private GenerationData kingData;             // 0x18
private HumanData    cachePrince;            // 0x20
public bool IsCoronation { get; }            // 0x1941D14
public uint KingId       { get; }            // 0x1941D1C
public bool IsKingPC     { get; }            // 0x1941D30
public HumanData Data    { get; }            // 0x1941D5C
public int  Generation   { get; }            // 0x1941D70
public uint SuccessorId  { get; }            // 0x1941D88
public uint BeforeId     { get; }            // 0x1941DA0
public uint FirstKingId  { get; }            // 0x1941DB8
public HumanData PrinceData { get; }         // 0x1941DCC
public bool KillKing(HumanData dead);        // 0x1941EBC
public HumanData CreateNewKing();            // 0x194249C
private void setupNewKing(...);              // 0x1941FA0
private void newRoyal(HumanData man);        // 0x19429D8
public void CompleteCoronation();            // 0x1942D54
public void RecoverPrince();                 // 0x1942EE4
public void RecoverKing3644();               // 0x19434D4
public void Save5792();                      // 0x1943748
public static void Load5792();               // 0x19437A8
```

`GenerationData`（TypeDefIndex 763，dump.cs:39644）：

```csharp
private HumanData    currentHuman;       // 0x10
private int          currentGeneration;  // 0x18
private List<uint>   listHistorys;       // 0x20
public uint CurrentId      { get; }      // 0x19412E8
public HumanData CurrentHuman { get; }   // 0x1941308
public uint BeforeHumanId  { get; }      // 0x1941438
public int  CurrentIndex   { get; }      // 0x1941440
public void NextGeneration(bool onlyScan); // 0x19415FC
private void scanGeneration();           // 0x1941650
private void findNext();                 // 0x194191C
private void NewGeneration();            // 0x1941B18
public void Save5792();                  // 0x1941C0C（真头 0x1941BDC）
```

- `GenerationData.Save5792` **真头已核对在 0x1941BDC**（script.json 标的 0x1941C0C 偏了 0x30）：读标志位后向 `[x8+0xb8]` 写入常量 **`w9 = 6`**，随后 `ret`。该常量疑为存档版本号，**【未证实】**。
- `CreateNewKing`（0x194249C）取 `WorldData` 单例 `[x0+0xb8]` → `[x8+0x60]` → 判空 → 调 0x19F0654 / 0x19F066C 遍历候选、0x1959EF0 过滤、0x199B620 取 id 与当前 id 比对，命中计数，循环上限在 `w25`。**这是"从一个候选池里扫描挑新国王"的循环，不是读固定表。**
- **【未证实】**"新世界第一次加冕"到底由哪个入口触发（`setupNewKing` / `CompleteCoronation` 的真实函数头因符号错配未能干净反汇编出）。
- **【高置信】** 初始国王 = 新世界第一次加冕时用"男名 + 随机外观 + 随机生日"生成的普通 `HumanData`，只是额外挂了 `coronation=1` 与 `KingData.kingData` 世代记录。

---

## 2. 存档里怎么判定谁是国王

### 2.1 `WorldData.SaveFileName` 里根本没有 King 条目【已证实】

`WorldData.SaveFileName`（dump.cs:64486，TypeDefIndex 1198）：

| 枚举 | 值 | 文件 |
|---|---|---|
| Achievement | 0 | sv1_ws_achievement |
| Animals0..5 | 1..6 | sv1_ws_animals0-5 |
| Battle | 7 | sv1_ws_battle |
| Companys | 8 | sv1_ws_companys |
| Delete | 9 | sv1_ws_delete |
| Drops | 10 | sv1_ws_drops |
| **Familys** | 11 | sv1_ws_familys |
| Farms | 12 | sv1_ws_farms |
| FreeHouse | 13 | sv1_ws_freehouse |
| **Info** | 14 | sv1_ws_info |
| Party | 15 | sv1_ws_party |
| **Profiles** | 16 | sv1_ws_profiles |
| Telop | 17 | sv1_ws_telop |
| Tutorial | 18 | sv1_ws_tutorial |
| Version | 19 | sv1_ws_version |
| Weather | 20 | sv1_ws_weather |
| **Wedding** | 21 | sv1_ws_wedding |
| History | 22 | sv1_ws_history（本存档解压后仅 2 B `00 00`，0 条） |
| Actives | 23 | sv1_ws_actives |

**关键结构事实**：`WorldData` 静态字段表里**没有** `KingData` 字段；`KingData` 挂在 **`SaveInfo`** 上：

```
dump.cs:57052  public class SaveInfo
dump.cs:57063      protected KingData kingData;   // 0x38   ← 国王在这里
dump.cs:57111      public KingData KingData { get; }
```

⇒ **谁是国王存在 `sv1_ws_info`（SaveFileName.Info=14）**，具体是 `SaveInfo.kingData @0x38` → `KingData.kingData @0x18`（GenerationData）→ `currentHuman @0x10`（HumanData）→ `animalId @0x30`（HumanData 继承自 AnimalData）。

### 2.2 `SaveInfo` 完整字段偏移（dump.cs:57052 起）

| 偏移 | 类型 | 字段 |
|---|---|---|
| 0x10 | bool | changed |
| 0x18 | GameDate | Today |
| 0x20 | uint | playerId |
| 0x24 | uint | nextAnimalId |
| 0x28 | uint | nextFamilyId |
| 0x2C | uint | nextBattleId |
| 0x30 | SexCode | sex |
| 0x34 | uint | todayBaby |
| **0x38** | **KingData** | **kingData** ← 国王 |
| 0x40 / 0x48 / 0x50 / 0x58 / 0x60 | ManID | tomorrowDead / todayDead / todayBorn / todayStudentPC / todayAdultPC |
| 0x68 | ParliamentaryData | parliamentaryData |
| 0x70 | Dictionary | weathers |
| 0x78 / 0x7C | int | pcCharmBoostValue / pcCharmBoostTime |
| 0x80 / 0x88 / 0x90 / 0x94 | uint / BeastData / int / float | beastId / beastData / beastCount / leftBoost |
| 0x98 | DateTime | lastAutoBoostTime |
| 0xA0 / 0xA4 / 0xA8 | saveZone / int / int | saveFolderId / playerGenerations / limitSlot（LimitSlotMax=10） |
| 0xB0 | List\<SlotPC\> | saveSlotPC |
| 0xB8 | EnaConData | enaconDat |
| 0xC0 / 0xC4 / 0xC8 / 0xCC / 0xD0 | int / int / int / uint / DragonData | countryLevelOld / countryLevelNow / countryExp / dragonId / dragonData |
| 0xD8 / 0xE0 / 0xE8 / 0xF0 / 0xF8 | — | PlayersLog / coordinate / obtainedList / listEvents / endEatEvent |
| 0x100 / 0x108 / 0x110 / 0x118 | — | pregnancyToday / freeMonsterId / limitWarpArea（默认 3，最大 20）/ saveWarpAreas |

`SaveInfo.Save5792` RVA 0x19F74C0、`Load5792` RVA 0x19F7B14（dump.cs:57659/57662）。**这两个地址都已落到函数体中段，且用 `stp x30` / `sub sp` 两种特征向前扫 0x180 字节都找不到函数头**（在 0x19E0000–0x1A10000 区间搜 `0xA9BF7BFD` 共 0 命中）→ **【未证实】**，这几段逻辑无法从 .so 静态确认。

### 2.3 本存档的 `sv1_ws_info` 实测（解压 308 B）

```
0000: 01 1f 68 01 | 0000'0000 | 61 42 00 00 | 00 00 4c 01 | 0000'1e01 | 0000'0000 | 02 00 00 00
0020: 00 01 01 01 | 06 00 00 00 | 01 01 00 18 00 00 00 | 00 00 00 01 | d0 00 00 00 | 01 d0 00 00
0040: 00 01 a0 86 01 00 05 03 | 00 00 00 03 00 00 00 | 03 03 01 02 03 00 | 0b 00 00 00 0b 00 00 00
...
00a8: 01 13 "0001-01-01 00:00:00" ...
00cc: 00 00 04 01 | 40 01 00 00 | 42 00 00 00 | 01 a3 00 00 | 00 36 00 00 | 00 01 a7 00 ...
0120: 17 00 00 00 00 00 00 00 00 36 01 00 00 00 00 00 00 00 00 00
```

全档扫描"可能表示人物 id 的 u32（24..332）"得到的关键位置：

| 偏移 | 值 | 上下文 |
|---|---|---|
| **0x28** | **24**（Gerda） | `06 00 00 00 \| 01 01 00 \| 18 00 00 00` |
| 0x30 | 208（Emilio） | `01 d0 00 00 00` |
| 0x35 | 208（Emilio） | `01 d0 00 00 00`（紧接上一个） |
| 0xCC | 320 | `04 01 40 01 00 00` |
| 0xF1 | 321（Debora） | `04 01 41 01 00 00` |
| 0x0D | 332、0x11 | 286 |

**【高置信】**：0x28 处的 `18 00 00 00`=24 是 **KingId 槽**（24=Gerda，本世界唯一带"王室第一人"特征的记录：她的 family 成员 key=Partner 出现在 family1/family3 两处）；0x30/0x35 的 208 是**继任者/王子**槽。
**【未证实】**：`03 03 01 02 03 00` 这类 `[count][idx...]` 段与后半 `[01][u32 id][u32 值]` 键值对（`01 40 01 00 00|42 00 00 00`、`01 a3 00 00 00|36 00 00 00`、`01 a7 00 00 00|3d 00 00 00`、`01 b4 00 00 00|41 00 00 00`…）的确切语义。值 0x36–0x42（54–66）落在人物 id 区间内侧，疑为"某人与某人的关系/好感/职务"表。
`sv1_ws_info_org` 是 364 B 未压缩版，与压缩版差 56 B（版本差异，未深究）。

### 2.4 `ProfileData` 字段偏移与类型（dump.cs:49594，TypeDefIndex 987）

| 偏移 | 类型 | 字段 |
|---|---|---|
| 0x10 | byte | flgP |
| 0x14 | uint | profileId |
| 0x18 | string | name |
| 0x20 | GameDate | birthDay |
| 0x28 | GameDate | deathDay |
| 0x30 | AppearanceData | appearanceData |
| 0x38 | HumanData | lifeData |
| 0x40 | uint | currentFamilyId |
| 0x48 | FamilyData | curFamilyData |
| 0x50 | uint | oldFamilyId |
| 0x58 | FamilyData | oldFamilyData |
| **0x60** | **byte** | **sex**（SexCode：Unknown=0 / Female=1 / Male=2） |
| **0x61** | **byte** | **age** |
| 0x64 | uint | cacheHusband ← 只是缓存，见 §5 |
| 0x68 | List\<uint\> | cacheParents |
| 0x70 / 0x78 | ProfileData | cacheMother / cacheFather |

`ProfileData.profFlags`（dump.cs:49545，`[Flags]`）：`None=0 / Admission=1 / Graduation=2 / King=4 / HeroSlot=8`。
`ProfileData.SexCode`：`Unknown=0 / Female=1 / Male=2`。
`FaceBaseType`：`Normal=0 / Baby=1 / Elder=2`；`FacePartsType`：`AdultMale=0 / AdultFemale=1 / ChildMale=2 / ChildFemale=3 / Baby=4`。

`AppearanceData`（TypeDefIndex 948，dump.cs:47927，8 字节紧凑结构）：

| 偏移 | 类型 | 字段 | 常量上限 |
|---|---|---|---|
| 0x10 | byte | orgHairCol | `HairColorWhite=0, Brown=1, Black=2, Red=3, Gold=4, Ash=5, Orange=6, Blue=7, Green=8`；`CountOriginalHairColor=9`、`CountCustomHairColor=32` |
| 0x11 | byte | skinCol | `SkinColors: Caucasian=0 / Asia=1 / Latin=2 / Brown=3` |
| 0x12 | byte | eyes | `CountEyes=30` |
| 0x13 | byte | eyeBrow | `CountEyeBrow=30` |
| 0x14 | byte | mouth | `CountMouth=30` |
| 0x15 | byte | hair | `HairType HairStyle01..16 = 0..15`；`CountHairStyle=8` |
| 0x16 | byte | hairCol | 0..8 |
| 0x17 | byte | beard | `BeardType: None=0 / Mustache=1 / Chin=2 / Full=3` |

构造与序列化方法：`.ctor(byte hairCol, byte skinCol, byte eyes, byte eyeBrow, byte mouth, byte hair, byte nowHairC, byte iniBeard)` 0x198FFDC；`CreateRandom` 0x199047C；`ToJsonString` 0x1990668；`.ctor(string str)` 0x19908B4；`Save5792` 0x1990EB0；`Load5792` 0x1990F1C。

`GameDate`（TypeDefIndex 881，dump.cs:43820）：`begYear=192`、`begDay=2`、`begHour=0`、`begKoku=0`；`float runSec //0x10`、**`int dateValue //0x14`**；`skipGapTime=0.2`。

### 2.5 可直接调用的改数据方法

| 方法 | RVA |
|---|---|
| `ProfileData.Rename` | 0x19D0DD0 |
| `ProfileData.BabyRename` | 0x19D0E40 |
| `ProfileData.ChangeEyes` | 0x19D1024 |
| `ProfileData.ChangeHairStyle` | 0x19D1224 |
| `ProfileData.ChangeBeard` | 0x19D12A4 |
| `ProfileData.ChangeSex` | 0x19D14E8 |
| `ProfileData.get_Age` | 0x19D1578 |
| `ProfileData.get_IsYoung` | 0x19D1658 |
| `ProfileData.SetMarriage(uint newFamilyId)` | 0x19D26D4 |
| `ProfileData.SetNewPerson(uint newFamilyId)` | 0x19D2600 |
| `ProfileData.SetBackPerson()` | 0x19D2664 |
| `ProfileData.Kill(GameDate)` | 0x19D2794 |
| `ProfileData.ImpersonationBirthdayForNewWorld(int years, bool rndDay)` | 0x19D2830 |
| `ProfileData.BackInitAge(int backAge)` | 0x19D2AB4 |
| `ProfileData.IncDay` | 0x19D2BC8 |
| `ProfileData.get_ModelBaseName` | 0x19D2EBC |
| `ProfileData.Save5792` | **0x19D3084**（真头；script.json 标的 0x19D3114 是函数体中段） |
| `ProfileData.Load5792` | 0x19D31D4 |
| `BloodData.IsKingOrSuccessor(HumanData)` | 0x1993EB0 |

### 2.6 本存档的国王判定结论

**这份存档里没有加冕的国王。**【已证实】

- 309 条 profile 的 flag **全部 = `0x01`**（仅 Admission 位），**无一条带 `King=4`**。
- `sv1_ws_history` 解压后仅 `00 00`（0 条），`sv1_ws_wedding` 解压后仅 2 B → 该世界尚未加冕、尚无婚礼。
- 若要造一个国王：`info` @0x28 写其 id + profiles 把该条 flag 改 `0x05`。**两者必须同时改**，只改一处的一致性行为【未证实】。

---

## 3. `sv1_ws_profiles` 结构与 25 字节逐字段

### 3.1 容器结构【已证实：解析到字节尾正好对齐】

```
[u32 count][9B 头]
重复 count 次：
  [u32 id][u8 0x01][u8 nameLen][name: nameLen * UTF-8]
  [25B 数据]
  [9B 尾部：u32 nextId | u32 familyId | u8 0x03]   ← 仅非末条有，末条无
```

实测：`count = 0x135 = 309`，头 9 B = `00 00 00 18 00 00 00 03 00 00`（疑为 `[pad][u32 firstId=24][u32 3]`，**【未证实】**）。309 条解析完正好落在 14,254 B = 解压后文件长度 → 结构完全正确。
id 连续 **24 → 332**（0..23 为保留槽，**【未证实】**用途）。flag 全 = `0x01`。

### 3.2 25 字节逐字段（309 条实测统计）

| off | 类型 | 含义 | 实证 |
|---|---|---|---|
| 0 | u8 | 恒 `0x01` | 309/309。**【未证实】**（疑 flgP 低位/记录有效位） |
| **1..3** | **u24 LE** | **出生日编码：`k = (u24-15)//16`（`u24%16==15`，185 条）或 `k = u24//16`（`u24%16==0`，124 条，全部 id≥152）** | **见 §3.3 完整推导，【已证实】** |
| 4..9 | 6×u8 | 全 `00` | 309/309 = 0。**【未证实】**（deathDay 空位/保留） |
| 10 | u8 | 恒 `0x01` | 309/309。**【未证实】** |
| **11** | **u8** | **发色 `orgHairCol`（基础发色）** | 值域 **1..8**，分布 37/36/49/47/27/35/42/36；**与 off 17 强相关：凡 off17≠0 的 177 条，off11 无一例外 == off17** →**【已证实】** |
| **12** | **u8** | **肤色 `skinCol`** | 值域 **0..3**，分布 **68/84/83/74**，**恰好 = `SkinColors` 的 4 个值** →**【已证实】** |
| 13 | u8 | **eyes** | 值域 0..29 = CountEyes 30，**【高置信】** |
| 14 | u8 | **eyeBrow** | 值域 0..29 = CountEyeBrow 30，**【高置信】** |
| 15 | u8 | **mouth** | 值域 0..29 = CountMouth 30，**【高置信】** |
| 16 | u8 | **hair**（发型） | 值域 0..7，**【高置信】** |
| 17 | u8 | **当前发色覆盖值**（`hairCol`，非 0 时覆盖 off 11） | 值域 0..8，0 占 132 条；**非 0 时恒等于 off11** →**【已证实】** |
| 18 | u8 | **beard** | 值域 0..3，0 占 297 条 = BeardType None，**【高置信】** |
| 19 | u8 | 未证实 | 值域 0..254，0 占 108 条 |
| 20 | u8 | 未证实（疑 bool） | 0/1，0 占 302 |
| 21..22 | u8×2 | 恒 0 | 309/309 |
| **23** | **u8** | **sex（1=Female / 2=Male）** | **恒 1 或 2，1→155、2→154；309 条无一例外且与 `ProfileData.SexCode` 完全吻合 →【已证实】** |
| **24** | **u8** | **age（年龄）** | 值域 0..25。**`age` 是权威整数字段，不能用 `floor(k/30)` 反推**（309 条 0 匹配，见 §3.3）；与 off 1..3 的出生日编码必须同步改 →**【已证实】** |

### 3.3 年龄怎么存 —— 决定性推导【已证实（分段）】

**年龄存两处，必须同改：**

```
u24(off 1..3) = 出生日编码（见下方两族格式）
age(off 24)   = 整数年龄（权威值，独立存的）
约定：一年 = 30 天；age = 整数岁数，生日当天 +1
```

**关于 u24 的两族格式（本轮新发现，必须知道）**：309 条里 `u24 % 16` **不是恒为 15**：

| `u24 % 16` | 条数 | 解码式 | 覆盖 id |
|---|---|---|---|
| **0xF (15)** | **185** | `k = (u24 − 15) // 16` | 主要 id < 152 及部分后期 |
| **0x0** | **124** | `k = u24 // 16`（低 4 位直接为 0） | 全部集中在 **id ≥ 152** |

两族在语义上连续（都是 k 落在 167..181 年区间、age 10..11 岁无缝衔接），**说明低 4 位只是打包占位差异，解出 k 的方式要按 `%16` 分支**。若一律用 `(u24−15)//16`，那 124 条会偏 −15/16 ≈ −0.94 天，误差不足一天，**对年龄换算无实质影响**——这也正是早先"`%16==15` 恒成立"判断错误却没暴露的原因。

**年龄换算（两条独立拟合互相印证）**：

| 样本 | 条数 | 拟合式 | 每年天数 |
|---|---|---|---|
| `%16==15` 组 | 185 | `u24 = 91885.60 − 476.49×age` | **29.78** |
| `%16==0` 组 | 124 | `u24 = 92013.30 − 489.41×age` | **30.59** |
| **合计** | **309** | `u24 = 91964.40 − 480.58×age` | **30.04** |

⇒ **一年 = 30 天**，两个子集各自拟合都落在此值附近（29.78 / 30.59），不是单点巧合。

**为什么 `age` 不能由 k 算出**：`age = floor(k/30)` **309 条 0 匹配**（已验证）。因为 k 是"该人物出生日到基准的天数偏移"，不同年龄的人基准点不同（= 当前日 − 其出生日），而 **`age` 是独立存的权威整数**。写档时**以 `age` 为最终依据**，`u24` 只需让 `birthDay` 与之一致。

**逐条验证样例**：

| 人物 | u24 | mod16 | k | age 字段 | t = k + age×30 |
|---|---|---|---|---|---|
| Gerda (id24) | 83295 | 15 | 5205 | 18 | 5745 |
| Darren (id25) | 83311 | 15 | 5206 | 18 | 5746 |
| Penelope (id28) | 83071 | 15 | 5191 | 19 | 5761 |
| Alisa (id152) | 86832 | **0** | **5427** | 11 | 5757 |
| Lionel (id153) | 86864 | **0** | **5429** | 11 | 5759 |
| Melissa (id154) | 86880 | **0** | **5430** | 11 | 5760 |

`t = k + age×30` 全部收敛在 **5732..5783**（宽度 51 ≈ 1.7 年，即各人生日在年内的不同位置），**中位数 5746 ≈ 当前游戏日**。若每年天数取 365，该宽度会散成 8362 → 完全排除。

**与 `GameDate.begYear = 192` 对上**：`5746 / 30 ≈ 191.5`，落在纪元年 192 附近。

**给「杨广」设 5 岁的实际数值**（本存档 `t ≈ 5746`，建议写在 `%16==0` 族以简化）：

| 目标 age | k = t - age*30 | `%16==15` 族 u24 = k*16+15 -> 3 字节 | `%16==0` 族 u24 = k*16 -> 3 字节 |
|---|---|---|---|
| **5** | 5596 | 0x15DCF -> **`cf 5d 01`** | 0x15DC0 -> **`c0 5d 01`** |
| 6 | 5566 | 0x15BDF -> `df 5b 01` | 0x15BD0 -> `d0 5b 01` |
| 15 | 5296 | 0x14B1F -> `1f 4b 01` | 0x14B10 -> `10 4b 01` |
| 18 | 5206 | 0x1456F -> `6f 45 01` | 0x14560 -> `60 45 01` |

（这四行已用 `_hark\_final2.py` 实测打印校对；`k=5206 -> 0x1456F -> 6f 45 01` 与真实记录 Darren id25 的 `off1..3 = 6f 45 01` 完全一致，构成端到端验证。**先前版本写的 `4f 5d 01` 是算错的，已作废。**）

**⚠ 关于「游戏里 6 岁 ≈ 成年 18 岁」**：这是**观感/表现层**的换算，**存档里没有这层**——存档只认 `age`（0..25）。想要"看起来 5 岁"就写 `age=5`。**【未证实】** 这个 3 倍率的确切出处：`GameDate.AdmissionDay` / `GraduationDay` / `BaseLimitAge` 是属性（getter 0x196D1E8 / 0x196D1F0 / 0x196D1F8），常量值在静态构造或 `c_*` 表里，dump.cs 读不到（只找到 flag 位 `Admission=1 / Graduation=2`，dump.cs:49554/49555）。

### 3.4 性别与肤色：取值范围与"浅肤色"取值【已证实】

#### 性别 —— `off 23`

| 值 | 含义 | 本存档条数 |
|---|---|---|
| **1** | **Female**（`ProfileData.SexCode.Female = 1`） | 155 |
| **2** | **Male**（`ProfileData.SexCode.Male = 2`） | 154 |
| 0 | `Unknown`（本存档 0 条，不用） | — |

- 309 条**无一例外**只取 1 或 2，与 `ProfileData.SexCode` 枚举（dump.cs 区：`Unknown=0 / Female=1 / Male=2`）完全吻合。
- 运行时 setter：`ProfileData.ChangeSex(ProfileData.SexCode sex)` **RVA 0x19D14E8**（dump.cs:49842）。
- **要男性国王 → 写 `0x02`。**

#### 肤色 —— `off 12`

`AppearanceData.SkinColors`（dump.cs:47865，TypeDefIndex 944）**只有 4 个值**：

| 值 | 枚举名 | 本存档条数 | 说明 |
|---|---|---|---|
| **0** | **`Caucasian`** | **68** | **白皮（浅肤色）← 符合要求** |
| **1** | **`Asia`** | **84** | **黄皮（浅肤色）← 符合要求** |
| 2 | `Latin` | 83 | 偏棕（中等） |
| 3 | `Brown` | 74 | 深棕（深肤色） |

- **`off 12` 取 0..3 恰好四个值、四组条数 68/84/83/74 近似均分**，与枚举定义完全一一对应 ⇒ **`off 12 = skinCol`【已证实】**。
- **要"黄皮或白皮" → 写 `0x01`（Asia 黄皮）或 `0x00`（Caucasian 白皮）。** 两者都在浅肤色区间。
- `AppearanceData.CountSkinColor` 是 `static readonly int`（dump.cs:47943），值需运行时读；但存档实测证明**有效值域就是 0..3**。
- 相关方法：`AppearanceData.get_SkinColor` RVA 0x198F588、`set_SkinColor` RVA 0x198F590、`GetSkinColorText` 0x198F4E8、`GetBodyTextureName(string, SkinColors)` dump.cs:18978、`GetFaceBaseTextureName(FaceBaseType, SkinColors)` dump.cs:19442、`ProfileData.ChangeSkinColor(SkinColors)` RVA **0x19D0FA4**（dump.cs:49800）、`HumanData.ChangeSkinColor` RVA 0x19A5330（dump.cs:52481）、`AppearanceData.CreateRandom()` RVA 0x199047C。
- **⚠ 那两个 setter 的反汇编地址（0x198F588/0x198F590）实际落在别的函数中段**（与全项目 script.json 偏移问题一致），字节写入偏移**不能**由它直接确认；上面的字段归属由**存档 309 条统计**确立，证据强度高于反汇编。

#### 发色 —— `off 11` 与 `off 17`（易混淆，特此说明）

上一版把 `off 11` 当成 skinCol，**是错的**。证据：

- `off 11` 值域 **1..8**，共 8 个值 —— `SkinColors` 只有 4 个值，对不上。
- `off 11` 与 `off 17` 强绑定：**在 `off 17 ≠ 0` 的 177 条记录里，`off 11` 无一例外等于 `off 17`**（`all(r.off11 == r.off17 for r in recs if r.off17 != 0)` → `True`）。
- ⇒ **`off 11` = 基础发色 `orgHairCol`**（`HairColorWhite=0/Brown=1/Black=2/Red=3/Gold=4/Ash=5/Orange=6/Blue=7/Green=8`，`CountOriginalHairColor=9`）；**`off 17` = 当前发色覆盖值**，为 0 表示不覆盖。
- **要黑发 → `off 17 = 2`（Black），`off 11` 同步写 2。**

#### 与年龄的交叉验证（off 12 不是 FacePartsType）

早先怀疑 `off 12` 是 `FacePartsType`（成年/儿童标记），已被否决——它与 age 完全无相关：

```
age= 0: {off12=0:3, 2:4, 3:2}    age= 5: {0:3, 1:1, 2:4, 3:4}
age=18: {0:2, 1:5, 2:4, 3:5}    age=24: {0:5, 1:5, 2:6, 3:6}
```

成年与否**只由 `age` 决定**（0..25，`age ≥ 18` 为成年），`FaceBaseType` / `FacePartsType` 是运行时由 age 派生的，不单独存。

样例（原文，括号内为**最新归属**；旧标注"skinCol=8"是错的，见上）：
```
24 Gerda    01 5f 45 01 00 00 00 00 00 00 01 08 02 15 10 1a 03 00 00 01 00 00 00 01 12
25 Darren   01 6f 45 01 00 00 00 00 00 00 01 06 02 0c 10 0f 05 00 00 02 00 00 00 02 12
26 Ray      01 2f 46 01 00 00 00 00 00 00 01 04 01 0d 14 0c 07 00 00 00 00 00 00 02 12
208 Emilio  01 10 5a 01 00 00 00 00 00 00 01 06 02 0a 12 12 01 06 00 00 00 00 00 02 07
```
Gerda 逐字段：`off0=01` / `off1..3=01 5f 45`(u24=83295, k=5205) / `off4..9=0` / `off10=01` / **`off11=08` 基础发色** / **`off12=02` 肤色 Latin** / `off13=15` eyes=21 / `off14=10` eyeBrow=16 / `off15=1a` mouth=26 / `off16=03` hair / **`off17=00` 发色覆盖=不覆盖** / `off18=00` beard=None / `off19=01` / `off20..22=0` / **`off23=01` sex=Female** / **`off24=12` age=18**。
Darren 逐字段：`off11=06` / `off12=02` 肤色 Latin / `off13=0c` / `off14=10` / `off15=0f` / `off16=05` / `off17=00` / `off18=00` / `off23=02` **Male** / `off24=12` age=18。

### 3.5 关于 `ProfileData.Save5792` 反汇编为什么帮不上

- `script.json` 给 `ProfileData.Save5792` 标 0x19D3114，**真头在 0x19D3084**（`sub sp, sp, #0x70`）。
- 函数体调用链：0x19851D8、0x199F4F8、0x199EAC4、0x19A720C、0x1970FE4、0x19F92E0、0x19A15EC、0x1C85EC8、0x19AB5F0/0x19AB760、0x19B8F94、0x19850D4、0x19AB83C、0x19853CC。
- **但这些被调地址全部落在别的方法中段**（0x19A720C 在 `HumanData$$AddItem`+880、0x1970FE4 在 `HistoryInfo$$.ctor`+248、0x19F92E0 在 `SlotPC$$CreateTempHumanData`+104、0x19B8F94 在 `HumanData$$thinkDungeonReady`+180、0x19850D4 在 `AnimalData$$AddWeapons`+1152）。metadata 里 `"Save5792"` 只出现 3 次（off 0x876e3 / 0xb4c99 / 0xb4d0a）而 dump.cs 有 4 个类各有 `Save5792` → **dumper 类归属错配，符号不可信**。
- ⇒ **25 字节的字段归属只能靠存档统计 + 字段语义自洽来定**，上表已按此标注置信度。想拿到干净的写入顺序，必须换 Il2CppDumper 版本重导（`script.json` 需 `--force-binary-search` 或改 `config.json` 的 `DumpMethod`），**本条列为未证实/待办**。

---

## 4. 婚姻 / 配偶：字段在哪，怎么改成未婚

### 4.1 婚姻状态的真正存储位置【已证实（反汇编）】

`ProfileData` 上的 `cacheHusband @0x64`、`get_IsMarriaged` 0x19D178C、`get_HusbandID` 0x19D17B4 —— 反汇编（真身 @0x19D1798 起）：

```
cbz  x19, ...                                  ; 空判
x0=x19, w1=#0x26c, x2=0, bl 0x19A8488          ; 查 flag 0x26c
非零 → 0x19D17F0
否则 x0=x19, w1=#0x26d, bl 0x19A8488           ; 再查 flag 0x26d
仍为零 → return 0
0x19D17F0: 取 WorldData 单例, bl 0x18EB968 得 id
            x0=x19, w1=w0, bl 0x19B7910
            非零 → return 1, 否则 return 0
```

- `sub_19A8488(x0=human, w1=flagId)`：`cbz w1 → 0`；否则 `sub_19A7034` → 读 `[sp+0xc]` → `cmp #0, cset gt`。即 **"某 flag 表里 id 为 flagId 的条目值 > 0"**。
- `sub_19A7034(x0, x1=flagId, x8=out 24B)`：取容器 `[x0+0x18]` 条目数、基址 `x0+0x20`，**每条 0x18 字节、首字段 u32 为 key**，逐条比对；未命中调 0x196FC28 取默认值。
- `sub_19B7910(x0=human, w1=id)`：0x18E71C4 判 id 有效性 → 0x197C17C 取容器 → 0x18E71B8 判存在 → 读 `[x19+0x190]`（`HumanData.hcg`）分支（<=3 / ==4 / ==5 / ==6 各有不同路径）。

⇒ **`IsMarriaged` / `HusbandID` 不是直接读 `cacheHusband`**，而是：**先看 HumanData 的 flag `0x26c` 或 `0x26d` 是否存在，再从 WorldData 某容器按 id 查配偶。**

### 4.2 家族侧：`FamilyData` 与 `FamilyIndex`【已证实】

`FamilyData.FamilyIndex`（dump.cs:48750，TypeDefIndex 963）—— **这是拆婚的关键枚举**：

```
Leader = 0;  Partner = 1;  Successor = 2;
Child2nd = 3; Child3rd = 4; Child4th = 5; Child5th = 6; Child6th = 7; Limit = 8;
```

`FamilyData`（dump.cs:48767，TypeDefIndex 964）：

```csharp
private uint familyId;                                  // 0x10
private string name;                                    // 0x18
private GameDate marriageDay;                           // 0x20
private ushort homeArea;                                // 0x28
private Dictionary<FamilyData.FamilyIndex, uint> dicPersons; // 0x30
private GameDate lastAccess;                            // 0x38
public uint Leader { get; }        // 0x1999C50
public uint Husband { get; }       // 0x1999C58
public int  CountChildren { get; } // 0x1999C60
public void AddChild(...);         // 0x199994C
public void ChangeLeader(uint);    // 0x199A13C
public void ChangeHome(...);       // 0x1999AA4
public bool Find(uint who);        // 0x1994B64
public void Save5792();            // 0x199A570
public void Load5792();            // 0x199A77C
```

⇒ **婚姻状态 = 该人所在 `FamilyData.dicPersons` 里是否存在 `Partner(1)` 项**，且**夫妻双方各自家族的 `dicPersons` 里都各有一份指向对方的 `Partner(1)`**。

### 4.3 `sv1_ws_familys` 实测结构【高置信，有内部矛盾】

- 未压缩 `sv1_ws_familys_org` 10,681 B（压缩版 `sv1_ws_familys` 10,617 B，count 不同：org=288、压缩=286，版本差异）。
- 头 `[u32 count]`；每条记录头同样是 `[u32 familyId][u8 0x01][u8 len][ASCII 名字]`，扫描得到正好 288 条。
- body 长度实测集合：**18(187 条) / 31(23) / 36(42) / 41(27) / 46(7) / 51(2)** —— 全部是 `18+5k` 与 `31+5k` 两类。
- 短体（18 B）实证：
  ```
  family1 (Gainsborough) body = 00 | 05 00 | 01 | 00 18 00 00 | 01 | 9f 51 01 00 00 00 00 00
                               pad  homeArea=5  cnt  id=24       key  GameDate 8B
  family2 body              = 00 | 34 00 | 01 | 00 19 00 00 | 01 | 9f 51 01 00 00 00 00 00
                               pad  homeArea=52 cnt  id=25       key
  ```
- 长体（46 B）例（family3）：**以 GameDate 8B 开头** `01 9f 51 01 00 00 00 00`，再 `00 | 05 00 | 05 00 | 5×([u32 id][u8 key])` = 24→1, 25→2, 152→3, 178→4, 208→1，末尾再一个 GameDate 8B。
  **注意：长体以 GameDate 开头、短体以 GameDate 结尾 —— 字段顺序不一致。** 哪种对应 `marriageDay`(@0x20)、哪种对应 `lastAccess`(@0x38)，**【未证实】**。
- 统计：body 末 8 B 最常见 `9f 51 01 00 00 00 00 00`（135 条）；body 首 8 B 最常见 `01 9f 51 01 00 00 00 00`（57 条）。

**内部矛盾（必须标未证实）**：profiles 尾部解出 Gerda(id24) familyId=3、Darren(id25) familyId=4，但 family3 里同时出现 `24→Partner(1)` 和 `208→Partner(1)` 两个 key=1。`docs` 里 25 B 尾部 `[u32 familyId]` 与 familys 成员 key 的解析**至少有一处仍错**，**【未证实】**，改档前必须先用 `FamilyData.Find` / `Who(FamilyIndex)` 0x1999BB4 的运行时结果校正。

### 4.4 把某人改成未婚，必须同改的地方（清单）

以目标人物 `T`（国王）与配偶 `S` 为例：

| # | 位置 | 操作 | 依据 |
|---|---|---|---|
| 1 | `FamilyData.dicPersons` 中 T 所在家族 | 删除 `key=Partner(1)` 项 | FamilyIndex 枚举【已证实】 |
| 2 | `FamilyData.dicPersons` 中 S 所在家族 | 删除指向 T 的 `Partner(1)` 项 | 双方各存一份【高置信】 |
| 3 | `FamilyData.marriageDay @0x20` | 清空（GameDate 归零） | 字段语义【高置信】 |
| 4 | 有子女时：`Successor(2)` / `Child2nd..Child6th(3..7)` | 归属重挂到 `Leader(0)`，用 `AddChild` 0x199994C / `ChangeLeader` 0x199A13C | 枚举 + 方法存在【高置信】 |
| 5 | `ProfileData.cacheHusband @0x64` | 归零 | 缓存字段【高置信】 |
| 6 | **HumanData 的 flag `0x26c` / `0x26d`** | **必须清除**——`IsMarriaged` 实测走这里 | 反汇编【已证实】 |
| 7 | `sv1_ws_wedding` | 本存档本就空（2 B），无需处理 | 【已证实】 |
| 8 | 家族名若来自婚姻合并 | 决定是否保留 familyId / 名字 | 语义决策，【未证实】游戏反应 |

**只改 `cacheHusband` 不改 flag 0x26c/0x26d 是无效的**——下次存档/重载 `IsMarriaged` 仍为真。这是本轮最重要的修正。

**直接调用链（运行时，不碰字节）**：
```
ProfileData.SetBackPerson() 0x19D2664      // 从家族摘除（先备份）
ProfileData.SetNewPerson(uint) 0x19D2600   // 挂到新家族
FamilyData.ChangeLeader(uint) 0x199A13C    // 若有子女重挂
HumanData 清 flag 0x26c / 0x26d
```
`ProfileData.SetMarriage(uint newFamilyId)` 0x19D26D4 是"建立婚姻"方向，**不要**用它来拆婚。

---

## 5. 对话 / 台词按角色触发机制

### 5.1 `c_message` 表（MasterMessage）【已证实】

- 加密 Rijndael，`tools\rijndael_game.py` 的 `decrypt()` 无参可解。
- 明文是 .NET `BinaryFormatter`，顶层 `List<MasterMessage>`，元素数 **0x10BD = 4285**。
- `MasterMessage`（TypeDefIndex 1423，dump.cs:73701）：

```csharp
private uint id;                                  // 0x10
private string key;                               // 0x18
private ConvertMessage.MsgHour      hour;         // 0x20
private ConvertMessage.MsgWeather   weather;      // 0x24
private ConvertMessage.MsgSeason    season;       // 0x28
private ConvertMessage.MsgPerson    speaker;      // 0x2C  ← 谁在说
private ConvertMessage.MsgPerson    listener;     // 0x30  ← 对谁说
private ConvertMessage.MsgCharacter character;    // 0x34  ← 说者性格
private ConvertMessage.MsgCharacter youCharacter; // 0x38  ← 听者性格
private string msg;                               // 0x40
private string motion;                            // 0x48
private int    face;                              // 0x50
private string date;                              // 0x58
```

### 5.2 触发粒度：**关系/身份，不是角色 id、不是名字**【已证实】

`ConvertMessage.MsgPerson`（dump.cs:58716）是身份枚举，不是人物 id：

```
None=0 Male=1 Female=2 Boy=3 Girl=4 Adult=5 Child=6 Elder=7
... Father=21 Mother=22 MyChild=25 Son=28 Daughter=29 ...
Monarch=35  Prince=36  WarriorMajor=37  KnightMajor=39 ...
Butler1=48 Butler2=49 Baby=50 ... NPC=60 PC=61 Student=62 ...
Fiance=75 Hero=76 Dragoon=77 ... NewAdult=82
```

`ConvertMessage.MsgCharacter` 是性格偏移（Gentle± / Positive± / Diligent±），判定在 `testMsgPerson` / `testMsgPersonSibling` / `testMsgPersonParents` 里按关系算。

⇒ **台词系统按"关系/身份/性格/时段/天气/季节"匹配，完全不读角色 id 或名字。**

### 5.3 `TalkData`（脚本对话）

`TalkData.msgData : TextData`（TypeDefIndex 1146，dump.cs:61812）：

```csharp
private int msgId;          // 0x20
private int priority;       // 0x24
private List<int> chkId;    // 0x28   ← 前置条件节点，不是角色 id
private List<int> ansId;    // 0x30   ← 选项跳转
private int face;           // 0x38
private string motionName;  // 0x40
private int headIcon;       // 0x48
private int effectId;       // 0x4C
private int optionId;       // 0x50
private string msgText;     // 0x58
private string msgTitle;    // 0x60
```

`chkId` 是对话图的条件节点，不是角色 id。`TalkData` 另有内部类 `TalkData.faceData`（TypeDefIndex 1145，dump.cs:61798）。相关：`Think8299` / `Think8413` 负责 NPC 的选择逻辑。

### 5.4 能不能给"杨广"加专属台词

- **按名字匹配：不能。** 系统无名字匹配路径【已证实】。
- **按角色 id 匹配：不能（静态台词层）。**【已证实】
- **做成"国王专属台词"：可以，这是唯一可行路线。** 初始国王必然带 `Monarch(35)` 身份，把 `c_message` 里 `speaker==35` / `listener==35` 的条目改成你的文本，任何加冕者（含杨广）都会说。
  - 做法：`decrypt` → 在 `BinaryFormatter` 里定位 `speaker==35` 条目 → 改 `msg`（BinaryFormatter 字符串是 `[7-bit len prefix][UTF-8]`，长度变动需重算前缀）→ `encrypt` 回写。**重打包后是否触发完整性校验/签名拒绝【未证实】**。
  - 工作区已有抽取/回填管线：`02_extracted\contents\strings_all.tsv`、`c_en2zh.json`，优先复用。

---

## 6. 直接可用的改法（针对本存档，**仅示例，未执行**）

### 6.1 改名 + 变年轻 + 变帅 + 立为国王

```python
import zlib, struct

P = r"D:\workspaces\Elnea-Localization\01_raw_data\app_internal\files\Contents\sv1_ws_profiles"
buf = bytearray(zlib.decompress(open(P, "rb").read()))
count = struct.unpack_from("<I", buf, 0)[0]
off = 13

TARGET    = 34          # 待改造的 profile id（34 是 25 岁男性，起点最省事）
NEW_NAME  = "杨广"       # 6 字节 UTF-8，必须 == 原 nameLen 字节数，否则整块重排
KING_ID   = TARGET
TODAY_K   = 5746        # 当前游戏日基准（本存档实测 t=k+age*30 的中位数；现场反推法见下方说明）
NEW_AGE   = 5           # 目标年龄字段值（5 岁）

for k in range(count):
    rid  = struct.unpack_from("<I", buf, off)[0]
    flag = buf[off+4]; ln = buf[off+5]
    name_off = off + 6
    data_off = name_off + ln

    if rid == TARGET:
        nb = NEW_NAME.encode("utf-8")
        if len(nb) != ln:
            raise SystemExit(f"名字字节数 {len(nb)} != 原槽 {ln}，需整块重排（见 6.2）")
        buf[name_off:data_off] = nb
        buf[data_off + 11] = 2     # 基础发色 orgHairCol: 2 = Black（黑发）
        buf[data_off + 12] = 1     # 肤色 skinCol: 1 = Asia（黄皮）；要白皮写 0 = Caucasian
        buf[data_off + 13] = 12    # eyes
        buf[data_off + 14] = 14    # eyeBrow
        buf[data_off + 15] = 9     # mouth
        buf[data_off + 16] = 2     # hair（发型）
        buf[data_off + 17] = 2     # 当前发色覆盖值 hairCol: 2 = Black
        buf[data_off + 18] = 0     # beard = None
        buf[data_off + 23] = 2     # sex = Male ← 必须
        # ---- 年龄：5 岁。必须同时改 off24(age) 和 off1..3(出生日编码) ----
        NEW_AGE = 5
        u24 = (TODAY_K - NEW_AGE * 30) * 16 + 15      # TODAY_K = 当前游戏日 k
        buf[data_off + 24] = NEW_AGE                   # age 字段
        buf[data_off + 1:data_off + 4] = u24.to_bytes(3, "little")
        buf[off + 4] = 0x05        # Admission | King
        print("patched profile", rid)

    off = data_off + 25
    if k < count - 1:
        off += 9

# 同步写 sv1_ws_info @0x28 = KING_ID
I = r"D:\workspaces\Elnea-Localization\01_raw_data\app_internal\files\Contents\sv1_ws_info"
ib = bytearray(zlib.decompress(open(I, "rb").read()))
struct.pack_into("<I", ib, 0x28, KING_ID)

open(P + ".new", "wb").write(zlib.compress(bytes(buf), 9))
open(I + ".new", "wb").write(zlib.compress(bytes(ib), 9))
print("done")
```

**硬约束（已证实）**：
- 记录内名字是 `[u8 len][UTF-8]`，**改长度必须整块重排并修正后续所有偏移**；最稳是让新名字字节数 == 原名。"杨广"=6 字节，先确认目标槽位的 `nameLen` 是否为 6。
- **`age`(off 24) 与 `off 1..3` 必须同改**：二者描述同一事实（出生日），只改 age 不改 u24，下一帧 `IncDay` / `get_Age` 可能按 u24 重算覆盖回去。**注意不能反过来说 `age` 由 u24 算出**——`age = floor(k/30)` 在 309 条上 0 匹配（§3.3），`age` 是独立整数字段。
- **`TODAY_K` 必须从目标存档现场反推**，不能照抄 5746（那是本存档的实测中位数）：
  ```python
  # 对每条记录按 %16 分支解出 k，再用 t = k + age*30 取中位数
  def k_of(u):
      return (u - 15) // 16 if u % 16 == 15 else u // 16
  TODAY_K = median(k_of(u24_i) + age_i * 30 for i in all_records)
  ```
- 只改一处（profiles 的 flag 与 info 的 kingId）不一致时的行为【未证实】。

### 6.2 名字长度不等时的整块重排

因为每条记录后面固定跟 25 B + 9 B 尾，**按记录重建整个文件**比原地改更安全：

```python
out = bytearray(struct.pack("<I", count) + header9)
for rec in records:                      # 记录以 (id, name, data25, tail9|None) 保存
    nb = rec.name.encode("utf-8")
    out += struct.pack("<I", rec.id) + b"\x01" + bytes([len(nb)]) + nb + rec.data25
    if rec.tail9: out += rec.tail9
```

---

## 7. 未证实 / 待办

1. **`sv1_ws_info` 的 kingId 精确性**：@0x28=24 是【高置信】而非【已证实】。需**已加冕存档**做字节 diff 才能定论；`SaveInfo.Save5792`(0x19F74C0) 真函数头在 .so 中扫不到（0x19E0000–0x1A10000 内 `0xA9BF7BFD` 0 命中）。
2. **25 B 中 off 0 / off 10 / off 19..20 的确切含义**。off 1..3 的年龄编码、off 11/12/17 的肤色发色归属均已【已证实】解出（§3.3 / §3.4）。
3. **`ProfileData.Save5792` 的写入顺序**无法从当前 dump 取得：script.json 地址偏移 + 类归属错配（`"Save5792"` 在 metadata 只 3 处，dump.cs 里 4 个类都有）。要拿到必须换 Il2CppDumper 重导。
3b. **`GameDate.AdmissionDay` / `GraduationDay` / `BaseLimitAge` 的常量值**（18 岁成年、6 岁入学之类门槛）没能从 dump.cs 读到——它们是属性，值在静态构造或 `c_*` 配置里。"6 岁 ≈ 18 岁"这一 3 倍换算率**未证实**出处。
4. **familys 内部矛盾**：profiles 尾部 familyId 与 familys 的 `dicPersons` key 至少一处解析错，改档前须用运行时 `FamilyData.Find` / `Who` 校正。
5. `c_message` 改文本重打包后是否触发完整性校验/签名拒绝。
6. `initGeneration` 字面值（`.cctor` 0x1943828）。
7. `NameMale_en` 改后新开局是否立即生效（`NameData.loader` 缓存时机）。
8. 长体/短体 family body 中 GameDate 位置不一致，`marriageDay` 与 `lastAccess` 哪个在前，未证实。
