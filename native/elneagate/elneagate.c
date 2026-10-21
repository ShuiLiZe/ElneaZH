// ElneaZH · 上传前名字闸门（Android 侧胶水 + JNI + inline hook）
//
// 做什么：运行时把 libil2cpp.so 里的 Assembly-CSharp::Cipher::EncryptRJ256(string) 挂上，
// 在它真正加密之前把明文 JSON 里存档块中的中文名换成固定拼音（Cipher.EncryptRJ256 是
// 所有 sns04/*.php 请求体唯一的加密出口，明文只在这里出现一次）。
//
// 安全阀（任何一条不满足就不挂、游戏照常跑）：
//  1. 只认 libil2cpp.so 的基址（dl_iterate_phdr）+ 固定偏移 0x18ad7e4；
//  2. 挂之前逐字比对入口前 20 条指令（40 字节），与当前 base 2.10.7 的字节完全一致才动手；
//  3. hook 引擎用 And64InlineHook（MIT 单文件，自带 LDR 字面量重定位跳板）；
//  4. 替换函数里任何异常（malloc 失败、UTF-8 不合法、zlib 失败）都直接调原函数。
//
// 日志标签 ElneaGate：
//   "hooked EncryptRJ256 @ ..."     挂上了
//   "verify failed (base changed?)" 校验失败，没挂
//   "rewrote N name(s) in <n>B"     每次替换了几个名字
#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <malloc.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/mman.h>

#include "eg_log.h"
#include "eg_names.h"
#include "eg_offline.h"
#include "eg_sym.h"
#include "elneagate_jni.h"

#include "vendor/And64InlineHook.hpp"

#define TAG "ElneaGate"
#define LOGI(...) eg_plog(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) eg_plog(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) eg_plog(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---- 目标：Cipher::EncryptRJ256(string)，base 2.10.7 (base.apk sha 5117bbaa...) ----
#define ENC_OFFSET      0x18ad7e4u
#define ENC_PROLOGUE_LEN 40
#define ENC_VERIFY_LEN 12
static const unsigned char ENC_PROLOGUE[ENC_PROLOGUE_LEN] = {
    // 0x18ad7e4
    0xfe, 0x0f, 0x1d, 0xf8,   // str  x30, [sp, #-0x30]!
    0xf6, 0x57, 0x01, 0xa9,   // stp  x22, x21, [sp, #0x10]
    0xf4, 0x4f, 0x02, 0xa9,   // stp  x20, x19, [sp, #0x20]
    0xf5, 0x0f, 0x01, 0x90,   // adrp x21, #0x3aa9000
    0xf4, 0x0f, 0x02, 0x90,   // adrp x20, #0x3841000
    0xf3, 0x03, 0x00, 0xaa,   // mov  x19, x0
    0xa8, 0x7b, 0x40, 0x39,   // ldrb w8, [x21, #0xef9]
    0x94, 0x6e, 0x40, 0xf9,   // ldr  x20, [x20, #0xd98]
    0xa8, 0x00, 0x38, 0x36,   // tbnz w8, #0, #0x18ad840
    0x00, 0x00, 0x00, 0x90,   // adrp x0, #0x3841000
};

typedef void *(*enc_fn_t)(void *il2cpp_string);

static enc_fn_t g_orig_enc;
static void *g_base;
static int g_hooked;
static void *g_enc_target;   // 实际挂上的 EncryptRJ256（按名字解析）
// nativeInstall 会被 Java 侧每 500ms 轮询调进来：失败日志按次数说话，不刷屏
#define EG_INSTALL_GIVEUP_TICKS 40
static int g_install_fail_ticks;
// 诊断（给 selfcheck 看）：失败码 / 校验结果 / 第一处不匹配 / 附加说明
static volatile int g_diag_code = -1;
static volatile int g_diag_verify = -1;     // 1 = 40B 对上了；0 = 没对上；-1 = 没读
static volatile int g_diag_mismatch = -1;   // 第一处不匹配的下标
static volatile int g_diag_actual = -1;
static volatile int g_diag_expect = -1;
static volatile int g_diag_detail;
static __thread int g_depth;

// il2cpp 导出函数（运行时按名字取，避免依赖具体版本）
typedef void *(*il2cpp_string_new_t)(const char *);
typedef void *(*il2cpp_string_new_utf16_t)(const uint16_t *, int32_t);
static il2cpp_string_new_t g_string_new;
static il2cpp_string_new_utf16_t g_string_new_utf16;

// ---- 名字映射表：中文 -> 拼音（由 Java 侧从 assets 表 + 别名表灌进来，保证与 Java 同一份数据）----
typedef struct {
    char *zh;      // UTF-8
    int zh_len;
    char *ascii;
} eg_pair;

static eg_pair *g_pairs;
static int g_pair_count;
static pthread_mutex_t g_pair_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile int g_stats_rewrites;   // 累计改过多少个名字
static volatile int g_stats_calls;      // 累计进入 EncryptRJ256 的次数

// ---- 单字拼音表（cp 升序，二分查找）：整名不在表里（玩家自己取的名字、表外 NPC）时逐字转拼音 ----
// 以前只查整名表：玩家名不在表里就原样上传中文 —— 防封最关键的那一类名字反而漏掉。
typedef struct {
    unsigned cp;
    char py[8];
} eg_char_py;
static eg_char_py *g_chars;
static int g_char_count;

static int eg_char_cmp(const void *a, const void *b) {
    unsigned x = ((const eg_char_py *) a)->cp, y = ((const eg_char_py *) b)->cp;
    return x < y ? -1 : (x > y ? 1 : 0);
}

static const char *eg_char_lookup(unsigned cp) {
    int lo = 0, hi = g_char_count - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (g_chars[mid].cp == cp) return g_chars[mid].py;
        if (g_chars[mid].cp < cp) lo = mid + 1; else hi = mid - 1;
    }
    return NULL;
}

static int eg_utf8_cp(const unsigned char *s, size_t n, size_t i, unsigned *cp) {
    unsigned c = s[i];
    if (c < 0x80) { *cp = c; return 1; }
    int len;
    if ((c & 0xE0) == 0xC0) { len = 2; *cp = c & 0x1F; }
    else if ((c & 0xF0) == 0xE0) { len = 3; *cp = c & 0x0F; }
    else if ((c & 0xF8) == 0xF0) { len = 4; *cp = c & 0x07; }
    else return 0;
    if (i + (size_t) len > n) return 0;
    for (int k = 1; k < len; k++) {
        unsigned d = s[i + k];
        if ((d & 0xC0) != 0x80) return 0;
        *cp = (*cp << 6) | (d & 0x3F);
    }
    return len;
}

// 逐字转：ASCII 字母数字原样；汉字 -> 首字母大写拼音；其它符号（·、全角空格等）丢掉。
// 有一个汉字查不到就整体放弃（返回 0，原样上传 + WARN），绝不产出半截名字。
static int eg_map_chars(const char *name, size_t name_len, char *out, size_t cap, size_t *out_len) {
    if (!g_chars || g_char_count == 0) return 0;
    const unsigned char *u = (const unsigned char *) name;
    size_t i = 0, o = 0;
    int han = 0;
    while (i < name_len) {
        unsigned cp;
        int k = eg_utf8_cp(u, name_len, i, &cp);
        if (k == 0) return 0;
        i += (size_t) k;
        if (cp < 0x80) {
            if ((cp >= '0' && cp <= '9') || (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')
                    || cp == '_') {
                if (o + 1 >= cap) return 0;
                out[o++] = (char) cp;
            }
            continue;
        }
        const char *py = eg_char_lookup(cp);
        if (!py) {
            if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)) return 0;
            continue;
        }
        size_t pl = strlen(py);
        if (o + pl >= cap) return 0;
        memcpy(out + o, py, pl);
        o += pl;
        han++;
    }
    if (!han || o == 0 || o > 255) return 0;
    *out_len = o;
    return 1;
}

JNIEXPORT jint JNICALL
Java_com_shuilize_elneazh_inj_NameGate_nativeSetChars(JNIEnv *env, jclass cls, jstring table) {
    (void) cls;
    if (!table) return 0;
    const char *s = (*env)->GetStringUTFChars(env, table, NULL);
    if (!s) return -1;
    size_t n = strlen(s);
    eg_char_py *arr = (eg_char_py *) calloc(n / 3 + 8, sizeof(eg_char_py));
    int cnt = 0;
    size_t i = 0;
    while (arr && i < n) {
        size_t e = i;
        while (e < n && s[e] != '\n') e++;
        const char *line = s + i;
        size_t ln = e - i;
        const char *tab = memchr(line, '\t', ln);
        unsigned cp;
        if (tab && tab > line && eg_utf8_cp((const unsigned char *) line, (size_t) (tab - line), 0, &cp) > 0
                && cp >= 0x80) {
            size_t pl = ln - (size_t) (tab - line) - 1;
            if (pl > 0 && pl < sizeof arr[cnt].py) {
                int ok = 1;
                for (size_t k = 0; k < pl; k++) {
                    char c = tab[1 + k];
                    if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) { ok = 0; break; }
                }
                if (ok) {
                    arr[cnt].cp = cp;
                    memcpy(arr[cnt].py, tab + 1, pl);
                    arr[cnt].py[pl] = 0;
                    cnt++;
                }
            }
        }
        i = e + 1;
    }
    (*env)->ReleaseStringUTFChars(env, table, s);
    if (!arr) return -1;
    qsort(arr, (size_t) cnt, sizeof *arr, eg_char_cmp);
    pthread_mutex_lock(&g_pair_lock);
    free(g_chars);
    g_chars = arr;
    g_char_count = cnt;
    pthread_mutex_unlock(&g_pair_lock);
    LOGI("pinyin chars loaded: %d", cnt);
    return cnt;
}

static int eg_map(void *ud, const char *name, size_t name_len, char *out, size_t cap, size_t *out_len) {
    (void) ud;
    pthread_mutex_lock(&g_pair_lock);
    for (int i = 0; i < g_pair_count; i++) {
        if ((size_t) g_pairs[i].zh_len == name_len && memcmp(g_pairs[i].zh, name, name_len) == 0) {
            size_t n = strlen(g_pairs[i].ascii);
            if (n == 0 || n >= cap) { pthread_mutex_unlock(&g_pair_lock); return 0; }
            memcpy(out, g_pairs[i].ascii, n);
            *out_len = n;
            pthread_mutex_unlock(&g_pair_lock);
            return 1;
        }
    }
    // 整名不在表里：逐字拼音兜底（玩家名、表外 NPC）
    int r = eg_map_chars(name, name_len, out, cap, out_len);
    pthread_mutex_unlock(&g_pair_lock);
    return r;
}

// il2cpp String 布局：+0x10 = int32 length，+0x14 = UTF-16 字符
static char *eg_string_to_utf8(void *s, size_t *out_len) {
    if (!s) return NULL;
    int32_t len = *(int32_t *) ((char *) s + 0x10);
    if (len < 0 || len > (1 << 20)) return NULL;         // 1M 字符上限，明显不是请求体就放弃
    const uint16_t *u = (const uint16_t *) ((char *) s + 0x14);
    char *buf = (char *) malloc((size_t) len * 3 + 4);
    if (!buf) return NULL;
    size_t o = 0;
    for (int32_t i = 0; i < len; i++) {
        unsigned cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            unsigned lo = u[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                i++;
            }
        }
        if (cp < 0x80) buf[o++] = (char) cp;
        else if (cp < 0x800) {
            buf[o++] = (char) (0xC0 | (cp >> 6));
            buf[o++] = (char) (0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            buf[o++] = (char) (0xE0 | (cp >> 12));
            buf[o++] = (char) (0x80 | ((cp >> 6) & 0x3F));
            buf[o++] = (char) (0x80 | (cp & 0x3F));
        } else {
            buf[o++] = (char) (0xF0 | (cp >> 18));
            buf[o++] = (char) (0x80 | ((cp >> 12) & 0x3F));
            buf[o++] = (char) (0x80 | ((cp >> 6) & 0x3F));
            buf[o++] = (char) (0x80 | (cp & 0x3F));
        }
    }
    buf[o] = 0;
    *out_len = o;
    return buf;
}

#if defined(__aarch64__)
static void *eg_hook(void *plain_str) {
    g_stats_calls++;
    // 单机：每个请求体的唯一出口在这里断掉 —— 不加密、不发送、不伪造回包（返回 NULL，
    // 调用方走它自己的发送失败分支）。offline 的兜底闸不再另挂这个入口。
    if (eg_of_is_off()) return NULL;
    if (g_depth > 0 || !g_orig_enc || !g_string_new) return g_orig_enc ? g_orig_enc(plain_str) : NULL;
    if (plain_str == NULL) return g_orig_enc(plain_str);

    g_depth++;
    char *plain = NULL;
    char *fixed = NULL;
    size_t plain_len = 0, fixed_len = 0;
    eg_filter_ctx ctx;
    memset(&ctx, 0, sizeof ctx);
    ctx.map = eg_map;

    plain = eg_string_to_utf8(plain_str, &plain_len);
    if (plain && plain_len > 0) {
        fixed = eg_filter_json(&ctx, plain, plain_len, &fixed_len);
    }
    void *ret;
    if (fixed && ctx.hits > 0) {
        void *ns = g_string_new(fixed);
        if (ns) {
            g_stats_rewrites += ctx.hits;
            LOGI("rewrote %d name(s) in %uB", ctx.hits, (unsigned) plain_len);
            ret = g_orig_enc(ns);
        } else {
            LOGW("il2cpp_string_new failed, passing original");
            ret = g_orig_enc(plain_str);
        }
    } else {
        ret = g_orig_enc(plain_str);
    }
    free(plain);
    free(fixed);
    g_depth--;
    return ret;
}

#endif

// ------------------------------------------------------------------ hook 引擎

// ------------------------------------------------------------------ 基址与校验

static void *eg_find_libil2cpp(void) {
    // 统一走 eg_sym 的缓存（dl_iterate_phdr 优先，退回 maps）。
    // 以前这里自己再 dl_iterate_phdr 一遍、eg_offline 里又各解析一遍 —— 三份结果还不一定一致；
    // 真机崩的那次就是「一边说 base=0x0，另一边拿 0 去加偏移」。现在只有一份。
    uintptr_t b = eg_lib_base("libil2cpp.so");
    return b ? (void *) b : NULL;
}

// 校验入口 40 字节；返回 1 表示匹配
static int eg_verify(void *addr) {
    unsigned char cur[ENC_PROLOGUE_LEN];
    memcpy(cur, addr, sizeof cur);
    // 逐字节比：失败时记下第一个不同的位置和「实际/期望」两个字节，真机上直接能对
    g_diag_mismatch = 0;
    // 只比前 12 字节（str x30 / stp / stp 建栈帧）：函数身份已经由按名字解析保证，
    // 这里只防「入口不是普通建栈帧、inline 搬指令不安全」。后面的 adrp 立即数随构建变化，不比。
    for (size_t i = 0; i < ENC_VERIFY_LEN; i++) {
        if (cur[i] != ENC_PROLOGUE[i]) {
            g_diag_mismatch = (int) i;
            g_diag_actual = cur[i];
            g_diag_expect = ENC_PROLOGUE[i];
            break;
        }
    }
    if (memcmp(cur, ENC_PROLOGUE, ENC_VERIFY_LEN) != 0) return 0;
    return 1;
}

// ------------------------------------------------------------------ 拼音表装载

static void eg_free_pairs(void) {
    pthread_mutex_lock(&g_pair_lock);
    for (int i = 0; i < g_pair_count; i++) {
        free(g_pairs[i].zh);
        free(g_pairs[i].ascii);
    }
    free(g_pairs);
    g_pairs = NULL;
    g_pair_count = 0;
    pthread_mutex_unlock(&g_pair_lock);
}

// 从 Java 传进来的 "中文\t拼音\n" 装载（覆盖式）
JNIEXPORT jint JNICALL
Java_com_shuilize_elneazh_inj_NameGate_nativeSetNames(JNIEnv *env, jclass cls, jstring table) {
    (void) cls;
    long long t0 = eg_now_ms();
    EG_I("names: setNames enter (thread on this call is the JVM caller)");
    if (!table) { eg_free_pairs(); EG_I("names: cleared"); return 0; }
    const char *s = (*env)->GetStringUTFChars(env, table, NULL);
    if (!s) return -1;
    size_t n = strlen(s);
    eg_pair *arr = (eg_pair *) calloc(n / 3 + 8, sizeof(eg_pair));
    int cnt = 0;
    size_t i = 0;
    while (i < n) {
        size_t e = i;
        while (e < n && s[e] != '\n') e++;
        const char *line = s + i;
        size_t ln = e - i;
        const char *tab = memchr(line, '\t', ln);
        if (tab && tab > line) {
            size_t zl = (size_t) (tab - line);
            size_t al = ln - zl - 1;
            if (zl > 0 && zl < 128 && al > 0 && al < 64) {
                arr[cnt].zh = (char *) malloc(zl + 1);
                arr[cnt].ascii = (char *) malloc(al + 1);
                if (arr[cnt].zh && arr[cnt].ascii) {
                    memcpy(arr[cnt].zh, line, zl);
                    arr[cnt].zh[zl] = 0;
                    memcpy(arr[cnt].ascii, tab + 1, al);
                    arr[cnt].ascii[al] = 0;
                    arr[cnt].zh_len = (int) zl;
                    cnt++;
                } else {
                    free(arr[cnt].zh);
                    free(arr[cnt].ascii);
                }
            }
        }
        i = e + 1;
    }
    (*env)->ReleaseStringUTFChars(env, table, s);
    eg_free_pairs();
    pthread_mutex_lock(&g_pair_lock);
    g_pairs = arr;
    g_pair_count = cnt;
    pthread_mutex_unlock(&g_pair_lock);
    LOGI("names loaded: %d pair(s)", cnt);
    EG_I("names: setNames done %d pair(s) in %lldms", cnt, eg_now_ms() - t0);
    return cnt;
}

// 统计：[0]=累计替换名字数 [1]=累计调用次数 [2]=是否已挂 [3]=表大小
JNIEXPORT jintArray JNICALL
Java_com_shuilize_elneazh_inj_NameGate_nativeStats(JNIEnv *env, jclass cls) {
    (void) cls;
    jint v[4];
    v[0] = g_stats_rewrites;
    v[1] = g_stats_calls;
    v[2] = g_hooked;
    v[3] = g_pair_count;
    jintArray a = (*env)->NewIntArray(env, 4);
    if (a) (*env)->SetIntArrayRegion(env, a, 0, 4, v);
    return a;
}

// 挂载：返回 0 成功、-1 失败（失败原因写日志）
JNIEXPORT jint JNICALL
Java_com_shuilize_elneazh_inj_NameGate_nativeInstall(JNIEnv *env, jclass cls) {
    (void) cls;
    if (g_hooked) return 0;
    long long t0 = eg_now_ms();
    // 这里的线程名/tid 由 nativeLogBegin 那边打；这里只保证「进入」这条一定有
    EG_I("nativeInstall enter (thread=%s tid=%ld)", eg_thread_tag(), eg_tid());

    // 先看库在不在。不在就立刻 -1（让 Java 侧 200ms 后再来），**不解析符号**：
    // 每次解析都要过 eg_sym 四级兜底，库还没来时纯属白烧。
    if (!eg_lib_present()) {
        if (g_install_fail_ticks++ == 0 || g_install_fail_ticks >= EG_INSTALL_GIVEUP_TICKS) {
            LOGW("libil2cpp not mapped yet (tick %d); waiting for Unity to dlopen it",
                 g_install_fail_ticks);
        }
        return -1;
    }

    // dlsym(RTLD_DEFAULT) 在 Android 的链接器命名空间里经常看不到 Unity 自己 dlopen 的
    // libil2cpp.so：走 eg_sym 的四级兜底（默认作用域 → 句柄 → maps 路径句柄 → ELF .dynsym）。
    // maps 现在是一次快照（200ms TTL），不再是每个符号读一遍整张 maps。
    g_string_new = (il2cpp_string_new_t) eg_sym_find("il2cpp_string_new");
    g_string_new_utf16 = (il2cpp_string_new_utf16_t) eg_sym_find("il2cpp_string_new_utf16");
    if (!g_string_new) {
        // 只在第一次、且真正放弃时打一条（Java 侧每 200ms 会调进来，这里不再刷屏）
        if (g_install_fail_ticks++ == 0 || g_install_fail_ticks >= EG_INSTALL_GIVEUP_TICKS) {
            LOGW("il2cpp_string_new unresolved after %d tries (stage=%s, base=%p); %s",
                 g_install_fail_ticks, eg_sym_stage(),
                 (void *) eg_lib_base("libil2cpp.so"),
                 g_install_fail_ticks >= EG_INSTALL_GIVEUP_TICKS
                     ? "still unresolved, Java layer only until libil2cpp shows up"
                     : "will retry");
        }
        return -1;
    }
    EG_I("name gate: il2cpp_string_new @ %p via %s (%lldms)", (void *) g_string_new,
         eg_sym_stage(), eg_now_ms() - t0);
    g_diag_code = 1;
    g_diag_detail = 1;   // 能拿到 string_new，说明命名空间解析通了

    void *base = eg_find_libil2cpp();
    if (!base) base = (void *) eg_lib_base("libil2cpp.so");
    if (!base) {
        g_diag_detail = 2;
        LOGW("libil2cpp.so base not found (dl_iterate_phdr + maps 都是 0); skip");
        return -1;
    }
    g_base = base;
    // 按名字找 Cipher::EncryptRJ256（il2cpp 运行时 API），不再信固定偏移 + 手抄的 40B 序言表：
    // 真机 19:43 表里 @0x10 的 adrp 字节是错的（实际 0xb4 / 表 0xf4），闸门从没挂上过。
    int nf = 0;
    void *target = eg_of_method_ptr("Cipher", "EncryptRJ256", 1, &nf);
    if (!target) {
        g_diag_detail = nf ? 5 : 6;
        if (nf) {
            g_diag_code = 2;
            LOGW("name gate: Cipher::EncryptRJ256 not found by name (runtime ready); not hooking");
            return -2;
        }
        return -1;   // 运行时还没初始化完，稍后再来
    }
    void *fixed = (void *) ((uintptr_t) base + ENC_OFFSET);
    EG_I("name gate: EncryptRJ256 by name @ %p (base+0x%lx; fixed offset %s) (%lldms)", target,
         (unsigned long) ((uintptr_t) target - (uintptr_t) base),
         target == fixed ? "matches" : "DIFFERS", eg_now_ms() - t0);
    if (!eg_verify(target)) {
        const unsigned char *cur = (const unsigned char *) target;
        g_diag_code = 2;
        g_diag_verify = 0;
        g_diag_detail = 3;
        LOGW("verify failed at %p: first mismatch @%d actual=0x%02x expect=0x%02x "
             "head=%02x%02x%02x%02x%02x%02x%02x%02x %02x%02x%02x%02x%02x%02x%02x%02x (not hooking)",
             target, g_diag_mismatch, (unsigned) g_diag_actual, (unsigned) g_diag_expect,
             cur[0], cur[1], cur[2], cur[3], cur[4], cur[5], cur[6], cur[7],
             cur[8], cur[9], cur[10], cur[11], cur[12], cur[13], cur[14], cur[15]);
        return -2;
    }
    g_diag_verify = 1;
#if defined(__aarch64__)
    EG_I("name gate: prologue ok, writing hook slot (%lldms)", eg_now_ms() - t0);
    A64HookFunction(target, (void *) eg_hook, (void **) &g_orig_enc);
#else
    g_diag_code = 4;
    // 单机整体降级到 Java 层；不再重复刷同一条日志
    if (!g_diag_detail) {
        g_diag_detail = 4;
        LOGW("no hook engine for this ABI (arm64 only yet); Java layer only, game runs unchanged");
    }
    return -4;
#endif
    if (!g_orig_enc) {
        g_diag_code = 3;
        LOGW("hook engine returned no trampoline at %p+0x%x", base, ENC_OFFSET);
        return -3;
    }
    g_enc_target = target;
    g_hooked = 1;
    g_diag_code = 0;
    EG_I("name gate: hooked EncryptRJ256 @ %p+0x%x (base %p, trampoline %p, %lldms)",
         target, ENC_OFFSET, base, (void *) g_orig_enc, eg_now_ms() - t0);
    return 0;
}

// 自检用：直接喂一段明文，返回过滤后的 UTF-8（没有改动返回 NULL）
JNIEXPORT jstring JNICALL
Java_com_shuilize_elneazh_inj_NameGate_nativeFilterJson(JNIEnv *env, jclass cls, jstring plain) {
    (void) cls;
    if (!plain) return NULL;
    const char *s = (*env)->GetStringUTFChars(env, plain, NULL);
    if (!s) return NULL;
    eg_filter_ctx ctx;
    memset(&ctx, 0, sizeof ctx);
    ctx.map = eg_map;
    size_t out_len = 0;
    char *out = eg_filter_json(&ctx, s, strlen(s), &out_len);
    (*env)->ReleaseStringUTFChars(env, plain, s);
    if (!out) return NULL;
    jstring r = (*env)->NewStringUTF(env, out);
    free(out);
    return r;
}

// 自检用：base64(zlib(字节)) 过滤，返回新的 base64（无改动返回 NULL）
JNIEXPORT jstring JNICALL
Java_com_shuilize_elneazh_inj_NameGate_nativeFilterBlob(JNIEnv *env, jclass cls, jstring b64) {
    (void) cls;
    if (!b64) return NULL;
    const char *s = (*env)->GetStringUTFChars(env, b64, NULL);
    if (!s) return NULL;
    size_t n = strlen(s);
    size_t out_len = 0;
    char *out = NULL;
    // 复用 eg_filter_one 的逻辑：这里直接构造一个 JSON 片段走同一条路径保证一致
    char *fake = (char *) malloc(n + 32);
    if (fake) {
        size_t fl = 0;
        fl += (size_t) sprintf(fake, "{\"k\":\"");
        memcpy(fake + fl, s, n);
        fl += n;
        fake[fl++] = '"';
        fake[fl++] = '}';
        fake[fl] = 0;
        eg_filter_ctx ctx;
        memset(&ctx, 0, sizeof ctx);
        ctx.map = eg_map;
        size_t ol = 0;
        char *res = eg_filter_json(&ctx, fake, fl, &ol);
        free(fake);
        if (res) {
            // 抽出 {"k":"..."} 里的值
            const char *p = strstr(res, "\"k\":\"");
            if (p) {
                p += 5;
                const char *q = strrchr(p, '"');
                if (q) {
                    out_len = (size_t) (q - p);
                    out = (char *) malloc(out_len + 1);
                    if (out) { memcpy(out, p, out_len); out[out_len] = 0; }
                }
            }
            free(res);
        }
    }
    (*env)->ReleaseStringUTFChars(env, b64, s);
    if (!out) return NULL;
    jstring r = (*env)->NewStringUTF(env, out);
    free(out);
    return r;
}

JNIEXPORT jboolean JNICALL
Java_com_shuilize_elneazh_inj_NameGate_nativeHooked(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return g_hooked ? JNI_TRUE : JNI_FALSE;
}

// 给单机闸门用：这个固定偏移上的 EncryptRJ256 是否已经由名字闸门挂上。
// 两个闸门是同一份 .so 里的两个 .c，靠这个函数协调：谁先挂谁负责，另一个不再动那 40 字节。
int eg_name_gate_hooked(void);

void *eg_name_gate_target(void) { return g_hooked ? g_enc_target : NULL; }
int eg_name_gate_hooked(void) {
    return g_hooked ? 1 : 0;
}

// 自检用：解析诊断。0 = 已挂上；1 = 符号没解析到；2 = 符号有但 40B 入口校验不过；
// 3 = hook 引擎没给 trampoline；4 = 此 ABI 没有 hook 引擎；-1 = 还没试过。
// 同时把「用什么方式解析的 / libil2cpp 基址 / 校验位置和实际字节」一起交给 Java 写进 selfcheck。
JNIEXPORT jstring JNICALL
Java_com_shuilize_elneazh_inj_NameGate_nativeDiag(JNIEnv *env, jclass cls, jintArray code) {
    (void) cls;
    char buf[512];
    void *base = eg_find_libil2cpp();
    if (!base) base = (void *) eg_lib_base("libil2cpp.so");
    snprintf(buf, sizeof buf,
             "code=%d hook=EncryptRJ256 stage=%s lib=%p off=0x%x verify=%s gate=%s "
             "first_mismatch=0x%x actual=0x%02x expect=0x%02x detail=%d",
             g_diag_code, eg_sym_stage(), base, (unsigned) ENC_OFFSET,
             g_diag_verify == 1 ? "ok" : (g_diag_verify == 0 ? "fail" : "n/a"),
             g_hooked ? "hooked" : "not-hooked",
             (unsigned) g_diag_mismatch, (unsigned) g_diag_actual,
             (unsigned) g_diag_expect, g_diag_detail);
    if (code) {
        jint v[1] = {(jint) g_diag_code};
        (*env)->SetIntArrayRegion(env, code, 0, 1, v);
    }
    return (*env)->NewStringUTF(env, buf);
}

// 自检用：直接喂一段「存档原始字节」（raw），走与线上完全相同的 eg_filter_raw 路径，
// 返回过滤后的字节（无改动返回 null）。给 SelfCheck 做与 Python 夹具逐字节比对。
JNIEXPORT jbyteArray JNICALL
Java_com_shuilize_elneazh_inj_NameGate_nativeFilterRaw(JNIEnv *env, jclass cls, jbyteArray in) {
    (void) cls;
    if (!in) return NULL;
    jsize n = (*env)->GetArrayLength(env, in);
    jbyte *buf = (*env)->GetByteArrayElements(env, in, NULL);
    if (!buf) return NULL;
    // 复制一份，方便用同一套 malloc 逻辑
    unsigned char *raw = (unsigned char *) malloc((size_t) n);
    if (raw) memcpy(raw, buf, (size_t) n);
    (*env)->ReleaseByteArrayElements(env, in, buf, JNI_ABORT);
    if (!raw) return NULL;

    // 直接复用过滤函数（它按「原样扫描 + 长度字节改写」工作）
    eg_filter_ctx ctx;
    memset(&ctx, 0, sizeof ctx);
    ctx.map = eg_map;
    size_t out_len = 0;
    unsigned char *fixed = eg_filter_raw_public(&ctx, raw, (size_t) n, &out_len);
    free(raw);
    if (!fixed) return NULL;
    jbyteArray r = (*env)->NewByteArray(env, (jsize) out_len);
    if (r) (*env)->SetByteArrayRegion(env, r, 0, (jsize) out_len, (const jbyte *) fixed);
    free(fixed);
    return r;
}

// ------------------------------------------------------------------ 单机模式闸门（OfflineGate）
//
// 与名字闸门共用这一个 .so：名字闸门改上传明文，这里按名字解析游戏自己的方法挂 hook。
// 相关的解析逻辑全在 eg_offline.c。

void eg_of_set_enabled(int on);
void eg_of_set_state(const char *s);

// 灌入单机开关状态（'1' = 开 / '0' = 关）；现读，切换开关不用重进游戏。
JNIEXPORT jint JNICALL
Java_com_shuilize_elneazh_inj_OfflineGate_nativePush(JNIEnv *env, jclass cls, jstring state) {
    (void) cls;
    if (!state) { eg_of_set_state("0"); return 0; }
    const char *s = (*env)->GetStringUTFChars(env, state, NULL);
    if (s) {
        eg_of_set_state(s);
        (*env)->ReleaseStringUTFChars(env, state, s);
    }
    return 1;
}

// 安装一族钩子：target 1..6（见 eg_offline.h），names 是 "命名空间\t类\t方法" 行。
// 返回挂上的方法数；负数 = 原因码（-1 il2cpp API 还不可见 / -4 这个 ABI 没有 hook 引擎 / -5 编号非法）。
JNIEXPORT jint JNICALL
Java_com_shuilize_elneazh_inj_OfflineGate_nativeInstall(JNIEnv *env, jclass cls, jint target, jstring names) {
    (void) cls;
    if (!names) return -1;
    const char *s = (*env)->GetStringUTFChars(env, names, NULL);
    if (!s) return -1;
    int n = eg_of_install((int) target, s);
    (*env)->ReleaseStringUTFChars(env, names, s);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_shuilize_elneazh_inj_OfflineGate_nativeUninstall(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return eg_of_uninstall();
}

// [0]=已挂 [1]=入口不创建/面板收掉 [2]=请求拦截 [3]=界面隐藏 [4]=遮罩跳过
// [5]=解析失败 [6]=回调异常 [7]=兜底闸（EncryptRJ256）已挂
JNIEXPORT jintArray JNICALL
Java_com_shuilize_elneazh_inj_OfflineGate_nativeStats(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    int v[EG_STATS_SLOTS];
    memset(v, 0, sizeof v);
    eg_of_stats(v);
    jint jv[EG_STATS_SLOTS];
    for (int i = 0; i < EG_STATS_SLOTS; i++) jv[i] = v[i];
    jintArray a = (*env)->NewIntArray(env, EG_STATS_SLOTS);
    if (a) (*env)->SetIntArrayRegion(env, a, 0, EG_STATS_SLOTS, jv);
    return a;
}

// native 侧现在读到的「单机开」值（1/0）。切开关后 Java 可以拿它对账：
// 如果这里一直是 0 而 prefs 里是 true，说明状态没推进 native（push 没生效）。
JNIEXPORT jint JNICALL
Java_com_shuilize_elneazh_inj_OfflineGate_nativeOff(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return eg_of_is_off();
}

// 兜底闸状态：1 = 已挂在 EncryptRJ256 上
JNIEXPORT jint JNICALL
Java_com_shuilize_elneazh_inj_OfflineGate_nativeEncState(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return eg_of_enc_state();
}

JNIEXPORT jint JNICALL
Java_com_shuilize_elneazh_inj_OfflineGate_nativeResetStats(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    eg_of_reset_stats();
    return 1;
}

// 运行时探测：我们依赖的 il2cpp API 能 dlsym 到几个（0..5）。
JNIEXPORT jint JNICALL
Java_com_shuilize_elneazh_inj_OfflineGate_nativeProbe(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return eg_of_api_probe();
}

// 自检用：能不能按名字解析到某个方法（不调用，只解析）。
JNIEXPORT jboolean JNICALL
Java_com_shuilize_elneazh_inj_OfflineGate_nativeResolvable(
        JNIEnv *env, jclass cls, jstring ns, jstring klass, jstring method) {
    (void) cls;
    if (!klass || !method) return JNI_FALSE;
    const char *c_ns = ns ? (*env)->GetStringUTFChars(env, ns, NULL) : NULL;
    const char *c_k = (*env)->GetStringUTFChars(env, klass, NULL);
    const char *c_m = (*env)->GetStringUTFChars(env, method, NULL);
    int ok = 0;
    if (c_k && c_m) ok = eg_of_resolvable(c_ns ? c_ns : "*", c_k, c_m);
    if (c_ns) (*env)->ReleaseStringUTFChars(env, ns, c_ns);
    if (c_k) (*env)->ReleaseStringUTFChars(env, klass, c_k);
    if (c_m) (*env)->ReleaseStringUTFChars(env, method, c_m);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// 自检用：这个类在不在运行时里（不区分命名空间、不调用任何方法）。
JNIEXPORT jboolean JNICALL
Java_com_shuilize_elneazh_inj_OfflineGate_nativeClassPresent(
        JNIEnv *env, jclass cls, jstring klass) {
    (void) cls;
    if (!klass) return JNI_FALSE;
    const char *k = (*env)->GetStringUTFChars(env, klass, NULL);
    int ok = k ? eg_of_class_present(k) : 0;
    if (k) (*env)->ReleaseStringUTFChars(env, klass, k);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// 自检用：逐条 hook 台账（名字 / 解析到的地址 / 方式 / 成功或失败原因）。
JNIEXPORT jstring JNICALL
Java_com_shuilize_elneazh_inj_OfflineGate_nativeHookReport(JNIEnv *env, jclass cls) {
    (void) cls;
    static char buf[6144];
    int n = eg_of_hook_report(buf, (int) sizeof buf);
    if (n <= 0) return (*env)->NewStringUTF(env, "hooks: 0 installed / 0 failed");
    return (*env)->NewStringUTF(env, buf);
}
