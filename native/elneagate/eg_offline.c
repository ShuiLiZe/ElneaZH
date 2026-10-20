// ElneaZH · 单机模式 native 闸门（实现）
//
// 为什么走 il2cpp 运行时 API：游戏是 IL2CPP 打包，C# 方法名只存在于 global-metadata.dat，
// libil2cpp.so 的代码段与字符串表里 grep 不到（已经逐项验证过：MenuCell / InfoMenuPanel /
// AddShopPanel / AbstractPanel 全是 0 命中）。所以只能在运行时按名字解析：
//
//   il2cpp_domain_get            -> domain
//   il2cpp_domain_get_assemblies -> 全部 assembly
//   il2cpp_assembly_get_image    -> image
//   il2cpp_class_from_name       -> class（要 image + namespace + name）
//   il2cpp_class_get_method_from_name -> method（要参数个数）
//   *(void**)method              -> 函数地址（MethodInfo 的第一个字段就是 methodPointer）
//
// 解析到地址之后用 And64InlineHook 挂（与名字闸门同一个引擎、同一份 .so）。
// 任何一步失败都只是「这一项跳过」+ 打日志 ElneaOffline，不改变游戏行为。
//
// 六族钩子（编号与 Java 侧 OfflineGate 的 TARGET_* 一致，见 eg_offline.h）：
//
//   1 PANEL       Add*Panel 工厂方法：单机时返回 NULL，入口**根本不创建**。
//                 只用在「返回 NULL 也安全」的路径上（按钮回调、系统自动弹的面板）。
//   2 HTTP        HttpSystem.httpConnect/connectWWW/AddHttpQueue：单机时直接返回 0
//                 (=NULL/false)，请求一个字节都不发。**不伪造服务器业务数据。**
//   3 GATE        带返回值的 Add*Panel：面板照常创建，但立刻把 PanelEnabled 置 0。
//                 比返回 NULL 安全——调用方拿到的仍是合法对象，后续解引用不会崩。
//   4 BUTTON      按钮 / 入口点击回调：原函数照常跑（游戏自己的显隐判断不动），
//                 单机时追加 set_interactable(false)，点了没反应。
//   5 FLAG        MenuCell 那一族切换：原逻辑跑完后 EnableFlag 置 0、ActiveFlag 清空。
//   6 MASKS       AbstractPanel 的遮罩系列（AddMaskCollider*）：单机时不加遮罩，
//                 玩家不会被困在「不可用」提示后面那层黑幕里。
#include "eg_offline.h"

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "vendor/And64InlineHook.hpp"

#define TAG "ElneaOffline"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define EG_MAX 96   // 钩子上限（六族合计）

// ---------------------------------------------------------------- il2cpp API

typedef struct Il2CppDomain Il2CppDomain;
typedef struct Il2CppAssembly Il2CppAssembly;
typedef struct Il2CppImage Il2CppImage;
typedef struct Il2CppClass Il2CppClass;
typedef struct MethodInfo MethodInfo;

typedef Il2CppDomain *(*fn_domain_get)(void);
typedef const Il2CppAssembly **(*fn_domain_get_assemblies)(const Il2CppDomain *domain, size_t *size);
typedef Il2CppImage *(*fn_assembly_get_image)(const Il2CppAssembly *assembly);
typedef Il2CppClass *(*fn_class_from_name)(const Il2CppImage *image, const char *namespaze,
                                           const char *name);
typedef const MethodInfo *(*fn_class_get_method_from_name)(Il2CppClass *klass, const char *name,
                                                           int args_count);
typedef Il2CppClass *(*fn_object_get_class)(void *obj);
typedef void *(*fn_thread_attach)(Il2CppDomain *domain);

static struct {
    void *handle;
    fn_domain_get domain_get;
    fn_domain_get_assemblies domain_get_assemblies;
    fn_assembly_get_image assembly_get_image;
    fn_class_from_name class_from_name;
    fn_class_get_method_from_name class_get_method_from_name;
    fn_object_get_class object_get_class;
    fn_thread_attach thread_attach;
    int ready;
} g_api;

// dlsym(RTLD_DEFAULT, name)；libil2cpp 还没 dlopen 时补 dlopen(liblibcpp.so, NOLOAD)。
static void *eg_sym(const char *name) {
    void *p = dlsym(RTLD_DEFAULT, name);
    if (p) return p;
    if (!g_api.handle) g_api.handle = dlopen("libil2cpp.so", RTLD_NOW | RTLD_NOLOAD);
    if (g_api.handle) p = dlsym(g_api.handle, name);
    return p;
}

static int eg_api_load(void) {
    if (g_api.ready) return 1;
    g_api.domain_get = (fn_domain_get) eg_sym("il2cpp_domain_get");
    g_api.domain_get_assemblies = (fn_domain_get_assemblies) eg_sym("il2cpp_domain_get_assemblies");
    g_api.assembly_get_image = (fn_assembly_get_image) eg_sym("il2cpp_assembly_get_image");
    g_api.class_from_name = (fn_class_from_name) eg_sym("il2cpp_class_from_name");
    g_api.class_get_method_from_name =
            (fn_class_get_method_from_name) eg_sym("il2cpp_class_get_method_from_name");
    // 下面两个不是必需的：拿不到就只影响「运行期按实例类型找 setter」那部分
    g_api.object_get_class = (fn_object_get_class) eg_sym("il2cpp_object_get_class");
    g_api.thread_attach = (fn_thread_attach) eg_sym("il2cpp_thread_attach");

    if (!g_api.domain_get || !g_api.domain_get_assemblies || !g_api.assembly_get_image
        || !g_api.class_from_name || !g_api.class_get_method_from_name) {
        return 0;
    }
    g_api.ready = 1;
    return 1;
}

// 运行时探测：依赖的核心 API 里能取到几个（给 SelfCheck 用，期望 5）
int eg_of_api_probe(void) {
    const char *names[] = {
            "il2cpp_domain_get", "il2cpp_domain_get_assemblies", "il2cpp_assembly_get_image",
            "il2cpp_class_from_name", "il2cpp_class_get_method_from_name",
            };
    int n = 0;
    for (size_t i = 0; i < sizeof names / sizeof names[0]; i++) {
        if (eg_sym(names[i]) != NULL) n++;
    }
    return n;
}

// 在别的线程上调托管方法前必须先 attach 到 il2cpp 线程
static void eg_attach(void) {
    if (g_api.thread_attach && g_api.domain_get) g_api.thread_attach(g_api.domain_get());
}

// 在每个 assembly 的 image 里找 class（ns 传 "*" 或空表示不限命名空间）
static Il2CppClass *eg_find_class(const char *ns, const char *klass) {
    if (!klass || !klass[0]) return NULL;
    if (!eg_api_load()) return NULL;
    Il2CppDomain *d = g_api.domain_get();
    if (!d) return NULL;
    size_t n = 0;
    const Il2CppAssembly **as = g_api.domain_get_assemblies(d, &n);
    if (!as || n == 0) return NULL;
    int any = (ns == NULL || !ns[0] || (ns[0] == '*' && !ns[1]));
    for (size_t i = 0; i < n; i++) {
        if (!as[i]) continue;
        Il2CppImage *img = g_api.assembly_get_image(as[i]);
        if (!img) continue;
        Il2CppClass *c = g_api.class_from_name(img, any ? "" : ns, klass);
        if (c) return c;
    }
    return NULL;
}

// 解析 method 的地址；method 为空表示取 static 构造（.cctor）/实例构造（.ctor）
// argc < 0 表示「参数个数未知，按 0..8 依次试」。
static void *eg_resolve(void *c, const char *method, int argc) {
    if (!c || !g_api.ready) return NULL;
    Il2CppClass *klass = (Il2CppClass *) c;
    const MethodInfo *mi = NULL;
    if (method == NULL || method[0] == 0) {
        mi = g_api.class_get_method_from_name(klass, ".cctor", -1);
        if (!mi) mi = g_api.class_get_method_from_name(klass, ".ctor", -1);
    } else if (argc >= 0) {
        mi = g_api.class_get_method_from_name(klass, method, argc);
    } else {
        for (int k = 0; k <= 8 && !mi; k++) {
            mi = g_api.class_get_method_from_name(klass, method, k);
        }
    }
    if (!mi) return NULL;
    // MethodInfo 的第一个字段就是 methodPointer
    return *(void **) (const void *) mi;
}

// 运行期：从对象拿它的类（用于「不知道静态类型、但有实例」的 setter 查找）
static Il2CppClass *eg_class_of(void *obj) {
    if (!obj || !g_api.object_get_class) return NULL;
    return g_api.object_get_class(obj);
}

// ---------------------------------------------------------------- 拦截开关
//
// 由 Java 侧 OfflineGate.push(state) 灌进来：'1' 表示单机模式开。**现读**（切换不用重进游戏）。

static volatile char g_offline_flag = '0';
static volatile int g_enabled = 1;

void eg_of_set_enabled(int on) { g_enabled = on ? 1 : 0; }
void eg_of_set_state(const char *s) { if (s && s[0]) g_offline_flag = s[0]; }
static int eg_is_off(void) { return g_enabled && g_offline_flag == '1'; }

// ---------------------------------------------------------------- 钩子表

typedef struct {
    char ns[48];
    char klass[64];
    char method[64];
    void *target;        // 解析到的函数地址
    void *orig;          // trampoline
    int target_kind;     // EG_TARGET_*（见 eg_offline.h）
    int installed;
} eg_hook_entry;

static eg_hook_entry g_hooks[EG_MAX];
static int g_hook_count;
static pthread_mutex_t g_install_lock = PTHREAD_MUTEX_INITIALIZER;

static volatile int g_stats_resolve;   // 解析成功 / 已挂数量
static volatile int g_stats_panel;     // 入口不创建次数
static volatile int g_stats_http;      // 请求 / 登录挡住次数
static volatile int g_stats_ui;        // 面板置灰 / 按钮失效 / 旗帜清零次数
static volatile int g_stats_wait;      // 遮罩挡住次数
static volatile int g_stats_skipped;   // 解析失败（含走了兜底）次数
static volatile int g_stats_fault;     // 回调里发现表项没准备好、回退 NULL 的次数

static int eg_invoke(void *self, const char *method, int argc, void *a0, void *a1) {
    if (!self || !method || !method[0]) return 0;
    if (!eg_api_load()) return 0;
    Il2CppClass *k = eg_class_of(self);
    if (!k) return 0;
    void *fn = eg_resolve(k, method, argc);
    if (!fn) return 0;
    eg_attach();
    switch (argc) {
        case 0: ((void (*)(void *)) fn)(self); break;
        case 1: ((void (*)(void *, void *)) fn)(self, a0); break;
        default: ((void (*)(void *, void *, void *)) fn)(self, a0, a1); break;
    }
    return 1;
}

// 面板置灰：AbstractPanel.set_PanelEnabled(false)（成员名取自 global-metadata.dat，22949 行起）
static int eg_suppress_panel(void *panel) {
    return eg_invoke(panel, "set_PanelEnabled", 1, (void *) 0, NULL);
}

// 按钮失效：Unity UI 的 Selectable.set_interactable(false)
static int eg_suppress_button(void *btn) {
    return eg_invoke(btn, "set_interactable", 1, (void *) 0, NULL);
}

// 菜单项变灰：MenuCell.set_EnableFlag(false) + set_ActiveFlag(null)
static int eg_suppress_menu(void *cell) {
    int any = eg_invoke(cell, "set_EnableFlag", 1, (void *) 0, NULL);
    if (eg_invoke(cell, "set_ActiveFlag", 1, (void *) 0, NULL)) any = 1;
    return any;
}

// 通用转发：按 x0..x7 原样转给 trampoline。
static void *eg_dispatch(eg_hook_entry *e, void *a0, void *a1, void *a2, void *a3,
                         void *a4, void *a5, void *a6, void *a7) {
    typedef void *(*fn_t)(void *, void *, void *, void *, void *, void *, void *, void *);
    fn_t orig = (fn_t) e->orig;

    switch (e->target_kind) {
        case EG_TARGET_PANEL:
            if (eg_is_off()) {
                g_stats_panel++;
                LOGI("offline: skip panel entry %s::%s (entry not created)", e->klass, e->method);
                return NULL;   // 0 / NULL / false 在 AArch64 上是同一个值
            }
            break;

        case EG_TARGET_HTTP:
            if (eg_is_off()) {
                g_stats_http++;
                LOGI("offline: drop request %s::%s (report comm failure)", e->klass, e->method);
                return NULL;   // 不发；交给游戏的通信失败 / 离线分支
            }
            break;

        case EG_TARGET_GATE: {
            void *r = orig ? orig(a0, a1, a2, a3, a4, a5, a6, a7) : NULL;
            if (eg_is_off() && r && eg_suppress_panel(r)) {
                g_stats_ui++;
                LOGI("offline: panel %s::%s built but disabled", e->klass, e->method);
            }
            return r;
        }

        case EG_TARGET_BUTTON: {
            void *r = orig ? orig(a0, a1, a2, a3, a4, a5, a6, a7) : NULL;
            if (eg_is_off() && eg_suppress_button(a0)) {
                g_stats_ui++;
                LOGI("offline: button %s::%s made inert", e->klass, e->method);
            }
            return r;
        }

        case EG_TARGET_FLAG: {
            void *r = orig ? orig(a0, a1, a2, a3, a4, a5, a6, a7) : NULL;
            if (eg_is_off() && eg_suppress_menu(a0)) {
                g_stats_ui++;
                LOGI("offline: menu cell %s::%s greyed", e->klass, e->method);
            }
            return r;
        }

        case EG_TARGET_MASKS:
            if (eg_is_off()) {
                g_stats_wait++;
                LOGI("offline: no mask for %s::%s (page stays usable)", e->klass, e->method);
                return NULL;
            }
            break;

        default:
            break;
    }
    if (!orig) return NULL;
    return orig(a0, a1, a2, a3, a4, a5, a6, a7);
}

#if defined(__aarch64__)
#define EG_DECL_HOOK(N) \
    static void *eg_hook_##N(void *a0, void *a1, void *a2, void *a3, \
                             void *a4, void *a5, void *a6, void *a7) { \
        if (g_hook_count > (N) && g_hooks[N].installed) { \
            return eg_dispatch(&g_hooks[N], a0, a1, a2, a3, a4, a5, a6, a7); \
        } \
        g_stats_fault++; \
        return NULL; \
    }
EG_DECL_HOOK(0)  EG_DECL_HOOK(1)  EG_DECL_HOOK(2)  EG_DECL_HOOK(3)
EG_DECL_HOOK(4)  EG_DECL_HOOK(5)  EG_DECL_HOOK(6)  EG_DECL_HOOK(7)
EG_DECL_HOOK(8)  EG_DECL_HOOK(9)  EG_DECL_HOOK(10) EG_DECL_HOOK(11)
EG_DECL_HOOK(12) EG_DECL_HOOK(13) EG_DECL_HOOK(14) EG_DECL_HOOK(15)
EG_DECL_HOOK(16) EG_DECL_HOOK(17) EG_DECL_HOOK(18) EG_DECL_HOOK(19)
EG_DECL_HOOK(20) EG_DECL_HOOK(21) EG_DECL_HOOK(22) EG_DECL_HOOK(23)
EG_DECL_HOOK(24) EG_DECL_HOOK(25) EG_DECL_HOOK(26) EG_DECL_HOOK(27)
EG_DECL_HOOK(28) EG_DECL_HOOK(29) EG_DECL_HOOK(30) EG_DECL_HOOK(31)
EG_DECL_HOOK(32) EG_DECL_HOOK(33) EG_DECL_HOOK(34) EG_DECL_HOOK(35)
EG_DECL_HOOK(36) EG_DECL_HOOK(37) EG_DECL_HOOK(38) EG_DECL_HOOK(39)
EG_DECL_HOOK(40) EG_DECL_HOOK(41) EG_DECL_HOOK(42) EG_DECL_HOOK(43)
EG_DECL_HOOK(44) EG_DECL_HOOK(45) EG_DECL_HOOK(46) EG_DECL_HOOK(47)
EG_DECL_HOOK(48) EG_DECL_HOOK(49) EG_DECL_HOOK(50) EG_DECL_HOOK(51)
EG_DECL_HOOK(52) EG_DECL_HOOK(53) EG_DECL_HOOK(54) EG_DECL_HOOK(55)
EG_DECL_HOOK(56) EG_DECL_HOOK(57) EG_DECL_HOOK(58) EG_DECL_HOOK(59)
EG_DECL_HOOK(60) EG_DECL_HOOK(61) EG_DECL_HOOK(62) EG_DECL_HOOK(63)
EG_DECL_HOOK(64) EG_DECL_HOOK(65) EG_DECL_HOOK(66) EG_DECL_HOOK(67)
EG_DECL_HOOK(68) EG_DECL_HOOK(69) EG_DECL_HOOK(70) EG_DECL_HOOK(71)
EG_DECL_HOOK(72) EG_DECL_HOOK(73) EG_DECL_HOOK(74) EG_DECL_HOOK(75)
EG_DECL_HOOK(76) EG_DECL_HOOK(77) EG_DECL_HOOK(78) EG_DECL_HOOK(79)
EG_DECL_HOOK(80) EG_DECL_HOOK(81) EG_DECL_HOOK(82) EG_DECL_HOOK(83)
EG_DECL_HOOK(84) EG_DECL_HOOK(85) EG_DECL_HOOK(86) EG_DECL_HOOK(87)
EG_DECL_HOOK(88) EG_DECL_HOOK(89) EG_DECL_HOOK(90) EG_DECL_HOOK(91)
EG_DECL_HOOK(92) EG_DECL_HOOK(93) EG_DECL_HOOK(94) EG_DECL_HOOK(95)
static void *const EG_HOOK_FNS[EG_MAX] = {
    (void *) eg_hook_0,  (void *) eg_hook_1,  (void *) eg_hook_2,  (void *) eg_hook_3,
    (void *) eg_hook_4,  (void *) eg_hook_5,  (void *) eg_hook_6,  (void *) eg_hook_7,
    (void *) eg_hook_8,  (void *) eg_hook_9,  (void *) eg_hook_10, (void *) eg_hook_11,
    (void *) eg_hook_12, (void *) eg_hook_13, (void *) eg_hook_14, (void *) eg_hook_15,
    (void *) eg_hook_16, (void *) eg_hook_17, (void *) eg_hook_18, (void *) eg_hook_19,
    (void *) eg_hook_20, (void *) eg_hook_21, (void *) eg_hook_22, (void *) eg_hook_23,
    (void *) eg_hook_24, (void *) eg_hook_25, (void *) eg_hook_26, (void *) eg_hook_27,
    (void *) eg_hook_28, (void *) eg_hook_29, (void *) eg_hook_30, (void *) eg_hook_31,
    (void *) eg_hook_32, (void *) eg_hook_33, (void *) eg_hook_34, (void *) eg_hook_35,
    (void *) eg_hook_36, (void *) eg_hook_37, (void *) eg_hook_38, (void *) eg_hook_39,
    (void *) eg_hook_40, (void *) eg_hook_41, (void *) eg_hook_42, (void *) eg_hook_43,
    (void *) eg_hook_44, (void *) eg_hook_45, (void *) eg_hook_46, (void *) eg_hook_47,
    (void *) eg_hook_48, (void *) eg_hook_49, (void *) eg_hook_50, (void *) eg_hook_51,
    (void *) eg_hook_52, (void *) eg_hook_53, (void *) eg_hook_54, (void *) eg_hook_55,
    (void *) eg_hook_56, (void *) eg_hook_57, (void *) eg_hook_58, (void *) eg_hook_59,
    (void *) eg_hook_60, (void *) eg_hook_61, (void *) eg_hook_62, (void *) eg_hook_63,
    (void *) eg_hook_64, (void *) eg_hook_65, (void *) eg_hook_66, (void *) eg_hook_67,
    (void *) eg_hook_68, (void *) eg_hook_69, (void *) eg_hook_70, (void *) eg_hook_71,
    (void *) eg_hook_72, (void *) eg_hook_73, (void *) eg_hook_74, (void *) eg_hook_75,
    (void *) eg_hook_76, (void *) eg_hook_77, (void *) eg_hook_78, (void *) eg_hook_79,
    (void *) eg_hook_80, (void *) eg_hook_81, (void *) eg_hook_82, (void *) eg_hook_83,
    (void *) eg_hook_84, (void *) eg_hook_85, (void *) eg_hook_86, (void *) eg_hook_87,
    (void *) eg_hook_88, (void *) eg_hook_89, (void *) eg_hook_90, (void *) eg_hook_91,
    (void *) eg_hook_92, (void *) eg_hook_93, (void *) eg_hook_94, (void *) eg_hook_95,
};
#else
// armv7 没有 hook 引擎：所有安装都会返回负数，Java 侧据此只走 Java 层兜底
static void *const EG_HOOK_FNS[EG_MAX] = { NULL };
#endif

// ---------------------------------------------------------------- 解析 + 挂载

// 解析一行 "ns\tclass\tmethod"，成功就把地址填进 e；返回 1 成功
static int eg_resolve_line(eg_hook_entry *e, const char *line) {
    const char *t1 = strchr(line, '\t');
    if (!t1) return 0;
    const char *t2 = strchr(t1 + 1, '\t');
    size_t n1 = (size_t) (t1 - line);
    size_t n2 = t2 ? (size_t) (t2 - (t1 + 1)) : strlen(t1 + 1);
    if (n1 >= sizeof e->ns || n2 >= sizeof e->klass) return 0;
    memcpy(e->ns, line, n1);
    e->ns[n1] = 0;
    memcpy(e->klass, t1 + 1, n2);
    e->klass[n2] = 0;
    if (t2) {
        size_t n3 = strlen(t2 + 1);
        if (n3 >= sizeof e->method) return 0;
        memcpy(e->method, t2 + 1, n3);
        e->method[n3] = 0;
    } else {
        e->method[0] = 0;
    }
    // 『*』表示不限命名空间；否则先按给的名字空间找，找不到再退回不限
    Il2CppClass *c = NULL;
    if (e->ns[0] && !(e->ns[0] == '*' && !e->ns[1])) {
        c = eg_find_class(e->ns, e->klass);
    }
    if (!c) c = eg_find_class("*", e->klass);
    if (!c) return 0;
    void *fn = eg_resolve(c, e->method, -1);
    if (!fn) return 0;
    e->target = fn;
    return 1;
}

int eg_of_install(int target, const char *names) {
    if (target < EG_TARGET_PANEL || target > EG_TARGET_MASKS) return -5;
    pthread_mutex_lock(&g_install_lock);
    if (!eg_api_load()) {
        pthread_mutex_unlock(&g_install_lock);
        LOGW("il2cpp API not visible yet (libil2cpp not loaded?); nothing hooked");
        return -1;
    }
#if !defined(__aarch64__)
    pthread_mutex_unlock(&g_install_lock);
    LOGW("no hook engine for this ABI; nothing hooked for target %d", target);
    return -4;
#endif
    int before = g_hook_count;   // 注意：不能用「局部计数 + g_hooks[g_hook_count + added]」，
    int skipped = 0;             // 因为 hook 回调里也会读 g_hooks[0..g_hook_count)，必须边挂边提交。
    const char *p = names ? names : "";
    while (*p) {
        const char *nl = strchr(p, '\n');
        size_t len = nl ? (size_t) (nl - p) : strlen(p);
        if (len > 0 && g_hook_count < EG_MAX) {
            char line[192];
            if (len >= sizeof line) len = sizeof line - 1;
            memcpy(line, p, len);
            line[len] = 0;
            if (line[0] != '#' && line[0] != 0 && line[0] != '\r') {
                eg_hook_entry tmp;
                memset(&tmp, 0, sizeof tmp);
                int ok = eg_resolve_line(&tmp, line);
                int dup = 0;
                for (int i = 0; ok && i < g_hook_count; i++) {
                    if (g_hooks[i].target && g_hooks[i].target == tmp.target) { dup = 1; break; }
                }
                if (ok && tmp.target && !dup) {
                    int idx = g_hook_count;
                    eg_hook_entry *slot = &g_hooks[idx];
                    *slot = tmp;
                    slot->target_kind = target;
                    A64HookFunction(slot->target, EG_HOOK_FNS[idx], (void **) &slot->orig);
                    if (slot->orig) {
                        slot->installed = 1;
                        g_hook_count++;          // 提交：从现在起回调读得到这一项
                        g_stats_resolve++;
                    } else {
                        memset(slot, 0, sizeof *slot);
                        skipped++;
                        LOGW("hook engine returned no trampoline for %s", line);
                    }
                } else if (!ok) {
                    skipped++;
                    LOGW("resolve miss: %s", line);
                }
                if (g_hook_count >= EG_MAX) break;
            }
        }
        if (!nl) break;
        p = nl + 1;
    }
    int added = g_hook_count - before;
    pthread_mutex_unlock(&g_install_lock);
    LOGI("install: target=%d hooked %d, skipped %d (total hooked %d)",
         target, added, skipped, g_hook_count);
    return added;
}

int eg_of_uninstall(void) {
    pthread_mutex_lock(&g_install_lock);
    int n = 0;
#if defined(__aarch64__)
    for (int i = 0; i < g_hook_count; i++) {
        if (g_hooks[i].installed && g_hooks[i].target) {
            A64HookFunction(g_hooks[i].target, g_hooks[i].orig, NULL);
            g_hooks[i].installed = 0;
            n++;
        }
    }
#endif
    pthread_mutex_unlock(&g_install_lock);
    LOGI("uninstall: restored %d", n);
    return n;
}

// out = [已挂, 入口不创建, 请求拦截, 面板/按钮置灰, 遮罩, 解析失败, 回调异常]
void eg_of_stats(int out[7]) {
    out[0] = g_hook_count;
    out[1] = g_stats_panel;
    out[2] = g_stats_http;
    out[3] = g_stats_ui;
    out[4] = g_stats_wait;
    out[5] = g_stats_skipped;
    out[6] = g_stats_fault;
}

void eg_of_reset_stats(void) {
    g_stats_panel = 0;
    g_stats_http = 0;
    g_stats_ui = 0;
    g_stats_wait = 0;
    g_stats_skipped = 0;
    g_stats_fault = 0;
}

// 自检用：只解析不调用
int eg_of_resolvable(const char *ns, const char *klass, const char *method) {
    if (!klass || !klass[0] || !method || !method[0]) return 0;
    if (!eg_api_load()) return 0;
    Il2CppClass *c = eg_find_class(ns && ns[0] ? ns : "*", klass);
    if (!c) return 0;
    return eg_resolve(c, method, -1) != NULL;
}

// 自检用：某个类现在能不能看到（不调用任何方法）
int eg_of_class_present(const char *klass) {
    if (!klass || !klass[0]) return 0;
    return eg_find_class("*", klass) != NULL;
}
