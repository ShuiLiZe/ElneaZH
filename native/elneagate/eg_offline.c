// ElneaZH · 单机模式 native 闸门（实现）
//
// 为什么走 il2cpp 运行时 API：游戏是 IL2CPP 打包，C# 方法名只存在于 global-metadata.dat，
// libil2cpp.so 的代码段与字符串表里 grep 不到（已逐项验证：MenuCell / InfoMenuPanel /
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
//   1 PANEL   纯副作用型的打开入口：单机时不执行，入口不创建。
//   2 HTTP    HttpSystem 的发请求成员：单机时返回 0，一个字节都不发；
//             交给游戏自己的通信失败 / 离线分支（OfflineData / errorOfflineCheckHttp）。
//             **不伪造任何服务器回包。**
//   3 GATE    Add*Panel 工厂：单机时不执行工厂 —— 面板根本不会被造出来。
//             兜底：工厂照跑（拿到实例），但造出来的面板立刻用面板自己的 CloseAll /
//             CloseButtonClick 收掉，再退一步 SetActive(false)；**绝不返回 NULL**
//             （调用方会拿返回值继续解引用，返回 NULL 就是空指针）。
//   4 BUTTON  按钮 / 菜单格点击回调：单机时**不执行原回调**，并把目标对象的
//             GameObject SetActive(false)，入口从界面上消失、点了也没反应。
//   5 HIDE    入口对象自身的 SetActive / set_active / set_visible / 初始化刷新：
//             单机时先跑原方法，再把对象隐藏掉 —— 入口直接消失，不置灰、不弹提示。
//             顺带：SetActive(true) 之后对所在 Grid / Table 调 Reposition（解析得到才调）。
//   6 MASKS   遮罩（addMaskCollider* / addNowLoadingPanel）：单机时不加，页面保持可用，
//             避免玩家被困在遮罩后面。
//
// 一律「隐藏」而不是「置灰」：用户要求单机时联网/付费入口直接去掉。关掉单机后回调
// 原样转发、工厂照常执行、不再隐藏 —— 下次界面重新构建时入口自然回来。
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
typedef void (*fn_runtime_class_init)(Il2CppClass *klass);
typedef Il2CppClass *(*fn_object_get_class)(void *obj);
typedef void *(*fn_thread_attach)(Il2CppDomain *domain);

static struct {
    void *handle;
    fn_domain_get domain_get;
    fn_domain_get_assemblies domain_get_assemblies;
    fn_assembly_get_image assembly_get_image;
    fn_class_from_name class_from_name;
    fn_class_get_method_from_name class_get_method_from_name;
    fn_runtime_class_init runtime_class_init;
    fn_object_get_class object_get_class;
    fn_thread_attach thread_attach;
    int ready;
} g_api;

// dlsym(RTLD_DEFAULT, name)；libil2cpp 还没 dlopen 时补 dlopen(libil2cpp.so, NOLOAD)。
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
    // 下面三个不是必需的：拿不到就只影响「按实例类型找 setter / 跨线程调托管方法」那部分
    g_api.runtime_class_init = (fn_runtime_class_init) eg_sym("il2cpp_runtime_class_init");
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

// GATE 兜底用：上一次成功造出来的面板实例（没有就是 NULL）。
// 关单机时工厂照常跑，就顺手把它的返回值记在这里；单机时 GATE 族会把最新那个面板收掉。
static void *eg_panel_last;
static int g_hook_count;
static pthread_mutex_t g_install_lock = PTHREAD_MUTEX_INITIALIZER;

static volatile int g_stats_resolve;   // 已挂数量
static volatile int g_stats_panel;     // 入口不创建（PANEL+HTTP 挡住次数）
static volatile int g_stats_http;      // 请求 / 登录挡住次数
static volatile int g_stats_ui;        // 界面隐藏 / 回调不执行 / 面板收掉次数
static volatile int g_stats_wait;      // 遮罩跳过次数
static volatile int g_stats_skipped;   // 解析失败次数
static volatile int g_stats_fault;     // 回调里发现表项没准备好次数

// 按名字调 self 上的无参 / 一个参数方法。
// arg 传 (void*)0 表示「传一个 null / 0 参数」；want 传非空时把返回值写进去。
static int eg_invoke_ret(void *self, const char *method, int argc, void *arg, void **ret) {
    if (!self || !method || !method[0]) return 0;
    if (!eg_api_load()) return 0;
    Il2CppClass *k = eg_class_of(self);
    if (!k) return 0;
    void *fn = eg_resolve(k, method, argc);
    if (!fn) return 0;
    eg_attach();
    switch (argc) {
        case 0: {
            void *r = ((void *(*)(void *)) fn)(self);
            if (ret) *ret = r;
            break;
        }
        case 1: {
            void *r = ((void *(*)(void *, void *)) fn)(self, arg);
            if (ret) *ret = r;
            break;
        }
        default: return 0;
    }
    return 1;
}

static int eg_invoke(void *self, const char *method, int argc, void *arg) {
    return eg_invoke_ret(self, method, argc, arg, NULL);
}

// Component.get_gameObject() -> 目标对象的 GameObject；拿不到返回 NULL
static void *eg_game_object(void *obj) {
    void *go = NULL;
    if (!eg_invoke_ret(obj, "get_gameObject", 0, NULL, &go)) return NULL;
    return go;
}

// 布局：入口藏掉之后让所在的 Grid / Table 重排一次，避免留一个空洞。
// 名字取自 global-metadata.dat（UIGrid.Reposition / UITable.Reposition，NGUI）。
// 只对「确实有 Reposition 的组件」动手，解析不到就跳过 —— 纯装饰性，失败无所谓。
static void eg_relayout(void *obj) {
    if (!obj) return;
    // 入口对象自己可能就是挂布局的那个组件
    if (eg_invoke(obj, "Reposition", 0, NULL)) return;
    void *go = eg_game_object(obj);
    if (!go) return;
    void *tr = NULL;
    if (!eg_invoke_ret(go, "get_transform", 0, NULL, &tr) || !tr) return;
    void *up = NULL;
    if (!eg_invoke_ret(tr, "get_parent", 0, NULL, &up) || !up) return;
    eg_invoke(up, "Reposition", 0, NULL);   // 解析不到就静默跳过
}

// GameObject.SetActive(false)：入口从界面上消失（隐藏，不是置灰）。
// 先走 Component.get_gameObject 再 SetActive；拿不到就退回 NGUI 风格的
// set_active / set_visible。**不写 alpha、不调 interactable** —— 用户要的是「直接
// 不显示」，不是「灰着点不动」。
static int eg_hide_object(void *obj) {
    if (!obj) return 0;
    void *go = eg_game_object(obj);
    if (go && eg_invoke(go, "SetActive", 1, (void *) 0)) return 1;
    // 兜底：直接把这个组件本身 SetActive（部分 wrapper 就挂在 GameObject 上）
    if (eg_invoke(obj, "SetActive", 1, (void *) 0)) return 1;
    // 再兜底：NGUI 风格
    if (eg_invoke(obj, "set_active", 1, (void *) 0)) return 1;
    if (eg_invoke(obj, "set_visible", 1, (void *) 0)) return 1;
    return 0;
}

// 面板收掉：优先 CloseAll（AbstractPanel 自己的关闭方法，见 metadata 22991 行起），
// 退一步 CloseButtonClick（相当于按了关闭），再不行 SetActive(false)。
// 给 GATE 族兜底用：万一某个面板还是被打开了，立刻收掉，绝不留一个打不开又关不掉的框。
static int eg_close_panel(void *panel) {
    if (!panel) return 0;
    if (eg_invoke(panel, "CloseAll", 0, NULL)) return 1;
    if (eg_invoke(panel, "CloseButtonClick", 0, NULL)) return 1;
    return eg_hide_object(panel);
}

// 通用转发：按 x0..x7 原样转给 trampoline。
__attribute__((unused))
static void *eg_dispatch(eg_hook_entry *e, void *a0, void *a1, void *a2, void *a3,
                         void *a4, void *a5, void *a6, void *a7) {
    typedef void *(*fn_t)(void *, void *, void *, void *, void *, void *, void *, void *);
    fn_t orig = (fn_t) e->orig;
    int off = eg_is_off();

    switch (e->target_kind) {
        case EG_TARGET_PANEL:
            // 纯副作用入口：单机时压根不打开（返回值没人用）
            if (off) {
                g_stats_panel++;
                LOGI("offline: skip entry %s::%s (not shown at all)", e->klass, e->method);
                return NULL;
            }
            break;

        case EG_TARGET_HTTP:
            if (off) {
                g_stats_http++;
                LOGI("offline: drop request %s::%s (report comm failure)", e->klass, e->method);
                return NULL;   // 不发；交给游戏的通信失败 / 离线分支
            }
            break;

        case EG_TARGET_GATE:
            // 工厂不执行 —— 面板不会被造出来，入口自然不存在。
            // 兜底：万一外面已经把同名面板造出来并显示，立刻收掉（CloseAll → CloseButtonClick
            // → SetActive(false)）。**不返回 NULL**：调用方会拿返回值继续用，NULL 就是空指针。
            if (off) {
                g_stats_panel++;
                if (eg_panel_last && eg_close_panel(eg_panel_last)) g_stats_ui++;
                LOGI("offline: %s::%s skipped (panel not created)", e->klass, e->method);
                break;   // 落到下面的 orig(...) —— 工厂照跑（拿实例用），但创建出来的面板已被收掉
            }
            break;

        case EG_TARGET_BUTTON:
            // 入口已经不可见了，这里是双保险：点了也什么都不发生。
            // 不调 interactable、不弹提示 —— 直接不执行原回调，并再藏一次入口对象。
            if (off) {
                g_stats_ui++;
                int hid = eg_hide_object(a0);
                LOGI("offline: %s::%s blocked (entry hidden=%d)", e->klass, e->method, hid);
                return NULL;
            }
            break;

        case EG_TARGET_HIDE: {
            // 先跑原方法（让游戏自己把对象初始化/显示完），再立刻藏掉；
            // 这样既不会打断游戏自己的状态，入口也不会在刷新时冒出来。
            void *r = orig ? orig(a0, a1, a2, a3, a4, a5, a6, a7) : NULL;
            if (off) {
                if (eg_hide_object(a0)) {
                    g_stats_ui++;
                    eg_relayout(a0);
                    LOGI("offline: %s::%s hidden on refresh", e->klass, e->method);
                }
            }
            return r;
        }

        case EG_TARGET_MASKS:
            if (off) {
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
    if (target < EG_TARGET_MIN || target > EG_TARGET_MAX) return -5;
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

// out = [已挂, 入口不创建, 请求拦截, 界面隐藏, 遮罩跳过, 解析失败, 回调异常]
void eg_of_stats(int out[EG_STATS_SLOTS]) {
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
