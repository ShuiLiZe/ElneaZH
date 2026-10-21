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
    check("中文" in nb and "上传用" in nb, "对照表带表头说明")

# 真机「没有能接收文件的应用」：这条链路逐环钉住（少一环就会退到 Toast 失败）
check("setClipData" in sh, "发送前 setClipData（7.0+ 的 uri 授权跟着 ClipData 走）")
check("grantUriPermission" in sh, "逐个目标 App 显式授权（部分 ROM 不看 ClipData）")
check("queryIntentActivities" in sh, "先问系统谁能接，再按顺序降级")
check("ACTION_SAVE_AS" in sh, "有「存到文件」接管页（分享面板没目标时用）")
check("saveToFolder" in sh, "全都失败时有落盘兜底")
check("MediaScanner" in sh, "落盘后通知媒体库（文件管理器立刻看得到）")
check("FileUriExposedException" in sh or "绝不回退 file://" in sh, "不再退 file://（7.0+ 必炸）")
check("Uri.fromFile" not in sh, "contentUri 不再拼接 file:// uri")
m_send = re.search(r"static\s+boolean\s+send\s*\(.*?\)\s*\{(.*?)\n    \}", sh, re.S)
check(m_send is not None, "Share.send 存在")
if m_send:
    sb = m_send.group(1)
    check(sb.index("startSaveAs") < sb.index("ACTION_SEND"),
          "send 先试「存到文件」，再走系统分享面板")
    check("setClipData" in sb, "send 里设了 ClipData")
    check("GRANT_READ" in sb, "send 里给了读权限 flag")
    check("createChooser" in sb, "send 里包了 createChooser")

sa = read("SaveAsActivity.java")
check("ACTION_CREATE_DOCUMENT" in sa, "保存页用系统保存器（ACTION_CREATE_DOCUMENT）")
check("openOutputStream" in sa, "保存页真的把字节写进选定文件")
check("getContentResolver().openInputStream" in sa, "保存页从 content:// 读源文件")
check("saveToFolder" in sa, "保存页失败也有落盘兜底")

man2 = open(os.path.join(ROOT, "tools", "manifest_zh.py"), encoding="utf-8").read()
check("SaveAsActivity" in man2, "manifest_zh.py 注册了 SaveAsActivity")
check("'exported', 'false'" in man2.replace('A + ', ''), "SaveAsActivity exported=false（外部调不到）")

bp = read("Backup.java")
check("ACTION_OPEN_DOCUMENT" in bp, "导入用 ACTION_OPEN_DOCUMENT")
check('"application/zip"' in bp and '"application/octet-stream"' in bp,
      "导入 MIME = application/zip + application/octet-stream")

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
# 第四批起弹窗只留一行开关说明（版面要求），已购说明挪到 Offline.purchaseHint 供日志/其它入口用
check("purchaseHint" in read("Offline.java"), "Offline.purchaseHint 仍在（日志/其它入口用）")
check("资源照常下载" in sup2, "Support 界面写明资源照常下载")

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

# 弹窗版面（第四批）：一条 QQ 群、收款码折叠、存档组、单机一个开关、账号组、底部链接
check(sup.count("ORANGE, ORANGE_DARK, Color.WHITE") == 1, "整个弹窗只有一个橙色按钮")
for label in ("立即备份", "导出分享", "导入存档", "更换游戏身份", "关闭"):
    check('"%s"' % label in sup, "弹窗按钮「%s」" % label)
for label in ("导出名字对照表", "联系原作者", "赞赏支持"):
    check(label in sup and "link(a, d," in sup, "「%s」是小文字链接" % label)
check("fold.setVisibility(View.GONE)" in sup, "收款码默认折叠")
check("QQ + \"（点这里加入）\"" in sup or "汉化交流 QQ 群" in sup, "顶部一行 QQ 群")
check("addShareSection" not in sup, "旧的「分享 / 导出」整块已并入存档组")
check("ScrollView" in sup, "弹窗可滚动")
b = sup[sup.index("static Dialog build"):sup.index("private static void addSep")]
check("\\n" not in "".join(__import__("re").findall(r'"([^"]*)"', b)), "弹窗文案没有换行（每条 ≤1 行）")
check("addOfflineSection" in b, "单机模式组在弹窗里")
check("caption(a, d, card, \"存档\")" in b and "caption(a, d, card, \"账号\")" in b
      and "caption(a, d, card, \"单机模式\")" in sup, "存档 / 单机模式 / 账号 三个分组标题")

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
FAMILIES = ["", "PANEL", "HTTP", "GATE", "BUTTON", "HIDE", "MASKS"]
for t, msg in ((1, "入口不创建"), (2, "请求拦截"), (3, "面板收掉"), (4, "按钮不执行"),
               (5, "入口隐藏"), (6, "遮罩")):
    check(("TARGET_%d" % t) in og or ("TARGET_" + FAMILIES[t]) in og,
          "第 %d 族 hook 存在（%s）" % (t, msg))
for name in ("TARGET_PANEL", "TARGET_HTTP", "TARGET_GATE", "TARGET_BUTTON", "TARGET_HIDE",
             "TARGET_MASKS"):
    check(name + " = " in og, "常量 %s 有固定值" % name)
# 用户最终要求：入口直接去掉（隐藏），不是置灰。所以这两条反过来钉：
check("set_interactable" not in og, "不靠 set_interactable 置灰（入口要直接消失）")
check("PanelEnabled" not in og, "不靠 PanelEnabled 置灰（入口要直接消失）")
check("SetActive" in og, "隐藏走 GameObject.SetActive(false)（入口直接消失）")
check("EnableFlag" in og and "ActiveFlag" in og,
      "菜单格的 EnableFlag / ActiveFlag 仍在 HIDE 族名单里（刷新时也被藏掉）")
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
check("install started in background" in og,
      "安装立即返回、真装在后台（绝不拖住启动）")
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
check("ElneaOffline" in sup3 and "describe()" in sup3, "闸门现状进 logcat（弹窗不再占版面）")

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
              "EG_TARGET_HIDE", "EG_TARGET_MASKS"):
        check(t in eg, "native 有 %s 分支" % t)
    check("EG_TARGET_FLAG" not in eg,
          "native 不再有置灰族 EG_TARGET_FLAG（改成隐藏）")
    check("eg_hide_object" in eg and "\"SetActive\"" in eg,
          "native 用 GameObject.SetActive 隐藏入口")
    check("eg_close_panel" in eg and "\"CloseAll\"" in eg,
          "GATE 兜底用面板自己的 CloseAll 收掉")
    check("eg_relayout" in eg and "\"Reposition\"" in eg,
          "隐藏后顺手让 Grid/Table 重排（解析不到就跳过）")
    check("__attribute__((unused))\nstatic void *eg_dispatch" in eg,
          "eg_dispatch 在 armv7 分支标 unused（编译无噪音）")
    check("il2cpp_object_get_class" in eg, "native 用 il2cpp_object_get_class 从实例拿类再找 setter")
    check("il2cpp_thread_attach" in eg, "调托管方法前先 attach 到 il2cpp 线程")
    check("eg_is_off()" in eg, "每次回调都现读开关（切开关立刻生效）")
    check("pthread_mutex_lock" in eg, "安装过程加锁（后台线程装、主线程读）")
    check("g_offline_flag" in eg, "开关值缓存在 native，hook 现读")
    check("MethodInfo" in eg, "注释里写清「地址 = MethodInfo 首个字段」（不依赖头文件布局）")
    # 不伪造回包：native 侧不许出现任何组包/造响应对象的逻辑
    for bad in ("il2cpp_object_new", "il2cpp_string_new(\"{"):
        check(bad not in eg, "native 不伪造服务器回包（无 %s）" % bad)

# ---- 兜底闸（请求出口兜底）：这一段的护栏意义是「即使 il2cpp 按名解析全对不上，
# 单机时请求也发不出去」。所以钉死三件事：挂在加密入口上、开关关着时原样转发、不伪造回包。

check("EG_TARGET_ENC" in og, "OfflineGate/头文件里有请求出口兜底族 EG_TARGET_ENC")
check("nativeEncState" in og, "OfflineGate 能查兜底闸是否挂上")
check("nativeOff" in og, "OfflineGate 能查 native 现读的开关值（对账用）")
check("stateMatches" in og, "OfflineGate 有 prefs vs native 对账函数")
check("encHooked" in og, "OfflineGate 有 encHooked()")
if eg:
    check("EG_TARGET_ENC" in eg, "native dispatch 有 EG_TARGET_ENC 分支")
    check("eg_install_enc_fallback" in eg, "native 有兜底闸安装函数")
    check("EG_ENC_OFFSET" in eg, "兜底闸用固定偏移（不依赖按名解析）")
    check("eg_verify_enc" in eg, "兜底闸挂之前校验入口序言（不认识就不挂）")
    check("g_enc_orig" in eg and "eg_enc_hook" in eg, "兜底闸有自己的 trampoline 与回调")
    check("return g_enc_orig ? g_enc_orig(a0) : NULL;" in eg,
          "开关关着时兜底闸原样转发（关掉立刻恢复联网）")
    check("g_enc_hooked = 1;" in eg, "兜底闸挂上后置位")
    # 兜底闸不许伪造回包：它只能返回 NULL，让调用方自己走失败分支
    i = eg.index("static void *eg_enc_hook(void *a0)")
    hook_body = eg[i: eg.index("\n}\n", i)]
    check("il2cpp" not in hook_body and "HTTP" not in hook_body,
          "兜底闸回调里不碰 il2cpp / 不组 HTTP 回包（只拒绝加密）")
    check("return NULL;" in hook_body, "单机时兜底闸返回 NULL（不发货）")

hdr = ""
fp = os.path.join(ROOT, "native/elneagate/eg_offline.h")
if os.path.isfile(fp):
    hdr = io.open(fp, encoding="utf-8").read()
check("EG_TARGET_ENC    7" in hdr, "头文件 EG_TARGET_ENC = 7")
check("EG_TARGET_MAX    8" in hdr, "头文件 EG_TARGET_MAX = 8（含本地装玩族）")
check("EG_TARGET_LPLAY  8" in hdr, "头文件 EG_TARGET_LPLAY = 8（不弹账号输入面板）")
check("EG_STATS_SLOTS   8" in hdr, "头文件统计槽 8 个（含兜底闸状态）")

# 本地装玩族：单机时不许弹账号输入面板 / 通信错误框
og = read("OfflineGate.java")
cro = read_c if False else None  # noqa: F841
check("TARGET_LPLAY = 7" in og.replace("TARGET_LPLAY = 7 ", "TARGET_LPLAY = 7"),
      "Java TARGET_LPLAY 常量")
check("static String lplayTable()" in og, "Java 有 lplayTable()")
check("{ TARGET_LPLAY, 0 }" in og, "lplayTable 已加入 groups()（会被安装）")
check("case TARGET_LPLAY: return lplayTable();" in og, "table() 分发到 lplayTable")
for m in ("openAccountPanel", "AccountInputButtonClick", "requestErrorAlert"):
    check('"%s"' % m in og, "本地装玩入口 %s 已挂钩（单机不弹）" % m)
for m in ("set_disableLocalPlay", "set_isAccountInput", "set_isEmytyLocalAccount"):
    check('"%s"' % m in og, "本地装玩 setter %s 已挂钩（按住为本地存档）" % m)
check('add(b, "TitlePanel"' in og, "本地装玩钩子挂在 TitlePanel（metadata 里那套本地游玩状态就在这里）")

egc_full = ""
fp2 = os.path.join(ROOT, "native/elneagate/eg_offline.c")
if os.path.isfile(fp2):
    egc_full = io.open(fp2, encoding="utf-8").read()
check("case EG_TARGET_LPLAY:" in egc_full, "native 有 EG_TARGET_LPLAY 分支")
check("eg_is_lplay_entry" in egc_full, "native 区分「弹框入口」")
check("eg_is_lplay_off_setter" in egc_full, "native 区分反向 setter（disableLocalPlay）")
check("strcmp(m, \"set_disableLocalPlay\") == 0" in egc_full,
      "set_disableLocalPlay 传 false（true 才是禁用本地装玩）")
check("单机时不弹账号输入面板" in egc_full or "no account/error popup" in egc_full,
      "native 注释写清「不弹账号框」")
# 兜底闸绝不能因为加族而失效
check("EG_TARGET_ENC    7" in hdr and "eg_enc_hook" in egc_full, "兜底闸仍独立于新族")
check("EG_MAX 112" in egc_full, "EG_MAX 扩容到 112（装得下新族）")

# Java 侧：安装必须在后台重试到 il2cpp 出现，而不是等 1ms 就返回
check("INSTALL_WINDOW_MS" in og, "安装窗口是长窗口（不是等 1ms）")
check("workerRunning" in og, "安装有后台单实例保护（不会重复装）")
i = og.index("static int install(final Context ctx, int waitMs)") if "static int install(final Context ctx, int waitMs)" in og else -1
check(i >= 0, "install(Context,int) 签名存在")
if i >= 0:
    seg = og[i: i + 900]
    check("Thread" in seg, "install 立即返回，真装在后台线程")
check("NativeLoad.tryLoad" in og,
      "与名字闸门共用同一个加载器 NativeLoad（避免两边各解一份 / 抢同一个库名）")
check("installRetry" in og, "每族都带重试（il2cpp 未就绪就再等）")
check("lastResults = results.clone()" in og, "每族结果一出来就对外可见（界面能看到真实数字）")

zh3 = read("ZhInit.java")
check("Offline.isOn" in zh3 and "stateMatch" in zh3, "启动时打一条 prefs/native 对账日志")

sup4 = read("Support.java")
check("stateMatches" in sup4, "Support 切开关后对账")
check("ensureInstalled" in sup4, "Support 对账失败时补装闸门")

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
