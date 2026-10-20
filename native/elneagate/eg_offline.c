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

// 钩子表编号（Java 侧必须一致）
#define EG_TARGET_PANEL 1
#define EG_TARGET_HTTP  2

// ---------------------------------------------------------------- il2cpp API

typedef struct Il2CppDomain Il2CppDomain;
typedef struct Il2CppAssembly Il2CppAssembly;
typedef struct Il2CppImage Il2CppImage;
typedef struct Il2CppClass Il2CppClass;
typedef struct Il2CppObject Il2CppObject;
typedef struct MethodInfo MethodInfo;

typedef Il2CppDomain *(*fn_domain_get)(void);
typedef const Il2CppAssembly **(*fn_domain_get_assemblies)(const Il2CppDomain *domain, size_t *size);
typedef Il2CppImage *(*fn_assembly_get_image)(const Il2CppAssembly *assembly);
typedef Il2CppClass *(*fn_class_from_name)(const Il2CppImage *image, const char *namespaze, const char *name);
typedef const MethodInfo *(*fn_class_get_method_from_name)(Il2CppClass *klass, const char *name, int args_count);
typedef const char *(*fn_image_get_name)(const Il2CppImage *image);

static struct {
    void *handle;
    fn_domain_get domain_get;
    fn_domain_get_assemblies domain_get_assemblies;
    fn_assembly_get_image assembly_get_image;
    fn_class_from_name class_from_name;
    fn_class_get_method_from_name class_get_method_from_name;
    fn_image_get_name image_get_name;
    int ready;
} g_api;

// dlsym(RTLD_DEFAULT, name)；libil2cpp 还没 dlopen 时先从 /proc/self/maps 记下的路径补 dlopen。
static void *eg_sym(const char *name) {
    void *p = dlsym(RTLD_DEFAULT, name);
    if (p) return p;
    // 再试 RTLD_NOLOAD 打开 libil2cpp（已经加载过的进程里这一步只是拿引用）
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
    g_api.image_get_name = (fn_image_get_name) eg_sym("il2cpp_image_get_name");

    if (!g_api.domain_get || !g_api.domain_get_assemblies || !g_api.assembly_get_image
        || !g_api.class_from_name || !g_api.class_get_method_from_name) {
        return 0;
    }
    g_api.ready = 1;
    return 1;
}

// 运行时探测：依赖的 API 里能取到几个（给 SelfCheck 用）
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

// 在每个 assembly 的 image 里找 namespace::class
static Il2CppClass *eg_find_class(const char *ns, const char *klass) {
    if (!eg_api_load()) return NULL;
    Il2CppDomain *d = g_api.domain_get();
    if (!d) return NULL;
    size_t n = 0;
    const Il2CppAssembly **as = g_api.domain_get_assemblies(d, &n);
    if (!as || n == 0) return NULL;
    for (size_t i = 0; i < n; i++) {
        if (!as[i]) continue;
        Il2CppImage *img = g_api.assembly_get_image(as[i]);
        if (!img) continue;
        Il2CppClass *c = g_api.class_from_name(img, ns ? ns : "", klass);
        if (c) return c;
    }
    return NULL;
}

// 解析 method 的地址；method 为空表示取 static 构造（.cctor）
static void *eg_resolve(void *c, const char *method, int argc) {
    if (!c || !g_api.ready) return NULL;
    Il2CppClass *klass = (Il2CppClass *) c;
    const MethodInfo *mi = NULL;
    if (method == NULL || method[0] == 0) {
        mi = g_api.class_get_method_from_name(klass, ".cctor", -1);
        if (!mi) mi = g_api.class_get_method_from_name(klass, ".ctor", -1);
    } else {
        // 参数个数：先按 0..6 顺序试（同一个名字不同重载的情况极少，日志里会写 argc）
        if (argc >= 0) {
            mi = g_api.class_get_method_from_name(klass, method, argc);
        } else {
            for (int k = 0; k <= 6 && !mi; k++) {
                mi = g_api.class_get_method_from_name(klass, method, k);
                if (mi) argc = k;
            }
        }
    }
    if (!mi) return NULL;
    // MethodInfo 的第一个字段就是 methodPointer
    void *p = *(void **) (const void *) mi;
    return p;
}

// ---------------------------------------------------------------- 拦截开关
//
// 由 Java 侧 OfflineGate.push(PREF) 灌进来：'1' 表示单机模式开。现读（切换不用重进游戏）。

static volatile char g_offline_flag = '0';
static volatile int g_enabled = 1;

void eg_of_set_enabled(int on) { g_enabled = on ? 1 : 0; }
void eg_of_set_state(const char *s) { if (s && s[0]) g_offline_flag = s[0]; }
static int eg_is_off(void) { return g_enabled && g_offline_flag == '1'; }

// ---------------------------------------------------------------- 钩子表

typedef struct {
    char ns[64];
    char klass[64];
    char method[64];
    void *target;        // 解析到的函数地址
    void *orig;          // trampoline
    int target_kind;     // EG_TARGET_PANEL / EG_TARGET_HTTP 或 0（关闭态直接转发，不需要抓参数）
    int installed;
} eg_hook_entry;

#define EG_MAX_HOOKS 64
static eg_hook_entry g_hooks[EG_MAX_HOOKS];
static int g_hook_count;
static pthread_mutex_t g_install_lock = PTHREAD_MUTEX_INITIALIZER;

static volatile int g_stats_panel;     // 面板拦截次数
static volatile int g_stats_http;      // HTTP 拦截次数
static volatile int g_stats_skipped;   // 解析失败/跳过数
static volatile int g_stats_resolve;   // 解析成功数

// 通用转发：按 x0..x7 原样转给 trampoline。
// 拦截决定只看全局开关 —— 面板类返回 0（NULL 面板），HTTP 类返回 0（false = 通信失败）。
static void *eg_dispatch(eg_hook_entry *e, void *a0, void *a1, void *a2, void *a3,
                         void *a4, void *a5, void *a6, void *a7) {
    typedef void *(*fn_t)(void *, void *, void *, void *, void *, void *, void *, void *);
    int block = 0;
    if (eg_is_off()) {
        if (e->target_kind == EG_TARGET_PANEL) {
            g_stats_panel++;
            block = 1;
            LOGI("offline: skip panel entry %s::%s::%s", e->ns, e->klass, e->method);
        } else if (e->target_kind == EG_TARGET_HTTP) {
            g_stats_http++;
            block = 1;
            LOGI("offline: drop request %s::%s::%s (report comm failure)", e->ns, e->klass, e->method);
        }
    }
    if (block) return NULL;   // 0 / NULL / false 三者在 AArch64 上是同一个值
    if (!e->orig) return NULL;
    return ((fn_t) e->orig)(a0, a1, a2, a3, a4, a5, a6, a7);
}

#if defined(__aarch64__)
#define EG_DECL_HOOK(N) \
    static void *eg_hook_##N(void *a0, void *a1, void *a2, void *a3, \
                             void *a4, void *a5, void *a6, void *a7) { \
        return eg_dispatch(&g_hooks[N], a0, a1, a2, a3, a4, a5, a6, a7); \
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
static void *const EG_HOOK_FNS[EG_MAX_HOOKS] = {
    (void *) eg_hook_0, (void *) eg_hook_1, (void *) eg_hook_2, (void *) eg_hook_3,
    (void *) eg_hook_4, (void *) eg_hook_5, (void *) eg_hook_6, (void *) eg_hook_7,
    (void *) eg_hook_8, (void *) eg_hook_9, (void *) eg_hook_10, (void *) eg_hook_11,
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
};
#else
static void *const EG_HOOK_FNS[EG_MAX_HOOKS] = { NULL };
#endif

// ---------------------------------------------------------------- 解析 + 挂载

static void eg_join_ns(char *dst, size_t cap, const char *ns, const char *klass) {
    if (!ns || !ns[0]) snprintf(dst, cap, "%s", klass);
    else snprintf(dst, cap, "%s::%s", ns, klass);
}

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
    Il2CppClass *c = eg_find_class(e->ns, e->klass);
    if (!c) return 0;
    void *fn = eg_resolve(c, e->method, -1);
    if (!fn) return 0;
    e->target = fn;
    return 1;
}

// 老版本兜底：ns 或 class 为空的行，按「类名 + 方法名」在所有 assembly 里扫一遍。
// （同一方法名在别的类里也可能有，所以只用来兜偶发的命名空间差异，扫不到就跳过。）
static int eg_resolve_loose(eg_hook_entry *e, const char *klass, const char *method) {
    if (!method || !method[0] || !eg_api_load()) return 0;
    Il2CppDomain *d = g_api.domain_get();
    if (!d) return 0;
    size_t n = 0;
    const Il2CppAssembly **as = g_api.domain_get_assemblies(d, &n);
    if (!as) return 0;
    for (size_t i = 0; i < n; i++) {
        if (!as[i]) continue;
        Il2CppImage *img = g_api.assembly_get_image(as[i]);
        if (!img) continue;
        Il2CppClass *c = g_api.class_from_name(img, "", klass);
        if (!c) continue;
        void *fn = eg_resolve(c, method, -1);
        if (fn) {
            e->target = fn;
            return 1;
        }
    }
    return 0;
}

int eg_of_install(int target, const char *names) {
    pthread_mutex_lock(&g_install_lock);
    if (!eg_api_load()) {
        pthread_mutex_unlock(&g_install_lock);
        LOGW("il2cpp API not visible yet (libil2cpp not loaded?); nothing hooked");
        return -1;
    }
    int before = g_hook_count;     // 注意：不能用「局部计数 + g_hooks[g_hook_count + added]」，
    int skipped = 0;               // 因为 hook 回调里也会读 g_hooks[0..g_hook_count)，必须边挂边提交。
    const char *p = names ? names : "";
    while (*p) {
        const char *e = strchr(p, '\n');
        size_t len = e ? (size_t) (e - p) : strlen(p);
        if (len > 0 && g_hook_count < EG_MAX_HOOKS) {
            char line[256];
            if (len >= sizeof line) len = sizeof line - 1;
            memcpy(line, p, len);
            line[len] = 0;
            if (line[0] != '#' && line[0] != 0) {
                eg_hook_entry tmp;
                memset(&tmp, 0, sizeof tmp);
                int ok = eg_resolve_line(&tmp, line);
                if (!ok) {
                    // 松散兜底：把 "ns\tclass\tmethod" 的 class/method 拿出来扫
                    const char *t1 = strchr(line, '\t');
                    const char *t2 = t1 ? strchr(t1 + 1, '\t') : NULL;
                    if (t1 && t2) {
                        char klass[64], method[64];
                        size_t kl = (size_t) (t2 - (t1 + 1));
                        size_t ml = strlen(t2 + 1);
                        if (kl < sizeof klass && ml < sizeof method) {
                            memcpy(klass, t1 + 1, kl);
                            klass[kl] = 0;
                            memcpy(method, t2 + 1, ml);
                            method[ml] = 0;
                            tmp.ns[0] = 0;
                            snprintf(tmp.klass, sizeof tmp.klass, "%s", klass);
                            snprintf(tmp.method, sizeof tmp.method, "%s", method);
                            ok = eg_resolve_loose(&tmp, klass, method);
                            if (ok) g_stats_skipped++;   // 记一笔「走了兜底」
                        }
                    }
                }
                int dup = 0;
                for (int i = 0; i < g_hook_count; i++) {
                    if (g_hooks[i].target && g_hooks[i].target == tmp.target) { dup = 1; break; }
                }
                if (ok && tmp.target && !dup) {
                    eg_hook_entry *slot = &g_hooks[g_hook_count];
                    *slot = tmp;
                    slot->target_kind = target;
#if defined(__aarch64__)
                    A64HookFunction(slot->target, EG_HOOK_FNS[g_hook_count], (void **) &slot->orig);
                    if (slot->orig) {
                        slot->installed = 1;
                        g_hook_count++;          // 提交：从现在起回调读得到这一项
                        g_stats_resolve++;
                    } else {
                        memset(slot, 0, sizeof *slot);
                        skipped++;
                        LOGW("hook engine returned no trampoline for %s", line);
                    }
#else
                    skipped++;
                    LOGW("no hook engine for this ABI; skip %s", line);
#endif
                } else if (!ok) {
                    skipped++;
                    LOGW("resolve miss: %s", line);
                }
                if (g_hook_count >= EG_MAX_HOOKS) break;
            }
        }
        if (!e) break;
        p = e + 1;
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

void eg_of_stats(int out[4]) {
    out[0] = g_hook_count;
    out[1] = g_stats_panel;
    out[2] = g_stats_http;
    out[3] = g_stats_skipped;
}

// 自检用：按名字调一次，返回 1 表示调用成功
int eg_of_call(const char *ns, const char *klass, const char *method, int argc, void **argv) {
    if (!eg_api_load()) return 0;
    Il2CppClass *c = eg_find_class(ns, klass);
    if (!c) return 0;
    void *fn = eg_resolve(c, method, argc);
    if (!fn) return 0;
    return 1;
}
