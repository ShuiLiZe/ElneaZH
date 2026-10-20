#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线验证「单机模式」与「分享/导出」的判定规则（不需要手机、不需要 Gradle）。

做法：不跑 Java，而是把 inj/*.java 里那几个纯规则函数与常量表**按源码解析**出来，重算一遍。
锁住四件最要紧的事：
  1. 判定范围 = 整个游戏服务器（althi.co.jp 的 sns04），不是挑名单拦；
  2. 单机开启时任何 sns04 请求都判定为「本机接住、不外发」，登录/未知接口也算；
  3. 单机关闭时一条都不拦（关掉立刻恢复联网）；
  4. 名字对照表格式稳定（中文<TAB>拼音），导出 txt 粘进表格不乱。

解析不了（源码被大改、函数名变了）就报错退出 —— 这条脚本是护栏，静默跳过等于没护栏。
"""
from __future__ import annotations

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INJ = os.path.join(ROOT, "inj", "com", "shuilize", "elneazh", "inj")

FAILS: list[str] = []
CHECKS = [0]


def read(name: str) -> str:
    path = os.path.join(INJ, name)
    if not os.path.isfile(path):
        FAILS.append("缺少文件: %s" % path)
        return ""
    with io.open(path, encoding="utf-8") as f:
        return f.read()


def check(ok: bool, msg: str) -> None:
    CHECKS[0] += 1
    print(("  ok   " if ok else "  FAIL ") + msg)
    if not ok:
        FAILS.append(msg)


def java_set(src: str, const: str) -> set[str]:
    """从 `static final String[] NAME = { "...", ... }` 里取字符串。"""
    m = re.search(r"%s\s*=\s*\{(.*?)\}" % re.escape(const), src, re.S)
    if not m:
        return set()
    return set(re.findall(r'"([^"]+)"', m.group(1)))


def body_of(src: str, sig: str) -> str | None:
    """取一个方法体（粗粒度：从签名到下一个同缩进的 `}`）。"""
    m = re.search(sig, src, re.S)
    if not m:
        return None
    return m.group(0)


# ---------------------------------------------------------------- Offline.java

off = read("Offline.java")

m_host = re.search(r"static\s+boolean\s+isGameServer\s*\(String\s+\w+\)\s*\{(.*?)\n    \}", off, re.S)
m_int = re.search(r"static\s+boolean\s+intercept\s*\(String\s+\w+\s*,\s*boolean\s+(\w+)\)\s*\{(.*?)\n    \}", off, re.S)
m_rep = re.search(r"static\s+Reply\s+offlineReply\s*\(String\s+\w+\)\s*\{(.*?)\n    \}", off, re.S)

check(m_host is not None, "Offline.isGameServer(String) 存在")
check(m_int is not None, "Offline.intercept(String, boolean) 存在")
check(m_rep is not None, "Offline.offlineReply(String) 存在")

if m_host and m_int:
    host_body = m_host.group(1)
    flag = m_int.group(1)
    int_body = m_int.group(2)

    check("althi.co.jp" in host_body or "althi.co.jp" in off, "isGameServer 认 althi.co.jp")
    check("sns04" in host_body, "isGameServer 认 sns04")
    # host()/path() 里统一做小写规整（isGameServer 直接调它们）
    norm = body_of(off, r"static\s+String\s+host\s*\(String\s+\w+\)\s*\{(.*?)\n    \}") or ""
    check(("toLowerCase" in norm) or ("lc(" in norm),
          "host() 做了小写规整（大写域名也拦得住；isGameServer 走 host()）")
    check("startsWith(\".\")" in host_body or "endsWith(\".\" +" in host_body or "\".\" + DOMAIN" in host_body,
          "isGameServer 按 host 后缀判定（不是 contains，防 althi.co.jp.evil.com）")
    check("contains(\"althi.co.jp\")" not in host_body,
          "isGameServer 不用 contains 匹配域名")
    check("&&" in host_body or "return path" in host_body, "isGameServer 是「域名 且 路径」双条件")

    check(not re.search(r"\b%s\s*=\s*false" % re.escape(flag), int_body),
          "intercept 不会把开关置 false")
    check("isGameServer" in int_body, "intercept 以 isGameServer 为准（不是白名单挑着拦）")
    check(re.search(r"return\s+%s\s*&&\s*isGameServer" % re.escape(flag), int_body) is not None,
          "intercept = 单机开 && 游戏服务器（没有第三个削弱条件）")
    check("isWriteRequest" not in int_body,
          "intercept 不受「只拦写请求」限制（登录等读请求同样拦）")

WRITE = java_set(off, "WRITE")
READ = java_set(off, "READ")
check(len(WRITE) >= 10, "WRITE 端点表非空（%d 个）" % len(WRITE))
check(len(READ) >= 3, "READ 端点表非空（%d 个）" % len(READ))
for name in ("save2.php", "save.php", "kpi.php", "login.php"):
    check(name in WRITE, "WRITE 表含 %s" % name)
for name in ("read.php", "rank.php", "master.php"):
    check(name in READ, "READ 表含 %s" % name)

check("class Reply" in off, "Offline.Reply 回包结构存在")
if m_rep:
    rb = m_rep.group(1)
    check("200" in rb, "离线回包一律 200（客户端才能过启动链路）")
    check('\\"{}\\"' in rb or '"{}"' in rb, "离线回包是空 JSON（不冒充服务器业务数据）")

check(".commit()" in off, "单机开关用 commit() 同步落盘")
check('"elneazh_' in off, "开关存在 shared_prefs/elneazh_*（随备份走）")

# ---------------------------------------------------------------- NetBlocker.java

net = read("NetBlocker.java")
check("setURLStreamHandlerFactory" in net, "NetBlocker 用 stream handler 工厂接住 URL 栈")
check("ProxySelector" in net, "NetBlocker 有 ProxySelector 兜底")
check(re.search(r"static\s+boolean\s+allow\s*\(", net) is not None, "NetBlocker.allow 纯函数存在")
check("Offline" in net, "NetBlocker 的判定复用 Offline（单一真相，不各写一套）")
check("ThreadLocal" in net, "放行分支有递归保镖（否则 https 会自己套自己炸栈）")
check("getOutputStream" in net, "拦截时上传方向被吞掉（一个字节都不出手机）")
check("Reply" in net and "LocalConnection" in net, "命中后走本机回包，不建立 socket")
# 绝不能出现伪造业务数据 / 动服务器标记的痕迹
for bad in ("cheater_flag", "setCheat", "fakeServer", "伪造"):
    check(bad not in net, "NetBlocker 不含「%s」" % bad)

# ---------------------------------------------------------------- Share.java

sh = read("Share.java")
check("ACTION_SEND" in sh, "分享用 ACTION_SEND")
check("createChooser" in sh, "包了选择器（微信/QQ/文件都能选）")
check("FLAG_GRANT_READ_URI_PERMISSION" in sh, "给了读权限 flag（否则对方打不开）")
check("image_provider" in sh, "暂存目录与 @xml/image_share_filepaths 的 files-path 对齐")
check("getFilesDir" in sh, "暂存目录在 filesDir（不是 cacheDir，避免被清掉）")
check("zhfiles" in sh and "AUTHORITY" in sh, "FileProvider authority 与 manifest 一致")
check("getUriForFile" in sh, "通过 FileProvider 出 content:// uri")
check("application/zip" in sh, "导出存档 mime=application/zip")
check("text/plain" in sh, "导出名字表 mime=text/plain")

m_nt = re.search(r"static\s+String\s+nameTableText\s*\(.*?\)\s*\{(.*?)\n    \}", sh, re.S)
check(m_nt is not None, "Share.nameTableText(Map) 存在")
if m_nt:
    nb = m_nt.group(1)
    check("TreeMap" in nb, "对照表按顺序输出（TreeMap 排序）")
    check("\\t" in nb or "\\u0009" in nb, "对照表是 TAB 分隔（粘进表格不乱）")
    check("共 " in nb, "对照表末尾带条数统计")
    check("中文" in nb and "拼音" in nb, "对照表带表头说明")

# ---------------------------------------------------------------- 扩展包 / 资源下载

off_src = read("Offline.java")
check("allowWhileOffline" in off_src, "Offline 有 allowWhileOffline（下载放行规则集中一处）")
check("isAssetDownload" in off_src, "Offline 能识别资源/扩展包下载")
check("isAssetHost" in off_src, "Offline 有资源宿主判定 isAssetHost")
check("downloadHint" in off_src, "Offline 提供「该资源需关闭单机模式」提示语")
check("关闭单机模式" in off_src, "提示语里写了要关闭单机模式")
check("purchaseHint" in off_src, "已购/已解锁内容有「沿用本机记录」说明")

m_aw = re.search(r"static\s+boolean\s+allowWhileOffline\s*\(.*?\)\s*\{(.*?)\n    \}", off_src, re.S)
check(m_aw is not None, "allowWhileOffline 有实现体")
if m_aw:
    body = m_aw.group(1)
    check("isAssetHost" in body, "规则里用到资源宿主判定")
    check("isAssetDownload" in body, "规则里用到资源下载判定")
    # 资源下载必须被放行（用户要求：边玩边下载照常可用）
    check(re.search(r"isAssetDownload\(url\)\)\s*return\s+true", body) is not None,
          "资源下载在单机时返回 true（放行）")
    check("intercept" in body, "其余走闸门裁决（带身份的接口仍拦）")

# 识别函数：.php 绝不能算资源下载（接口带身份，必须拦）
m_ad = re.search(r"static\s+boolean\s+isAssetDownload\s*\(.*?\)\s*\{(.*?)\n    \}", off_src, re.S)
check(m_ad is not None, "isAssetDownload 有实现体")
if m_ad:
    ad = m_ad.group(1)
    check('endsWith(".php")' in ad, "isAssetDownload 显式排除 .php 接口")
    for ext in (".bundle", ".unity3d", ".ab", ".assetbundle"):
        check('"%s"' % ext in ad, "isAssetDownload 认 %s 后缀" % ext)

nb = read("NetBlocker.java")
check("assetHits" in nb, "闸门统计被拦下载（界面提示用）")
check("assetAllowed" in nb, "闸门统计被放行的下载（证明边玩边下载没被误伤）")
check("downloadTip" in nb, "闸门能给界面一句下载提示")
check("pass through ASSET download" in nb, "放行的下载有 logcat 记录")

sup2 = read("Support.java")
check("Offline.purchaseHint" in sup2, "Support 界面展示已购内容说明")
check("照常下载" in sup2, "Support 界面写明资源照常下载")

# ---------------------------------------------------------------- manifest

man = open(os.path.join(ROOT, "tools", "manifest_zh.py"), encoding="utf-8").read()
check("zhfiles" in man, "manifest_zh.py 里 FileProvider authority = zhfiles")
check("grantUriPermissions" in man, "provider 声明了 grantUriPermissions")
check("7f100002" in man, "provider 指向已有的 image_share_filepaths 资源")
check("requestLegacyExternalStorage" in man, "开了 requestLegacyExternalStorage（备份目录可读）")
check("noHistory" in man, "SupportActivity noHistory（弹窗式，不堆栈）")

# ---------------------------------------------------------------- 入口串联

sup = read("Support.java")
check("Offline.set" in sup, "Support 弹窗里能写单机开关")
check("Offline.isOn" in sup, "Support 弹窗读当前状态")
check("shareBackup" in sup, "Support 弹窗有「导出存档」入口")
check("shareNameTable" in sup, "Support 弹窗有「导出名字对照表」入口")
check("Backup.pick" in sup or "restore" in sup, "Support 弹窗有「导入存档」入口")

zh = read("ZhInit.java")
check("NetBlocker.install" in zh, "ZhInit 启动时装网络闸门")

for name, src in (("ZhInit", zh), ("Support", sup)):
    check(src.count("try") >= 3, "%s 的关键步骤都有 try/catch（%d 处）" % (name, src.count("try")))


# ---------------------------------------------------------------- 原生单机闸门（il2cpp 按名解析 + inline hook）
#
# 这一段的护栏意义：native 侧一旦崩了，游戏直接起不来。所以这里逐条钉住
# 「解析不到就跳过」「不伪造回包」「只在开关开着时动手」三件事。

og = read("OfflineGate.java")
check("nativeInstall" in og, "OfflineGate 把 hook 安装交给 native")
check("nativePush" in og, "OfflineGate 把开关状态推给 native（切开关立刻生效，不用重进游戏）")
for t, msg in ((1, "入口不创建"), (2, "请求拦截"), (3, "面板置灰"), (4, "按钮失效"),
               (5, "菜单旗帜"), (6, "遮罩")):
    check(("TARGET_%d" % t) in og or ("TARGET_" + ["", "PANEL", "HTTP", "GATE", "BUTTON",
                                                   "FLAG", "MASKS"][t]) in og,
          "第 %d 族 hook 存在（%s）" % (t, msg))
for name in ("TARGET_PANEL", "TARGET_HTTP", "TARGET_GATE", "TARGET_BUTTON", "TARGET_FLAG",
             "TARGET_MASKS"):
    check(name + " = " in og, "常量 %s 有固定值" % name)
check("set_interactable" in og, "按钮失效走 Unity UI 的 set_interactable")
# 置灰/失效/变灰用到的 setter 名字：真正的调用在 native 的 eg_invoke/eg_suppress_* 里，
# Java 侧的表格与文档也要提到同一批名字，两边一旦走偏就静默不生效，这里把它钉住。
check("PanelEnabled" in og and "EnableFlag" in og and "ActiveFlag" in og,
      "置灰用到的成员名（PanelEnabled / EnableFlag / ActiveFlag）在 OfflineGate 有据可查")
for bad in ("il2cpp_object_new", "il2cpp_string_new"):
    check(bad not in og, "OfflineGate 侧也不造任何服务器对象（无 %s）" % bad)
for panel in ("AddShopPanel", "AddMailPanel", "AddFriendListPanel", "AddPvpPreparePanel",
              "AddKingdomRankingPanel", "AddNoticeInfoPanel", "AddEventPanel", "AddAdPanel",
              "AddPremiumPanel", "AddGachaPanel", "AddSerialCodeInputPanel", "AddAlliancePanel"):
    check("\"" + panel + "\"" in og, "面板名单含 %s" % panel)
check("httpConnect" in og, "请求族含 HttpSystem.httpConnect")
check("AddHttpQueue" in og, "请求族含 HttpSystem.AddHttpQueue")
check("connectWWW" in og, "请求族含 HttpSystem.connectWWW")
check("AssetBundleManager" not in og and "downloadAndCache" not in og,
      "资源下载路径不在拦截名单里（边玩边下必须可用）")
check(og.count("try") >= 10, "OfflineGate 处处 try/catch（%d 处）" % og.count("try"))
check("done.await(waitMs" in og, "安装不等太久（最多等 waitMs，绝不拖住启动）")
check("countLines" in og, "名单行数可数（测试/自检用）")
check("nativeResetStats" in og, "切开关时能清零计数（真机看日志更直观）")

zh2 = read("ZhInit.java")
check("OfflineGate.install" in zh2, "ZhInit 启动时装原生闸门")
if "OfflineGate.install" in zh2:
    i = zh2.index("OfflineGate.install")
    seg = zh2[max(0, i - 200): i + 220]
    check("try" in seg and "catch" in seg, "OfflineGate.install 包在自己的 try/catch 里")

sup3 = read("Support.java")
check("OfflineGate.push" in sup3, "Support 切开关时推状态给原生闸门")
check("OfflineGate.summary" in sup3, "Support 显示原生闸门现状")

sc = read("SelfCheck.java")
check("offline native" in sc, "SelfCheck 报了原生闸门状态")

# native 侧：源码级护栏
eg = ""
for cand in ("native/elneagate/eg_offline.c",):
    fp = os.path.join(ROOT, cand)
    if os.path.isfile(fp):
        eg = io.open(fp, encoding="utf-8").read()
check(eg != "", "native/elneagate/eg_offline.c 存在")
if eg:
    for api in ("il2cpp_domain_get", "il2cpp_domain_get_assemblies", "il2cpp_assembly_get_image",
                "il2cpp_class_from_name", "il2cpp_class_get_method_from_name"):
        check(api in eg, "native 用到 %s（按名字解析，不写死偏移）" % api)
    check("dlsym" in eg, "il2cpp API 用 dlsym 取（版本变了只是解析不到，不会崩）")
    check("ElneaOffline" in eg, "native 日志 TAG 是 ElneaOffline")
    check("if (!orig) return NULL" in eg or "!orig" in eg,
          "没挂上就原样返回（绝不拿空指针去调）")
    for t in ("EG_TARGET_PANEL", "EG_TARGET_HTTP", "EG_TARGET_GATE", "EG_TARGET_BUTTON",
              "EG_TARGET_FLAG", "EG_TARGET_MASKS"):
        check(t in eg, "native 有 %s 分支" % t)
    check("il2cpp_object_get_class" in eg, "native 用 il2cpp_object_get_class 从实例拿类再找 setter")
    check("il2cpp_thread_attach" in eg, "调托管方法前先 attach 到 il2cpp 线程")
    check("eg_is_off()" in eg, "每次回调都现读开关（切开关立刻生效）")
    check("pthread_mutex_lock" in eg, "安装过程加锁（后台线程装、主线程读）")
    check("g_offline_flag" in eg, "开关值缓存在 native，hook 现读")
    check("MethodInfo" in eg, "注释里写清「地址 = MethodInfo 首个字段」（不依赖头文件布局）")
    # 不伪造回包：native 侧不许出现任何组包/造响应对象的逻辑
    for bad in ("il2cpp_object_new", "il2cpp_string_new(\"{"):
        check(bad not in eg, "native 不伪造服务器回包（无 %s）" % bad)

egc = ""
fp = os.path.join(ROOT, "native/elneagate/elneagate.c")
if os.path.isfile(fp):
    egc = io.open(fp, encoding="utf-8").read()
check("eg_offline.h" in egc, "elneagate.c 引了 eg_offline.h")
for jni in ("OfflineGate_nativeInstall", "OfflineGate_nativePush", "OfflineGate_nativeStats",
            "OfflineGate_nativeProbe", "OfflineGate_nativeResolvable",
            "OfflineGate_nativeResetStats", "OfflineGate_nativeClassPresent"):
    check(jni in egc, "JNI 导出 %s" % jni)


bg = io.open(os.path.join(ROOT, "tools", "build_gate.py"), encoding="utf-8").read()
check("eg_offline.c" in bg, "build_gate.py 把 eg_offline.c 编进 libelneagate.so")

bn = io.open(os.path.join(ROOT, ".github", "workflows", "build-check.yml"), encoding="utf-8").read()
check("build_gate.py" in bn, "CI 编译原生闸门")
check("verify_gate.py" in bn, "CI 验证原生闸门产物")

print()
if FAILS:
    print("FAILED: %d 项未通过 / 共 %d 项检查" % (len(FAILS), CHECKS[0]))
    for f in FAILS:
        print("  - " + f)
    sys.exit(1)
print("OK: %d 项检查全部通过" % CHECKS[0])
