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

#include "eg_names.h"
#include "eg_offline.h"

#include "vendor/And64InlineHook.hpp"

#define TAG "ElneaGate"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---- 目标：Cipher::EncryptRJ256(string)，base 2.10.7 (base.apk sha 5117bbaa...) ----
#define ENC_OFFSET      0x18ad7e4u
#define ENC_PROLOGUE_LEN 40
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
    pthread_mutex_unlock(&g_pair_lock);
    return 0;
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

static void *g_found_base;

static int eg_phdr_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    (void) data;
    if (!info->dlpi_name) return 0;
    const char *n = info->dlpi_name;
    // 只看 libil2cpp.so（路径结尾匹配，避免误判）
    size_t l = strlen(n);
    const char *want = "libil2cpp.so";
    size_t w = strlen(want);
    if (l < w || strcmp(n + l - w, want) != 0) return 0;
    g_found_base = (void *) (uintptr_t) info->dlpi_addr;
    return 1;   // 停
}

static void *eg_find_libil2cpp(void) {
    g_found_base = NULL;
    dl_iterate_phdr(eg_phdr_cb, NULL);
    return g_found_base;
}

// 校验入口 40 字节；返回 1 表示匹配
static int eg_verify(void *addr) {
    unsigned char cur[ENC_PROLOGUE_LEN];
    memcpy(cur, addr, sizeof cur);
    if (memcmp(cur, ENC_PROLOGUE, sizeof cur) != 0) return 0;
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
    if (!table) { eg_free_pairs(); return 0; }
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

    g_string_new = (il2cpp_string_new_t) dlsym(RTLD_DEFAULT, "il2cpp_string_new");
    g_string_new_utf16 = (il2cpp_string_new_utf16_t) dlsym(RTLD_DEFAULT, "il2cpp_string_new_utf16");
    if (!g_string_new) {
        // libil2cpp 可能还没 dlopen：用绝对路径再试一次
        dlopen("libil2cpp.so", RTLD_NOW | RTLD_NOLOAD);
        g_string_new = (il2cpp_string_new_t) dlsym(RTLD_DEFAULT, "il2cpp_string_new");
    }
    if (!g_string_new) {
        LOGW("il2cpp_string_new not visible yet; will retry on next launch");
        return -1;
    }

    void *base = eg_find_libil2cpp();
    if (!base) { LOGW("libil2cpp.so not loaded; skip"); return -1; }
    g_base = base;
    void *target = (void *) ((uintptr_t) base + ENC_OFFSET);
    if (!eg_verify(target)) {
        LOGW("verify failed at %p+0x%x (base changed? not hooking)", base, ENC_OFFSET);
        return -2;
    }
#if defined(__aarch64__)
    A64HookFunction(target, (void *) eg_hook, (void **) &g_orig_enc);
#else
    LOGW("no hook engine for this ABI (arm64 only yet); game runs unchanged");
    return -4;
#endif
    if (!g_orig_enc) { LOGW("hook engine returned no trampoline"); return -3; }
    g_hooked = 1;
    LOGI("hooked EncryptRJ256 @ %p+0x%x (base %p)", target, ENC_OFFSET, base);
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

// [0]=已挂 [1]=入口不创建 [2]=请求拦截 [3]=面板/按钮置灰 [4]=遮罩挡住
// [5]=解析失败 [6]=回调异常
JNIEXPORT jintArray JNICALL
Java_com_shuilize_elneazh_inj_OfflineGate_nativeStats(JNIEnv *env, jclass cls) {
    (void) cls;
    int v[7];
    eg_of_stats(v);
    jint jv[7] = { v[0], v[1], v[2], v[3], v[4], v[5], v[6] };
    jintArray a = (*env)->NewIntArray(env, 7);
    if (a) (*env)->SetIntArrayRegion(env, a, 0, 7, jv);
    return a;
}

// 清空计数（切换开关时用，便于真机判断「这一轮有没有拦住」）。
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
