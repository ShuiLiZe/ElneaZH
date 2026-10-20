# 鑹惧皵灏间簹鐜嬪浗 2.10.7 姹夊寲鐗堛€岀湡鏈哄崱缁存姢 / 寮哄埗杈撳叆璐﹀彿銆嶆牴鍥犱笌淇

- 鐩爣锛歚jp.co.althi.elnea.en` v2.10.7锛坴ersionCode 141锛夛紝Unity 6000.3.17f1 / IL2CPP arm64-v8a
- 姹夊寲鏂瑰紡锛氫粎鏇挎崲 `assets/bin/Data/data.unity3d`锛岃嚜绛?v1/v2/v3锛孧anifest 鏈姩
- 鍒嗘瀽鏍锋湰锛歚02_extracted\apk\lib\arm64-v8a\libil2cpp.so`锛?1465584 B锛?- 鍙嶆眹缂栦緷鎹細`_scratch\il2cpp_dump\out\script.json` 鏂规硶鍦板潃 + capstone 閫愭潯瑙ｇ爜
- 璁板彿锛?*绗?1鈥? 鑺傚湴鍧€缁?VA锛? so 鍐?vaddr锛屼笌 script.json 鐨?RVA 鍚屼竴涓暟锛?*锛?*绗?5 鑺傝ˉ涓佷竴寰嬬粰鏂囦欢鍋忕Щ**銆傛湰鏍锋湰 `.text` 娈?`p_offset=0x1551780` / `p_vaddr=0x1555780`锛屽嵆 **VA = 鏂囦欢鍋忕Щ + 0x4000**锛堟父鎴忔柟娉曪級锛涜€?mscorlib 鐨?RVA 鎭板ソ绛変簬鏂囦欢鍋忕Щ銆?*涓よ€呬笉鍙贩鐢紝鎵撹ˉ涓佸墠蹇呴』鎸?5.5 閫愭潯姣斿鍘熷瓧鑺傘€?*
- **adb 鐘舵€侊細鏈 `adb devices` 鏃犺澶囷紝绗?1 姝ョ幇鍦烘姄鏃ュ織璺宠繃**銆傚彇璇佽剼鏈凡鍐欏ソ锛屾彃涓婃墜鏈哄嵆鍙窇锛堣绗?6 鑺傦級銆?
---

## 0. 涓€鍙ヨ瘽缁撹

鐪熸満杩涗笉鍘荤殑鐩存帴鍘熷洜鏄?*鐧诲綍琚湇鍔″櫒鎷掔粷鍚庤繘鍏ャ€屾棤璐﹀彿銆嶅厹搴曟€?*锛屼唬鐞嗗彧褰卞搷瀹冨崱鍦ㄥ摢涓垎鏀細

1. 鍚姩 鈫?`TitlePanel.Update` 鐘舵€佹満 鈫?璋?`TitlePanel.accountCheck()`锛圴A `0x1C425F0`锛?2. `accountCheck` 璇绘湰鏈?`[TitlePanel+0x128] isAccountInput`锛?   - **=1 鈫?涓嶇櫥褰曪紝鐩存帴 `openAccountPanel()`锛圴A `0x1C426BC`锛?* 鈫?鐪熸満銆岃緭鍏ヨ处鍙枫€嶆灏辨槸杩欎箞寮圭殑
   - =0 鈫?璇?`WorldData.LoadLocalAccount()`锛屾嬁涓嶅埌鍐嶇敤 `WorldData.LoadGuestAccount()`锛堝浐瀹氬尶鍚嶈处鍙凤級锛岄兘娌℃湁灏?*浠€涔堥兘涓嶅仛锛屽仠鍦ㄦ爣棰?*
3. 鎷垮埌璐﹀彿 鈫?`startLoginRequest()`锛圴A `0x1C43100`锛夆啋 `new LoginRequest(account)`锛圴A `0x1A9E8DC`锛夆啋 POST `login.php`锛屽弬鏁伴噷甯?`althi_uniq = UuidPlugin.GetUUID()`锛?1 瀛楃锛夊拰 `login_key` 绛?4. 鐧诲綍澶辫触 鈫?`onErrorLoginRequest1()`锛圴A `0x1C43FB0`锛?*鏈€澶氶噸璇?10 娆?*锛岀 11 娆″け璐?鈫?鍚屾牱 `openAccountPanel()`

銆屾棤璐﹀彿銆嶅厹搴曡矾寰?= 鏈嶅姟鍣ㄨ涓鸿繖涓?`althi_uniq` **娌℃湁瀵瑰簲鐨勬湇鍔″櫒瀛樻。**銆?
**涓轰粈涔堛€屾竻闄ゆ暟鎹?+ 鍗歌浇閲嶈銆嶄篃娌＄敤锛堢 4 杞慨姝ｏ紝杩欐槸鍏抽敭锛?*锛歚althi_uniq` 纭疄瀛樺湪 app 绉佹湁鐩綍閲岋紝鍗歌浇浼氳鍒犳帀锛涗絾閲嶈鍚?`UuidPlugin.GetUUID()` 閲嶅缓鍑烘潵鐨勪覆**骞朵笉鏄殢鏈虹殑**鈥斺€斿畠鐢?`AndroidUuidPlugin.GetUUID()` + SSAID 鎷兼垚锛岃€?SSAID锛坄SystemInfo.deviceUniqueIdentifier`锛夊湪**鍚屾満鍚岀鍚嶇殑閲嶈涔嬮棿淇濇寔涓嶅彉**銆傛墍浠ャ€屽垹 app 鏁版嵁銆嶆崲涓嶆帀鏈嶅姟鍣ㄧ溂閲岀殑韬唤銆傝瑙?3.2 / 3.3銆?
鑷充簬銆屾瀛愯鍒欐ā寮忓崱缁存姢涓€佸叏灞€妯″紡寮硅处鍙锋銆嶏細缁存姢鍒ゅ畾鍜岃处鍙峰垽瀹?*鍏辩敤鍚屼竴浠芥湇鍔″櫒鍝嶅簲閫氶亾**锛坄HttpNotify` 妫€鏌ユ槑鏂囩浜岃鏄惁 `MAINTENANCE`锛夛紝浠ｇ悊瑙勫垯涓嶅悓 鈫?鏈夌殑璇锋眰琚嫤鍦ㄧ淮鎶ら〉銆佹湁鐨勮鎷﹀湪鐧诲綍锛岃惤鍦ㄧ姸鎬佹満涓嶅悓鍒嗘敮銆?*涓ょ鐜拌薄鏄悓涓€涓牴鍥犵殑涓ょ琛ㄧ幇**锛屼笉鏄袱涓?bug銆?
---

## 1. 缁存姢鍒ゅ畾鏉′欢锛坄GameSystem` 闈欐€佸瓧娈甸摼锛?
### 1.1 鍒ゅ畾瀛楁涓庤鍐欑偣

| 鏂规硶 | VA | 鍏抽敭鎸囦护 |
|---|---|---|
| `GameSystem.get_IsDisableCommunicationByMaintenance()` | `0x18B71A4` | `x8 = GameSystem.data; ldrb w0,[x8,#0x41]` |
| `GameSystem.GoMaintenanceScene()` | `0x18B72B4` | `strb w21(=1),[x8,#0x41]` @ `0x18B7314`锛?0x60锛夆啋 `LoadScene(maintenanceRoot,0.5,0)` |
| `GameSystem.GoVersionUpScene()` | `0x18B71FC` | `strb w21(=1),[x8,#0x41]` @ `0x18B725C`锛?0x60锛夆啋 `LoadScene(versionUpRoot)` |
| `GameSystem.GoTitle()` | `0x18B5888` | `str wzr,[x8]` + `strb wzr,[x8,#0x41]` @ `0x18B5934`锛?0xAC锛夆啋 `LoadScene(title)` |
| `HttpRequest.get_IsMaintenance()` | `0x18BE80C` | `if (this[0x60]) return true; return GameSystem.data[0x41] != 0;` |

### 1.2 璁╁瓧娈电疆 1 鐨勫洓鏉¤矾

1. 鏈嶅姟鍣ㄤ笅鍙?鈫?`HttpNotify`锛圴A `0x18BFD80`锛夋妸鍝嶅簲鏄庢枃鎸?`\n` 鍒囷紝**绗?2 琛?== `"MAINTENANCE"`** 灏辫皟 `GoMaintenanceScene`
2. `UpdateServerMaintenanceCheckCode`锛圴A `0x18BA1FC`锛夛細`serverRefreshCode`锛坄GameSystem.data+0x12C`锛夊彉鍖栦笖鏈湴 `isChangedMaintenanceCode(+0x130)` 闈?0 鈫?缃剰
3. `get_testChangedMaintenanceCode`锛圴A `0x18BA158`锛夛細`begMaintenanceTime` 闈炵┖鏃舵瘡婊?1s锛坄delayMainteCheck > 1.0f`锛夊仛 `CompareTo(NowDateString) <= 0` 鈫?瑙﹀彂
4. 绂荤嚎鍒ゅ畾锛氱綉缁滀笉閫?/ 璇锋眰琚唬鐞嗘嫤鎺?鈫?璧?3 鐨勬湰鍦版椂闂存瘮杈?
### 1.3 瀹炶瘉锛氭湇鍔″櫒纭疄鎸夎处鍙蜂笅鍙戠淮鎶ょ獥鍙?
`02_extracted\maint_decrypted.bin`锛堟湰鏈烘姄鍒扮殑鐪熷疄鍝嶅簲锛夎В鍑虹殑瀛楁锛?
```
user_id        = 0
end_maintenance= 2026-10-15 03:54:39
```

鍗?`user_id=0`锛堟湭鐧诲綍/鍖垮悕锛変篃鏈変竴浠界淮鎶よ褰?鈫?**鏂拌璁惧涓嶇櫥褰曚篃浼氭嬁鍒扮淮鎶ょ獥**銆傝繖瑙ｉ噴浜嗐€屼互鍓嶆病瀛樻。涔熻兘杩涖€佺幇鍦ㄤ笉琛屻€嶏細**涓嶆槸瀹㈡埛绔彉浜嗭紝鏄湇鍔″櫒鎶婄淮鎶ょ獥涓嬪彂缁欎簡鍖垮悕妗?*銆?
### 1.4 涓夋潯鍒嗘敮鐨勫垽瀹氶『搴?
| 鍒嗘敮 | 瑙﹀彂 | 娓告垙琛ㄧ幇 |
|---|---|---|
| A | `HttpNotify` 绗?2 琛?== `MAINTENANCE`锛堟垨 `begMaintenanceTime` 杩囨湡锛?| MaintenanceRoot銆岀淮鎶や腑銆?|
| B | 鐧诲綍璇锋眰杩炵画澶辫触 >9 娆?| `openAccountPanel` 鈫?銆屼娇鐢ㄦ湇鍔″櫒瀛樻。缁х画銆嶈緭鍏ユ |
| C | 瑙勫垯妯″紡涓嬭姹傝鎷?鈫?鏃犲搷搴?鈫?璧?A 鐨勭绾垮垽瀹?| 鍏堝崱缁存姢锛屽垏鍏ㄥ眬鍚庡彉鎴?B |

**瑙勫垯/鍏ㄥ眬涓ょ妯″紡鍒嗗埆钀藉湪 A 鍜?B**锛屾墍浠ャ€屾崲浠ｇ悊妯″紡鐥囩姸鍙樹簡銆嶄笉绛変簬銆屾湁涓や釜 bug銆嶃€?
---

## 2. 鏍囬/鐧诲綍娴佺▼瑙﹀彂鏉′欢

### 2.1 `TitlePanel.accountCheck`锛圴A `0x1C425F0`锛屾枃浠?`0x1C3E5F0`锛?
```
0x1C42620  mov  w1,#1
0x1C42624  mov  x2,xzr
0x1C42628  bl   PlayerPrefs.SetInt
0x1C4262C  ldrb w8,[x19,#0x128]     ; isAccountInput        鈫?鏂囦欢 0x1C3E62C
0x1C42630  cbz  w8,#0x1C42644       ; ==0 鈫?姝ｅ父鐧诲綍         鈫?鏂囦欢 0x1C3E630
0x1C42634  ...      鈫?钀藉埌 TitlePanel.openAccountPanel (VA 0x1C426BC)
0x1C42644  ...      鈫?WorldData.LoadLocalAccount (VA 0x1A63CBC)
                       鈫?绌哄垯 WorldData.LoadGuestAccount (VA 0x1A63F24锛屽浐瀹?anonymous)
                       鈫?閮界┖鍒?ret锛堝共绛夛級
```

**寮硅处鍙锋鍙湁涓や釜鍏ュ彛**锛歚isAccountInput==1`銆乣onErrorLoginRequest1` 澶辫触璁℃暟 >9銆?*娌℃湁绗笁涓€?*

### 2.2 `TitlePanel.onErrorLoginRequest1`锛圴A `0x1C43FB0`锛屾枃浠?`0x1C3FFB0`锛?
```
0x1C43FB4  ldr  w8,[x0,#0x10C]      ; 澶辫触璁℃暟
0x1C43FB8  cmp  w8,#9
0x1C43FBC  b.le #0x1C43FD4          ; 鈮? 鈫?璁℃暟+1銆乧hangeState(5) 閲嶈瘯   鈫?鏂囦欢 0x1C3FFB8锛堝師 6D000054锛?0x1C43FC0  str  wzr,[x0,#0x10C]     ; >9 鈫?璁℃暟娓呴浂
0x1C43FC4  b    TitlePanel.openAccountPanel    鈫?寮广€屼娇鐢ㄦ湇鍔″櫒瀛樻。缁х画銆?```

### 2.3 `startLoginRequest` 鈫?`LoginRequest..ctor`锛圴A `0x1A9E8DC`锛?
```
HttpRequest..ctor(VA 0x18BE9D0)
  鈹?AlthiGem.GetGemCount
PlayerPrefs.GetString("login_key") 闈炵┖ 鈫?dict["login_key"]
HttpRequest.addUniqArg(VA 0x18C0AD8)
  鈹?dict["althi_uniq"] = UuidPlugin.GetUUID()      鈫?韬唤灏辨槸杩欎竴涓瓧娈?forGoogleLink(VA 0x1A9E6F0)                         鈫?璋锋瓕閾捐矾锛岃蛋涓嶅埌涔熻兘鐧诲綍
```

---

## 3. 鐪熸満 vs OurPlay 鐨勬牴鍥?
### 3.1 鐜拌薄瀵圭収

| 鐜 | `althi_uniq` 鏉ユ簮 | 鏈嶅姟鍣ㄨ瑙?| 缁撴灉 |
|---|---|---|---|
| OurPlay 娌欑 | 鐙珛 `/data/data` + 鐙珛 SSAID锛堣櫄鎷熺┖闂寸粰姣忎釜鍏嬮殕鍖呭垎閰嶄笉鍚?deviceUniqueIdentifier锛?| 鍏ㄦ柊璁惧 | 鐩存帴杩涙柊娓告垙 |
| 鐪熸満锛堥娆″畨瑁呮椂锛?| 鐪熸満 SSAID 娲剧敓 | 鍏ㄦ柊璁惧 | 褰撴椂鑳借繘 |
| 鐪熸満锛堢幇鍦紝娓呮暟鎹?閲嶈鍚庯級 | **鍚屼竴 SSAID 閲嶆柊娲剧敓** | **杩樻槸閭ｅ彴琚爣璁拌繃鐨勮澶?* | 鍗＄淮鎶?/ 寮硅处鍙锋 |

### 3.2 `althi_uniq` 鍒板簳鐢变粈涔堟瀯鎴愶紙VA 绾у疄璇侊級

杩欐槸鏈璋冩煡鏈€鍏抽敭鐨勫弽姹囩紪缁撴灉锛?*鎺ㄧ炕浜嗗墠鍑犺疆銆寀uid = `S-` + 30 浣嶉殢鏈?+ `-` + SSAID銆嶇殑鐚滄祴**銆?
`UuidPlugin.GetUUID()`锛圴A `0x1C8F410` / 鏂囦欢 `0x1C8B410`锛夛細

```
0x1C8F460  ldr  x0,[x21]                    ; PlayerPrefs 閿?"uuid"
0x1C8F46C  bl   PlayerPrefs.GetString (0x336976C)
0x1C8F474  bl   UuidPlugin.isEmptyUUID (0x1C8F3D8)
0x1C8F478  tbz  w0,#0,#0x1C8F510            ; 缂撳瓨闈炵┖ 鈫?鐩存帴杩斿洖缂撳瓨   鈫?鏂囦欢 0x1C8B478锛堝師 C0040036锛?0x1C8F480  bl   AndroidUuidPlugin.GetUUID (0x18A1CD4)
0x1C8F48C  bl   UuidPlugin.isEmptyUUID
0x1C8F490  tbz  w0,#0,#0x1C8F500            ; 闈炵┖ 鈫?鍥炲啓缂撳瓨骞惰繑鍥?    鈫?鏂囦欢 0x1C8B490锛堝師 80030036锛?0x1C8F494  bl   UuidPlugin.makeUUID (0x1C8F040)
0x1C8F4A8  bl   AndroidUuidPlugin.GetSerialID (0x18A2DB4)   ; x20 = SSAID
0x1C8F4C4  mov  x1,x20
0x1C8F4C8  mov  x2,x19
0x1C8F4D4  ldr  x0,[0x3854DC8]              ; 鏍煎紡涓插瓧闈㈤噺 = "#{0}{1}"
0x1C8F4D8  bl   String.Format<string,object,object> (0x2C322EC)
0x1C8F4E0  mov  w1,wzr
0x1C8F4E4  mov  w2,#0x29
0x1C8F4EC  bl   String.Substring (0x2C335F0) ; (0, 41)
0x1C8F4F4  bl   UuidPlugin.SetUUID (0x1C8F60C)
```

瑕佺偣锛?
1. **鏍煎紡涓叉槸 `"#{0}{1}"`锛屼笉鏄?`"S-{0}-{1}"`**锛堝瓧闈㈤噺鍦板潃 `0x3854DC8`锛夈€俙{0}` = `makeUUID()` 鐨?41 瀛楃锛宍{1}` = SSAID銆?2. `Substring(0, 41)` 鎶?`"#" + makeUUID(41) + SSAID` 鎴埌 41 浣?鈫?**SSAID 涓€涓瓧绗﹂兘杩涗笉浜嗘渶缁?uuid**銆?3. 鍥犳 `althi_uniq` = `"#" + 40 浣嶉殢鏈哄瓧绗锛?*闅忔満涓叉潵鑷摢閲屽喅瀹氬畠鏄惁鍙彉**锛?
`UuidPlugin.makeUUID()`锛圴A `0x1C8F040`锛夛細

```
List<int>.ctor
StringBuilder.set_Capacity(0x29)                       ; 41
棣栧瓧绗︼細DateTime.Now.Millisecond % (姹犻暱-1) + 1
寰幆 30 娆★紙w25=0x1e锛夛細
    List<int>.FindIndex(Predicate)                     ; 鍘婚噸
    閲嶅 鈫?UnityEngine.Random.Range(0, 姹犻暱)
                        ; Random 鍐呴儴涓夋暣鍨嬬姸鎬?[+0x10..+0x1C]锛?                        ; 0x1C8F9E4 eor w9,w9,w9,lsl#11 / eor w11,w11,w10,lsr#19 / eor w0,w11,w9
                        ; 绉嶅瓙 = DateTime.Now.Ticks 浣?32 浣?鈫?杩涚▼鍐呭叏灞€鍞竴銆佹瘡娆¤皟鐢ㄥ墠杩?    String.Substring(ch,1) 鈫?StringBuilder.Append
绗?31 瀛楃锛欴ateTime.Millisecond % 姹犻暱
StringBuilder.ToString()
```

`Random` 鐨勭姸鎬?*鍙湪杩涚▼鍐?*銆佺瀛愭槸 `DateTime.Now.Ticks`銆傛寜闈欐€佸垎鏋愶紝杩涚▼閲嶅惎鍚庣瀛愬簲褰撳彉鍖栵紝uuid 搴斿綋姣忔鍚姩閮戒笉鍚屻€?
**浣嗗疄娴嬩笉鏄繖鏍?*鈥斺€旂敤鎴锋竻鏁版嵁 + 鍗歌浇閲嶈鍚庢湇鍔″櫒浠嶈寰椾粬銆傚敮涓€鐨勮В閲婂氨鏄細**璧颁簡 `PlayerPrefs["uuid"]` 缂撳瓨鍒嗘敮锛坄0x1C8F478`锛?*锛屽嵆 App 鏁版嵁琚仮澶嶏紙鍗庝负/灏忕背/OPPO 鐨勩€屽嵏杞戒繚鐣欐暟鎹€嶃€屽簲鐢ㄥ厠闅嗚縼绉汇€嶃€屼簯澶囦唤杩樺師銆嶉兘浼氭仮澶?playerprefs锛汷urPlay 瀵煎叆鏃朵篃浼氬甫鏁版嵁鍖咃級銆傝繖涓庛€屾崲鍒?OurPlay 灏辨甯搞€嶅畬鍏ㄥ惢鍚堬細OurPlay 娌欑閲岃繖浠界紦瀛樻槸绌虹殑銆?
> 缁撹锛?*鏈嶅姟鍣ㄨ鐨勪笉鏄?SSAID锛岃€屾槸琚紦瀛樺鐢ㄧ殑 `althi_uniq` 瀛楃涓层€?* 鍙鎶婄紦瀛樿繖鏉¤矾鎵撴柇銆佸苟涓旇鏄庢枃閲岀殑韬唤鐪熺殑姣忔鏂扮敓鎴愶紝灏辫兘璁╂湇鍔″櫒鎶婅澶囧綋鏂版満銆?
### 3.3 鍏朵粬琚帓闄ょ殑鍥犵礌

| 鎬€鐤戦」 | 缁撹 |
|---|---|
| 璁惧 Android ID | **鏈弬涓?*銆侫PI 28+ 璧?`GetSerialID 鈫?getSafeDeviceId 鈫?SystemInfo.deviceUniqueIdentifier`锛?SSAID锛夛紱鍙湁 SSAID 涓虹┖/娈嬩覆鏃舵墠 fallback 鍒?`getAndroidId`锛圴A `0x18A3138`锛?|
| 骞垮憡 ID | **鏈弬涓?*銆俙getIMEI`/`getAndroidId`/骞垮憡 ID 閮戒笉鍦?API28+ 璺緞涓?|
| 璋锋瓕鏈嶅姟 / Play Games | **闈炲繀闇€**銆俙forGoogleLink` 鍙奖鍝嶅彲閫夌粦瀹氾紝涓嶇粦瀹氫篃璧?guest 鐧诲綍锛圤urPlay 鑳借繘鍗宠瘉锛?|
| 閲嶇瀵艰嚧鐨勭鍚嶅彉鍖?| 褰卞搷 `althi_uniq` 鐨?*鍙栧€?*锛堢鍚嶈澶囦笌鏈鍚嶈澶囪蛋涓嶅悓鍒嗘敮锛夛紝浣?*涓嶆瀯鎴愰攣**锛氶噸绛惧彧浼氳绗竴娆＄敓鎴愮殑涓蹭笉鍚岋紝涔嬪悗琚紦瀛樺鐢?|
| `/sdcard` 鎴?`/sdcard/Android/data` 娈嬬暀 | 娓告垙鑷繁鐨勫瓨妗ｈ蛋 `/data/data/.../files/`锛涘閮ㄧ洰褰曟畫鐣欎笉浼氭敼鍙?`althi_uniq`銆?*涓嶆瀯鎴愰攣** |

### 3.4 銆屾崲绛惧悕瀵嗛挜銆嶈兘鍚﹀仛蹇€熼獙璇?
**涓嶈兘浣滀负鍙潬鎵嬫**銆傛崲閽ュ寵鍙細寰楀埌涓€涓?*涓嶅悓鐨?* `althi_uniq`鈥斺€斿鏋?`PlayerPrefs["uuid"]` 缂撳瓨杩樺湪锛岃繖涓紦瀛樹細琚師鏍锋部鐢紙`0x1C8F478` 鍒嗘敮鍦ㄧ 3 姝ュ氨鐩存帴 return锛屾牴鏈笉閲嶆柊娲剧敓锛夛紝鎹簡绛変簬娌℃崲銆備笖鏀圭鍚嶄細甯︽潵銆屾洿鏂拌涓嶄笂銆佸瓨妗ｅ叏涓€嶇殑鍓綔鐢ㄣ€?*姝ｇ‘鍋氭硶鏄墦鏂紦瀛樺垎鏀?*锛?.1 鐨?P3/P4锛夛紝涓€娆″埌浣嶃€?
---

## 4. 涓嶆敼浜岃繘鍒剁殑淇锛堜紭鍏堝皾璇曪級

鍗充娇鐢ㄦ埛璇淬€屾竻鏁版嵁銆侀噸瑁呴兘娌＄敤銆嶏紝涔熷厛鎶?**playerprefs 缂撳瓨杩欎竴澶?*鍗曠嫭娓呮帀璇曚竴娆♀€斺€旇繖鏄敮涓€涓€涓笉闈犻噸瑁呭氨鑳芥竻骞插噣鐨勮惤鐐广€?
### 4.1 鎵嬪姩娓呰韩浠斤紙闇€瑕?root / adb锛涙湰鎶ュ憡鍙粰鍛戒护锛屼笉浠ｆ墽琛岋級

```bash
# 0) 鍏堝叏閲忓浠斤紙鍔″繀鍋氾紝鍑洪棶棰樺彲鍘熸牱鍥炴粴锛?adb shell su -c 'tar czf /sdcard/ElneaBackup_$(date +%s).tgz /data/data/jp.co.althi.elnea.en'

# 1) 澶囦唤 playerprefs 鍚庯紝鍒犳帀 uuid / login_key 涓よ锛堜笉鏁翠唤鍒狅級
adb shell su -c 'cp /data/data/jp.co.althi.elnea.en/shared_prefs/jp.co.althi.elnea.en.v2.playerprefs.xml /data/data/jp.co.althi.elnea.en/shared_prefs/pp.bak.xml'
adb shell su -c 'sed -i -E "/<(uuid|login_key)>/d" /data/data/jp.co.althi.elnea.en/shared_prefs/jp.co.althi.elnea.en.v2.playerprefs.xml'

# 2) 鍒犺韩浠界紦瀛樻枃浠朵笌缁存姢鐮佺紦瀛?adb shell su -c 'rm -f /data/data/jp.co.althi.elnea.en/files/althi_uniq'
adb shell su -c 'rm -f /data/data/jp.co.althi.elnea.en/files/Contents/maintenance'
adb shell su -c 'rm -f /data/data/jp.co.althi.elnea.en/files/Contents/ws_account'

# 3) 淇潈闄愶紙涓嶅悓璁惧 uid 涓嶅悓锛岀敤 stat 鍙栧疄闄呭€硷級
adb shell su -c 'stat -c "%u:%g" /data/data/jp.co.althi.elnea.en'
adb shell su -c 'chown -R u0_aXXX:u0_aXXX /data/data/jp.co.althi.elnea.en/shared_prefs'
adb shell su -c 'chmod 660 /data/data/jp.co.althi.elnea.en/shared_prefs/*.xml'
```

**璇存槑**锛氫笂闈㈡瘡涓€姝ラ兘瀵规墜鏈烘暟鎹湁鍐欐搷浣滐紝鍛戒护宸插啓濂戒絾**娌℃湁浠ｆ墽琛?*锛岄渶瑕佺敤鎴疯嚜宸卞湪鏈?root 鐨?adb 鐜璺戙€傝窇瀹岄噸鍚父鎴忥紝鑻ヨ繘寰楀幓 鈫?璇佹槑缂撳瓨灏辨槸閿侊紝鍚庣画鐢?5.1 鐨勮ˉ涓佸浐鍖栵紝鍏嶅緱鍐嶆墜鍔ㄦ竻銆?
### 4.2 浠ｇ悊渚э細鎶婃父鎴忓煙鍚嶆暣娈垫斁琛?
`app-elnea.althi.co.jp` 鏄?*鏄庢枃 HTTP**锛屾瀛愯鍒欐ā寮忎笅瀹规槗琚?MITM/鎷︽埅瀵艰嚧 `HttpNotify` 璁や负缁存姢銆傚湪姊瓙瀹㈡埛绔噷瀵硅鍩熷悕鍔犱竴鏉?`DIRECT`锛堟垨鍏ㄥ眬妯″紡涓嬪姞鐧藉悕鍗曪級銆?*杩欏彧鍘嬫帀銆岀淮鎶や腑銆嶈繖涓€鏀紝涓嶈兘鏇夸唬 4.1 / 5.1銆?*

### 4.3 涓轰粈涔?OurPlay 閲岃兘杩涳紙浣滀负瀵圭収楠岃瘉锛?
OurPlay 娌欑鐨?`PlayerPrefs["uuid"]` 鏄┖鐨?鈫?`GetUUID` 璧?`makeUUID` 鐢熸垚鍏ㄦ柊涓?鈫?鏈嶅姟鍣ㄥ綋鏂拌澶囥€?*杩欏氨鏄€岀紦瀛樺垎鏀€嶅亣璁剧殑鐜版垚鍙嶈瘉銆?*

---

## 5. `libil2cpp.so` 琛ヤ竵鏂规锛堝仛杩涙眽鍖栧櫒锛岀鍚嶅墠鑷姩鎵擄級

> 鏈妭瀛楄妭**宸插叏閮ㄥ疄鏈烘牎楠?*锛氬 `02_extracted\apk\lib\arm64-v8a\libil2cpp.so` 鏍￠獙鍘熷瓧鑺?+ capstone 鍙嶈В鏂板瓧鑺傦紝5 鏉″叏缁匡紙瑙?5.5锛夈€?> **鍦板潃绾緥**锛歚.text` 娈?`VA = 鏂囦欢鍋忕Щ + 0x4000`锛坄p_offset=0x1551780` / `p_vaddr=0x1555780`锛夈€傛湰鑺備竴寰嬬粰**鏂囦欢鍋忕Щ**銆俶scorlib 鐨?RVA 鎭板ソ绛変簬鏂囦欢鍋忕Щ锛宍String.Format` 鍦ㄦ枃浠?`0x2C1FB20` 澶勬槸 `sub sp,sp,#0x60` 鈥斺€?涓よ€呬笉鑳芥贩绠椼€?> 鎸囦护鍧囦负灏忕 u32銆?
### 5.1 浜旀潯琛ヤ竵

| # | 鍚嶇О | 鏂囦欢鍋忕Щ | 鍘熷瓧鑺?| 鏂板瓧鑺?| 鍙嶆眹缂栧彉鍖?| 浣滅敤 |
|---|---|---|---|---|---|---|
| P1 | `P_LOGIN_NO_ACCOUNT_B0` | `0x1C3E62C` | `68A24439` | `08008052` | `ldrb w8,[x19,#0x128]` 鈫?`mov w8,wzr` | `isAccountInput` 鎭掕涓?0 |
| P2 | `P_LOGIN_NO_ACCOUNT_B1` | `0x1C3E630` | `A8000034` | `1F2003D5` | `cbz w8,#0x1C42644` 鈫?`nop` | 姘镐笉璺宠繘 `openAccountPanel` |
| P3 | `P_LOGIN_NO_RETRY` | `0x1C3FFB8` | `6D000054` | `6D000014` | `b.le #0x1C43FD4` 鈫?`b #0x1C43FD4` | 鐧诲綍澶辫触 >9 娆′笉鍐嶅脊銆岃緭鍏ヨ处鍙枫€?|
| P4 | `P_UUID_NO_CACHE_0` | `0x1C8B478` | `C0040036` | `1F2003D5` | `tbz w0,#0,#0x1C8F510` 鈫?`nop` | 涓嶉噰淇?`PlayerPrefs["uuid"]` 鏃у€?|
| P5 | `P_UUID_NO_CACHE_1` | `0x1C8B490` | `80030036` | `1F2003D5` | `tbz w0,#0,#0x1C8F500` 鈫?`nop` | 姣忔鍚姩閮介噸鏂版淳鐢?uuid |

**P1+P2 鐨勬晥鏋?*锛氭棤璁烘湇鍔″櫒鎬庝箞鍥烇紝閮藉彧璧?`LoadLocalAccount 鈫?LoadGuestAccount 鈫?startLoginRequest`锛屽啀涔熷脊涓嶅嚭銆屼娇鐢ㄦ湇鍔″櫒瀛樻。缁х画銆嶃€?**P4+P5 鐨勬晥鏋?*锛歚PlayerPrefs["uuid"]` 褰诲簳澶辨晥锛屾瘡娆″喎鍚姩閮戒細 `makeUUID` 閲嶉€?41 瀛楃韬唤 鈫?鏈嶅姟鍣ㄨ涓烘柊璁惧锛岀淮鎶ら攣锛堟寜 user_id 涓嬪彂锛変篃闅忔柊韬唤娑堝け銆?**P4/P5 鐨勫壇浣滅敤**锛氭瘡娆″喎鍚姩鎹㈣韩浠?鈫?鏈嶅姟鍣ㄧ姣忔閮芥槸銆屾柊鍙枫€嶃€?*鎯充繚鐣欐湇鍔″櫒瀛樻。鐨勪汉涓嶈鐢?P4/P5**锛屽彧鐢?P1鈥揚3 鍗冲彲锛堣处鍙风粦瀹氬悗鐢?`login_key` 鎵挎媴锛屼笉渚濊禆 uuid 绋冲畾锛夈€傝繖涓€鍙栬垗鍐欒繘姹夊寲鍣ㄥ紑鍏炽€?
### 5.2 涓嶇敤浠ｇ爜娲炵┐銆佷笉纰?v0.0.7 宸插崰鐢ㄧ殑娲?
P1鈥揚5 鍏ㄦ槸**绛夐暱鍘熷湴鏇挎崲**锛? 瀛楄妭 鈫?4 瀛楄妭锛夛紝涓嶉渶瑕佸啓妗┿€佷笉闇€瑕?`bl` 杩滆烦锛屽洜姝わ細

- 涓嶇 `0x8F0B18`锛坴0.0.7 鐨?`/sdcard/ElneaAssistant/Contents/` 瀛楃涓诧級
- 涓嶇 `0x18AA959` / `0x18AA977` / `0x18AA97B` / `0x18AA983`锛坄FileSystem.get_fileContentsPath` 鏀瑰啓锛?- 涓嶇 `0x1AB3588`锛坄MasterCheckRequest.isChangeVersion` 鈫?`return false`锛?
骞朵笌鏃?`so_patch.bin` 鐨?6 鏉″仛杩囧尯闂撮噸鍙犳娴嬶細**鏃犲啿绐?*锛坄merge_so_patch.py` 缁撴灉 `old=6 new=5 merged=11`锛?74 瀛楄妭锛夈€?
### 5.3 澶囬€夛細缁存姢椤电洿鎺ュ叧鎺夛紙榛樿涓嶅惎鐢級

```
GoMaintenanceScene锛氭枃浠?0x18B3314  鍘?15050139 (strb w21,[x8,#0x41]) 鈫?鏂?1F050139 (strb wzr,[x8,#0x41])
GoVersionUpScene  锛氭枃浠?0x18B325C  鍘?15050139 鈫?鏂?1F050139
```

**鈿狅笍 涓ゆ潯鍐欑殑鏄悓涓€涓?`GameSystem.data+0x41`**锛屼竴璧锋敼浼氭妸銆岀増鏈崌绾с€嶆彁绀轰篃鍚炴帀锛屼笖鍚炴帀缁存姢鎻愮ず鍚庣帺瀹朵細瀵圭潃绌哄満鏅共绛夈€傚彧鍦ㄦ槑纭鍒ょ淮鎶ゆ椂鍚敤銆?
### 5.4 鐢熸垚鍣紙宸插彲璺戯紝浜у嚭鍗崇鍚嶅墠瑕佺敤鐨?bin锛?
`<repo>\tools\make_login_patch.py`

```powershell
# 鍙牎楠岋紝涓嶅啓鏂囦欢
python tools\make_login_patch.py --so 02_extracted\apk\lib\arm64-v8a\libil2cpp.so --verify
# 鍐?ELSP v1 浜岃繘鍒?python tools\make_login_patch.py --so 02_extracted\apk\lib\arm64-v8a\libil2cpp.so --out 04_app\assets\so_patch.login.bin
```

鍐呴儴 `PATCHES` 鍗?5.1 鐨勮〃锛沗Elf` 绫绘寜 `PT_LOAD` 鍋?VA鈫斿亸绉绘崲绠楋紱`build_entries()` 鏍￠獙鍘熷瓧鑺傚悗鎵嶄骇鍑烘潯鐩紝浠讳綍涓€鏉″涓嶄笂鐩存帴閫€鍑恒€佷笉鍐欐枃浠躲€?
鍚堝苟杩涙棦鏈?`so_patch.bin`锛?
```powershell
python tools\merge_so_patch.py 04_app\assets\so_patch.bin 02_extracted\apk\lib\arm64-v8a\libil2cpp.so 04_app\assets\so_patch.bin
```

锛坄merge_so_patch.py` 瀵瑰悓 offset 鍋氬幓閲嶃€佸鍖洪棿閲嶅彔鐩存帴鎶ラ敊閫€鍑猴紝涓嶄細鍗婅鐩栥€傦級

### 5.5 鏍￠獙缁撴灉锛堝叏缁匡紝宸茶窇杩囷級

```
[verify] P_LOGIN_NO_ACCOUNT_B0   off=0x1C3E62C  va=0x1C4262C  68A24439 -> 08008052  mov w8, wzr
[verify] P_LOGIN_NO_ACCOUNT_B1   off=0x1C3E630  va=0x1C42630  A8000034 -> 1F2003D5  nop
[verify] P_LOGIN_NO_RETRY        off=0x1C3FFB8  va=0x1C43FB8  6D000054 -> 6D000014  b #0x1c3ffd4
[verify] P_UUID_NO_CACHE_0       off=0x1C8B478  va=0x1C8F478  C0040036 -> 1F2003D5  nop
[verify] P_UUID_NO_CACHE_1       off=0x1C8B490  va=0x1C8F490  80030036 -> 1F2003D5  nop
琛ヤ竵鏉＄洰 5 涓紝so_patch.bin 澶у皬 112 瀛楄妭
[merge]  old=6 new=5 merged=11 -> 04_app\assets\so_patch.bin (374 瀛楄妭)
```

### 5.6 宸叉帴鍒版眽鍖栧櫒娴佺▼閲岋紙浜х墿宸茬敓鎴愶級

1. `04_app\assets\so_patch.bin` 宸茶鏇挎崲涓?**11 鏉″悎骞剁増**锛?74 瀛楄妭锛屽師 274 瀛楄妭 / 6 鏉★級銆?2. `04_app\build.bat` 閲嶆柊鏋勫缓 鈫?`03_patch_output\ElneaAssistant.apk`锛?592944 鈫?鏂板寘宸查噸绛撅級銆?3. 鏍￠獙锛歚apksigner verify --verbose 03_patch_output\ElneaAssistant.apk` 鈫?`Verifies`锛坴2 true / v3 true锛夛紱鍖呭唴 `assets/so_patch.bin` 瑙ｅ嚭 **cnt=11銆乵agic ELSP**銆?
鑰?`so_patch.bin` 鏄殢 App 鐨?assets 鎵撳寘銆乣zipalign` + `apksigner` 涔嬪墠灏卞凡瀹氱鐨勶紝**鎵€浠ヨ繖 11 鏉¤ˉ涓佹槸鍦ㄧ鍚嶅墠鎵撹繘鍘荤殑**锛岀鍚堣姹傘€傛眽鍖栧櫒琚父鎴忎晶璋冪敤鏃讹紝`MainActivity.applyLibil2cppPatch(so)`锛坄04_app\src\jp\co\althi\elnea\assistant\MainActivity.java:3308`锛岃皟鐢ㄧ偣 `:6084`锛夐€愭潯鏍￠獙鍘熷瓧鑺傚悗灏卞湴鏀?so锛屼箣鍚庡啀璧?v1/v2/v3 閲嶇銆?
---

## 6. 鐜板満鍙栬瘉锛堟墜鏈鸿繛涓婂悗鎸夐『搴忚窇锛?
鑴氭湰锛歚_hark\probe_login_chain.py`锛堝彧璇伙紝涓嶄細鍒犱换浣曟枃浠讹級

```powershell
cd <repo>\_hark
python probe_login_chain.py --pull        # 鎷夎韩浠戒笁浠跺/playerprefs/Contents 鍒楄〃/SSAID
python probe_login_chain.py --watch       # 娓?logcat 鈫?鍚姩娓告垙 鈫?杩囨护 login.php/MAINTENANCE/althi_uniq
python probe_login_chain.py --hunt-save   # 鍏ㄦ満鍙鎼滅储鏃у瓨妗?璐﹀彿娈嬬暀
```

鍒ゅ畾瑕佺偣锛?
- `--pull` 閲?`PlayerPrefs["uuid"]` **瀛樺湪涓旂瓑浜庝笂娆″惎鍔ㄧ殑鍊?* 鈫?鍛戒腑 5.1 鐨?P4/P5 鍦烘櫙锛堢紦瀛樺鐢級锛岃繖鏄湰杞牴鍥犵殑鐩存帴璇佹嵁
- `--watch` 閲岀湅鍒?`login.php` 杩斿洖 `...\nMAINTENANCE` 鈫?缁存姢閿佸懡涓紙绗?1.4 鑺傚垎鏀?A锛?- `--watch` 閲岀湅鍒扮櫥褰曞け璐?>9 娆?+ `AddAccountInputPanel` 鈫?绗?1.4 鑺傚垎鏀?B
- 鏈鍒嗘瀽鏃?`adb devices` 杈撳嚭涓虹┖锛?*杩欎笁鏉￠兘杩樻病璺?*锛屽睘浜庡緟琛ヨ瘉鎹?
---

## 7. 鏈В鍐?/ 闇€瑕佽澶囩纭鐨勭偣

1. **`PlayerPrefs["uuid"]` 缂撳瓨鏄惁鐪熺殑瀛樺湪**锛氬弽姹囩紪璇佹槑鏈夎繖涓垎鏀紙`0x1C8F478`锛夛紝浣嗐€岀敤鎴锋竻鏁版嵁閲嶈鍚庡畠杩樺湪銆嶅彧鑳介潬绗?6 鑺傜殑 `--pull` 璇佸疄銆傝嫢瀹炴祴璇ラ敭涓虹┖锛屽垯璇存槑 `makeUUID` 鐨?`UnityEngine.Random` 鍦ㄥ喎鍚姩鏃剁瀛愮浉鍚岋紝灞婃椂鏀圭敤 P4/P5 浠嶈兘淇锛堝己鍒堕噸閫狅級锛屼絾鏍瑰洜鎻忚堪瑕佹敼銆?2. `TitlePanel.isAccountInput(+0x128)` 鐢辫皝缃?1锛氶潤鎬佹壂鎻忔湭鎵惧埌鍐欏叆鐐癸紝鎺ㄦ祴鐢?`AccountInput*Panel` 鐨勬寜閽洖璋冨啓鍏ワ紱P1+P2 鐩存帴缁曡繃锛屼笉褰卞搷淇銆?3. `althi_uniq` 鏈€缁堝啓鎴愮殑鏄?`"#..."` 杩樻槸 `"S-..."`锛氭湰鏈烘牱鏈?`01_raw_data\files\althi_uniq` 鏄?`S-a1646f5b331b10ae388ec64aef08b66b-MY4eFt`锛堝彟涓€鍙版湭绛惧悕璁惧鐨勪骇鐗╋紝鐢?`AndroidUuidPlugin.getUUID` 鍒嗘敮浜х敓锛夈€傛牸寮忎覆瀹炶瘉涓?`"#{0}{1}"`锛屼袱绉嶅舰鎬侀兘涓嶅奖鍝?P4/P5銆?4. `GetSerialID` 鐨?API28+ 鍒嗘敮锛坄VA 0x18A2DB4`锛宍b.gt` @ `0x18A2E48` 璺冲埌 `bl getSafeDeviceId @0x18A3020`锛夊疄娴?*涓嶅弬涓?*鏈€缁?41 瀛楃锛堣 `Substring(0,41)` 鎴帀锛夛紝鍥犳涓嶉渶瑕佷负瀹冨啓妗┿€傛棭鏈熸柟妗堥噷銆屾妸 GetSerialID 鏀归亾杩斿洖鍥哄畾涓层€嶇殑 P4 宸蹭綔搴熴€?5. 浠ｇ悊瑙勫垯瀵?`app-elnea.althi.co.jp` 鐨勫叿浣撳懡涓儏鍐碉細闇€璁惧鎶撳寘纭锛堟槑鏂?HTTP锛屽彲鐩存帴 tcpdump锛夈€?6. **P4/P5 浼氳姣忔鍐峰惎鍔ㄩ兘鏄柊鍙?*锛屾湇鍔″櫒绔瓨妗ｆ棤娉曡嚜鐒剁画涓婏紱榛樿寤鸿鍙紑 P1鈥揚3銆?
---

## 8. 缁撹閫熸煡

| 鐜拌薄 | 鏍瑰洜 | 淇硶 |
|---|---|---|
| 瑙勫垯妯″紡鍗°€岀淮鎶や腑銆?| 鏈嶅姟鍣ㄥ鍖垮悕/鏃ц韩浠戒笅鍙戞寜鐢ㄦ埛缁存姢绐楋紙`maint_decrypted.bin`: `user_id=0`銆乣end_maintenance=2026-10-15 03:54:39`锛? 浠ｇ悊鎷︽埅浣?`HttpNotify` 鍛戒腑 `MAINTENANCE` | 4.2 鍩熷悕鏀捐锛涜褰诲簳鎹㈣韩浠藉啀鍔?P4/P5 |
| 鍏ㄥ眬妯″紡寮广€岃緭鍏ヨ处鍙枫€?| `accountCheck` 鏃犳湰鍦拌处鍙?鈫?guest 鐧诲綍澶辫触 10 娆?鈫?`openAccountPanel` | **P1+P2+P3**锛堝凡鍏ュ寘锛?|
| 娓呮暟鎹?鍗歌浇閲嶈閮芥棤鏁?| `althi_uniq` 鍦?`PlayerPrefs["uuid"]` 鏈夌紦瀛樺垎鏀紝`GetUUID` 绗?3 姝ョ洿鎺ヨ繑鍥炴棫鍊硷紝閲嶈鍚庤鏁版嵁鎭㈠甯﹀洖鏉?| **P4+P5**锛堝凡鍏ュ寘锛?|
| OurPlay 鑳借繘 | 娌欑鍐?`PlayerPrefs` 涓虹┖锛岃蛋 `makeUUID` 鐢熸垚鍏ㄦ柊涓?| 浣滀负瀵圭収楠岃瘉 |
| 鎹㈢鍚嶅瘑閽ヨ兘鍚﹂獙璇?| 涓嶈兘锛岀紦瀛樺垎鏀牴鏈笉閲嶆柊娲剧敓 | 涓嶇敤 |

**鎵ц椤哄簭**锛氳鏂扮増 `03_patch_output\ElneaAssistant.apk` 鈫?鐢ㄥ畠閲嶆墦鍖呮眽鍖栫増锛?1 鏉¤ˉ涓佽嚜鍔ㄦ墦锛夆啋 瑁呬笂鍚姩銆傝嫢鎯充繚鐣欐湇鍔″櫒瀛樻。锛屾妸 P4/P5 鍏虫帀鍙暀 P1鈥揚3銆?