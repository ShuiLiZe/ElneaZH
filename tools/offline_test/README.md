# tools/offline_test —— 不需要手机的离线自测

单机模式（第二批）与分享/导出（第三批）里，凡是「能离线判定」的逻辑都在这里跑，
CI（`.github/workflows/build-check.yml`）每次 push 都会执行两条检查：

```
python tools/verify_offline.py          # 源码级护栏：判定范围、表、文案、入口、try/catch
javac ... tools/offline_test ... && java com.shuilize.elneazh.inj.OfflineTest
                                        # 真跑 Java：isGameServer / intercept / Reply / 开关
```

## 目录

- `com/shuilize/elneazh/inj/OfflineTest.java`：**真编译真运行**的测试。
  与 `Offline` 同包，所以能直接调包私有方法。覆盖：
  - `isGameServer` 认四个入口（app-/cdn-/res-/裸域 + 大小写）；
  - 后缀骗术（`althi.co.jp.evil.com`、`notalthi.co.jp`）不算游戏服务器；
  - 单机开 → 所有 sns04 请求都「本机接住、不外发」，含登录与未知接口；
  - 单机关 → 一条都不拦；
  - 本机回包一律 200，内容只有 `{}` 或 `ok`（不含任何业务数据）；
  - 开关默认关、能存能读、与 `intercept` 联动；pref 名以 `elneazh_` 开头（随备份走）；
  - 说明文案里确实写了「云存档/好友/邮件/PVP/本地存档」。
- `android/**`：给上面那条测试用的**极小 stub**（`Context` / `SharedPreferences` / `Log` /
  `AssetManager`），只实现被调到的几个方法，不用 Robolectric，也不下载任何依赖。

## 本地怎么跑

```bash
mkdir -p build/offtest
javac -encoding UTF-8 -nowarn -d build/offtest \
  $(find tools/offline_test -name '*.java') \
  inj/com/shuilize/elneazh/inj/Offline.java \
  inj/com/shuilize/elneazh/inj/Pinyin.java \
  inj/com/shuilize/elneazh/inj/NameFix.java
java -cp build/offtest com.shuilize.elneazh.inj.OfflineTest
python tools/verify_offline.py
```

## 这里测不到、只能真机验的

- `URL.setURLStreamHandlerFactory` 是否真的装得上、Unity 的 UnityWebRequest 是否走 Java URL 栈；
- FileProvider 出 uri 后微信/QQ 能不能真收到；
- 游戏在单机开关下的实际表现（是否停在启动画面）。
  清单见 `HANDOFF.md` 的「待真机验证」。
