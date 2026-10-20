# 测试专用：内嵌 frida-gadget 的排查包（不合并 main，不发正式 Release）

目的：拿到 `acc=` 的**明文**、加密函数 VA、以及 key/IV 的位置。手机不需要 root。

## 怎么用

1. 装 `app-elneazh-test-gadget.apk`（签名和正式版相同，可以直接覆盖安装；数据不丢）。
   - 本地产物：`tools/make_gadget_apk.py` 生成，或 Actions 里手动跑 `test-gadget build` 拿 artifact。
2. 进游戏玩 3~5 分钟，随便点几个需要联网的界面（登录、看好友、改名字、云存档）。
3. 退出游戏，取结果文件（任选一个能读到的）：
   - `Documents/艾尔尼亚存档备份/elneazh_probe.txt`（和存档备份同目录，文件管理器能直接看到）
   - `Android/data/jp.co.althi.elnea.en/files/elneazh_probe.txt`
   - `adb shell cat /sdcard/Android/data/jp.co.althi.elnea.en/files/elneazh_probe.txt`
   - 都不行就 `adb logcat -s ElneaZH` 看实时输出（同样的内容也打到 console）
4. 把文件发回来。里面会长这样：
   ```
   str8 "acc=U2FsdGVkX1+...&lang=en"                 <- 明文（未加密前）
   str16 "http://app-elnea.althi.co.jp/sns04/read.php"
   CLASS mscorlib System.Security.Cryptography.RijndaelManaged
   METHOD System.Security.Cryptography.SymmetricAlgorithm::set_Key va=0x... fileoff=... 
   set_Key arg1 byte[] len=32 hex=... b64=...          <- key
   set_IV  arg1 byte[] len=16 hex=...                  <- IV
   ```

## 产物说明

| 文件 | 作用 |
| --- | --- |
| `tools/make_gadget_apk.py` | 本地重打包：套用 `so.elsp` 到 libil2cpp.so、换 AndroidManifest、塞 classes3.dex、加 `libfrida-gadget.so` + `libgadget.config.so`、按 16K 页对齐后 apksigner 签名 |
| `tools/gadget_hook.js` | gadget 的 script 模式脚本；hook `il2cpp_string_new*`、用 il2cpp 运行时 API 找 `System.Security.Cryptography` 里的 Key/IV/Encryptor/ToBase64String 并打印方法指针与入参；另附 `send/write` 兜底 |
| `.github/workflows/test-gadget.yml` | 手动触发（或推 `test-gadget-*` tag）构建同样的 APK，只上传 artifact，**不建 Release** |

gadget 配置是 script 模式（`{"interaction":{"type":"script","path":"libhook.js"}}`），不监听端口，不会等 PC。

## 注意

- 用的是 frida **16.7.19**（17.x 在部分 ART 版本上 gadget 起不来），只放 arm64 和 armv7 两套。
- 这个包只用于采样，采样完建议装回正式版。长期挂在游戏里会明显变卡。
- 真机验证还没做：如果启动即闪退，回一句日志尾部（`adb logcat | findstr -i frida`），我按日志调。
