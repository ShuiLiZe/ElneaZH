// ElneaZH · 方案 B 第一步：联网录制（只在「单机关」时工作，只读不改）
//
// 目的：单机重放需要知道「登录回包」长什么样、游戏在哪个方法里处理它。global-metadata v39
// 没有现成的 dumper，所以这里在进程内用 il2cpp 运行时 API 自己导一份方法表，再录下联网时
// 每一次请求明文（EncryptRJ256 入参）和回包明文（DecryptRJ256 返回值）以及调用栈 RVA。
//
// 产物（都在 /data/data/<包名>/files/）：
//   elneazh_dump.tsv     Assembly-CSharp 全部类/字段/方法：RVA、参数类型、返回类型
//   elneazh_netrec.log   每条请求/回包：时间、方向、长度、调用栈 RVA、明文（截断 256KB）
//   elneazh_rx_last.txt  最近一次「看起来是登录回包」的完整明文（重放用的种子）
//
// 安全约束：
//   * 单机开时什么都不录（eg_of_is_off）。
//   * DecryptRJ256 hook 原样返回原函数的结果，不改任何回包。
//   * 栈回溯每一步都先 eg_mem_ok 校验，读不到就停。
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "eg_log.h"
#include "eg_offline.h"
#include "eg_sym.h"
#include "vendor/And64InlineHook.hpp"

#define FILES "/data/data/jp.co.althi.elnea.en/files/"
#define REC_PATH FILES "elneazh_netrec.log"
#define DUMP_PATH FILES "elneazh_dump.tsv"
#define LOGIN_PATH FILES "elneazh_rx_last.txt"
#define REC_CAP (24L << 20)
#define REC_ENTRY_CAP (256 * 1024)
#define REC_FRAMES 14

static pthread_mutex_t g_rec_lock = PTHREAD_MUTEX_INITIALIZER;
static FILE *g_rec;
static long g_rec_size;
static volatile int g_rec_installed;
static int g_rec_seq;

typedef void *(*dec_fn_t)(void *);
static dec_fn_t g_dec_orig;

// ------------------------------------------------------------- il2cpp 字符串
char *eg_rec_str(void *s, size_t *out_len);
static char *rec_str(void *s, size_t *out_len) {
    *out_len = 0;
    if (!s || !eg_mem_ok((uintptr_t) s, 0x14)) return NULL;
    int32_t len = *(int32_t *) ((char *) s + 0x10);
    if (len < 0 || len > (8 << 20)) return NULL;
    if (!eg_mem_ok((uintptr_t) s + 0x14, (size_t) len * 2)) return NULL;
    const uint16_t *u = (const uint16_t *) ((char *) s + 0x14);
    char *b = (char *) malloc((size_t) len * 3 + 4);
    if (!b) return NULL;
    size_t o = 0;
    for (int32_t i = 0; i < len; i++) {
        unsigned cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len && u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (u[i + 1] - 0xDC00);
            i++;
        }
        if (cp < 0x80) b[o++] = (char) cp;
        else if (cp < 0x800) { b[o++] = (char) (0xC0 | (cp >> 6)); b[o++] = (char) (0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) {
            b[o++] = (char) (0xE0 | (cp >> 12)); b[o++] = (char) (0x80 | ((cp >> 6) & 0x3F));
            b[o++] = (char) (0x80 | (cp & 0x3F));
        } else {
            b[o++] = (char) (0xF0 | (cp >> 18)); b[o++] = (char) (0x80 | ((cp >> 12) & 0x3F));
            b[o++] = (char) (0x80 | ((cp >> 6) & 0x3F)); b[o++] = (char) (0x80 | (cp & 0x3F));
        }
    }
    b[o] = 0;
    *out_len = o;
    return b;
}

// ------------------------------------------------------------- 调用栈（x29 链）
static int rec_stack(void *fp, char *out, size_t cap) {
    uintptr_t base = eg_lib_base_cached();
    size_t o = 0;
    out[0] = 0;
    uintptr_t f = (uintptr_t) fp;
    for (int i = 0; i < REC_FRAMES && f; i++) {
        if ((f & 7) || !eg_mem_ok(f, 16)) break;
        uintptr_t next = ((uintptr_t *) f)[0];
        uintptr_t lr = ((uintptr_t *) f)[1] & 0x0000FFFFFFFFFFFFULL;   // 剥 PAC
        if (!lr) break;
        int n;
        if (base && lr > base && lr - base < 0x10000000ULL)
            n = snprintf(out + o, cap - o, "%s0x%lx", o ? "," : "", (unsigned long) (lr - base));
        else
            n = snprintf(out + o, cap - o, "%s?%lx", o ? "," : "", (unsigned long) lr);
        if (n < 0 || (size_t) n >= cap - o) break;
        o += (size_t) n;
        if (next <= f) break;
        f = next;
    }
    return (int) o;
}

static void rec_write(const char *dir, const char *txt, size_t n, void *fp) {
    char stk[REC_FRAMES * 14 + 8];
    rec_stack(fp, stk, sizeof stk);
    pthread_mutex_lock(&g_rec_lock);
    if (!g_rec) { g_rec = fopen(REC_PATH, "a"); g_rec_size = 0; }
    if (g_rec && g_rec_size < REC_CAP) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        struct tm tm;
        localtime_r(&ts.tv_sec, &tm);
        size_t w = n > REC_ENTRY_CAP ? REC_ENTRY_CAP : n;
        int h = fprintf(g_rec, "=== #%d %02d:%02d:%02d.%03ld %s len=%lu tid=%ld stack=%s\n", ++g_rec_seq,
                        tm.tm_hour, tm.tm_min, tm.tm_sec, ts.tv_nsec / 1000000L, dir,
                        (unsigned long) n, eg_tid(), stk);
        if (h > 0) g_rec_size += h;
        if (txt && w) { fwrite(txt, 1, w, g_rec); g_rec_size += (long) w; }
        if (w < n) g_rec_size += fprintf(g_rec, "\n...[cut %lu]", (unsigned long) (n - w));
        fputc('\n', g_rec);
        fflush(g_rec);
    }
    pthread_mutex_unlock(&g_rec_lock);
}

// 名字闸门的 EncryptRJ256 hook 调进来（入参 = 请求明文 il2cpp String）
void rp_set_last_tx(char *s);   // eg_replay.c：最近一条请求明文（接管所有权）
void rp_learn(const char *rx, size_t n);   // eg_replay.c：联网时把「最近请求 → 这条回包」存进重放库
char *eg_rec_str(void *s, size_t *out_len) { return rec_str(s, out_len); }

void eg_rec_req(void *plain_str, void *fp) {
    if (!g_rec_installed || eg_of_is_off() || !plain_str) return;
    size_t n = 0;
    char *s = rec_str(plain_str, &n);
    rec_write("TX", s, n, fp);
    rp_set_last_tx(s);
}

// 回包看起来像登录结果：含账号/用户信息字段之一
static int rec_looks_login(const char *s) {
    static const char *keys[] = {"\"user_id\"", "\"uid\"", "\"login\"", "\"session\"", "\"token\"",
                                 "\"savedata\"", "\"account\"", NULL};
    int hit = 0;
    for (int i = 0; keys[i]; i++) if (strstr(s, keys[i])) hit++;
    return hit >= 2;
}

#if defined(__aarch64__)
static void *rec_dec_hook(void *a0) {
    void *r = g_dec_orig ? g_dec_orig(a0) : NULL;
    if (!eg_of_is_off() && r) {
        size_t n = 0;
        char *s = rec_str(r, &n);
        rec_write("RX", s, n, __builtin_frame_address(0));
        if (s && n) rp_learn(s, n);
        if (s && n > 64 && rec_looks_login(s)) {
            FILE *f = fopen(LOGIN_PATH, "w");
            if (f) { fwrite(s, 1, n, f); fclose(f); }
            EG_I("rec: login-like response saved (%luB)", (unsigned long) n);
        }
        free(s);
    }
    return r;
}
#endif

// ------------------------------------------------------------- 进程内 dump
typedef void *(*p0)(void);
typedef void *(*p1)(void *);
typedef void *(*p2)(void *, void *);
typedef size_t (*sz1)(void *);
typedef void *(*cls_at)(void *, size_t);
typedef void *(*iter_fn)(void *, void **);
typedef uint32_t (*u1)(void *);
typedef void *(*param_fn)(void *, uint32_t);
typedef const void **(*asm_fn)(void *, size_t *);
typedef int (*i1)(void *);

#define SYM(T, n) T n = (T) eg_sym_find("il2cpp_" #n)

static void *rec_dump_thread(void *arg) {
    (void) arg;
    sleep(12);
    struct stat st;
    if (stat(DUMP_PATH, &st) == 0 && st.st_size > 100000) {
        EG_I("rec: dump exists (%ldB), skip", (long) st.st_size);
        return NULL;
    }
    SYM(p0, domain_get); SYM(asm_fn, domain_get_assemblies); SYM(p1, assembly_get_image);
    SYM(p1, image_get_name); SYM(sz1, image_get_class_count); SYM(cls_at, image_get_class);
    SYM(p1, class_get_name); SYM(p1, class_get_namespace); SYM(iter_fn, class_get_methods);
    SYM(iter_fn, class_get_fields); SYM(p1, method_get_name); SYM(u1, method_get_param_count);
    SYM(param_fn, method_get_param); SYM(p1, method_get_return_type); SYM(p1, type_get_name);
    SYM(p1, field_get_name); SYM(sz1, field_get_offset); SYM(p1, field_get_type); SYM(p1, thread_attach);
    SYM(p1, class_get_parent); SYM(p1, free);
    if (!domain_get || !domain_get_assemblies || !assembly_get_image || !image_get_class_count ||
        !image_get_class || !class_get_name || !class_get_methods || !method_get_name ||
        !method_get_param_count) {
        EG_W("rec: dump api missing");
        return NULL;
    }
    void *dom = domain_get();
    if (thread_attach) thread_attach(dom);
    uintptr_t base = eg_lib_base_cached();
    FILE *f = fopen(DUMP_PATH ".tmp", "w");
    if (!f) return NULL;
    fprintf(f, "# kind\tns\tclass\tname\trva/offset\ttype\tparams\n");
    size_t na = 0;
    const void **as = domain_get_assemblies(dom, &na);
    long nm = 0, nc = 0;
    for (size_t a = 0; as && a < na; a++) {
        void *img = assembly_get_image((void *) as[a]);
        const char *iname = img && image_get_name ? (const char *) image_get_name(img) : "";
        if (!iname || strncmp(iname, "Assembly-CSharp", 15) != 0) continue;
        size_t cc = image_get_class_count(img);
        for (size_t c = 0; c < cc; c++) {
            void *k = image_get_class(img, c);
            if (!k) continue;
            const char *kn = (const char *) class_get_name(k);
            const char *ns = class_get_namespace ? (const char *) class_get_namespace(k) : "";
            void *par = class_get_parent ? class_get_parent(k) : NULL;
            fprintf(f, "C\t%s\t%s\t%s\t\t\t\n", ns ? ns : "", kn ? kn : "?",
                    par ? (const char *) class_get_name(par) : "");
            nc++;
            if (class_get_fields && field_get_name) {
                void *it = NULL, *fld;
                while ((fld = class_get_fields(k, &it)) != NULL) {
                    char *tn = field_get_type && type_get_name ? (char *) type_get_name(field_get_type(fld)) : NULL;
                    fprintf(f, "F\t%s\t%s\t%s\t0x%lx\t%s\t\n", ns ? ns : "", kn, (const char *) field_get_name(fld),
                            field_get_offset ? (unsigned long) field_get_offset(fld) : 0UL, tn ? tn : "");
                    if (tn && free) free(tn);
                }
            }
            void *it = NULL, *m;
            while ((m = class_get_methods(k, &it)) != NULL) {
                uintptr_t p = ((uintptr_t) *(void **) m) & 0x0000FFFFFFFFFFFFULL;
                uint32_t pc = method_get_param_count(m);
                char params[512];
                size_t po = 0;
                params[0] = 0;
                for (uint32_t i = 0; i < pc && method_get_param && type_get_name; i++) {
                    char *tn = (char *) type_get_name(method_get_param(m, i));
                    int w = snprintf(params + po, sizeof params - po, "%s%s", i ? "," : "", tn ? tn : "?");
                    if (tn && free) free(tn);
                    if (w < 0 || (size_t) w >= sizeof params - po) break;
                    po += (size_t) w;
                }
                char *rt = method_get_return_type && type_get_name ? (char *) type_get_name(method_get_return_type(m)) : NULL;
                fprintf(f, "M\t%s\t%s\t%s\t0x%lx\t%s\t%s\n", ns ? ns : "", kn, (const char *) method_get_name(m),
                        (p && base && p > base) ? (unsigned long) (p - base) : 0UL, rt ? rt : "", params);
                if (rt && free) free(rt);
                nm++;
            }
        }
    }
    fclose(f);
    rename(DUMP_PATH ".tmp", DUMP_PATH);
    EG_I("rec: dump done classes=%ld methods=%ld", nc, nm);
    return NULL;
}

// 名字闸门挂上 EncryptRJ256 之后调用（此时 il2cpp 运行时已就绪）
void eg_rec_install(void) {
    if (g_rec_installed) return;
    g_rec_installed = 1;
#if defined(__aarch64__)
    int nf = 0;
    static const char *names[] = {"DecryptRJ256", "DecryptRJ", "Decrypt", NULL};
    void *dec = NULL;
    for (int i = 0; names[i] && !dec; i++) {
        dec = eg_of_method_ptr("Cipher", names[i], 1, &nf);
        if (dec) EG_I("rec: Cipher::%s resolved", names[i]);
    }
    if (dec) {
        A64HookFunction(dec, (void *) rec_dec_hook, (void **) &g_dec_orig);
        EG_I("rec: DecryptRJ256 @ +0x%lx hooked=%d", (unsigned long) ((uintptr_t) dec - eg_lib_base_cached()),
             g_dec_orig ? 1 : 0);
    } else {
        EG_W("rec: Cipher::DecryptRJ256 not found (nf=%d); TX only", nf);
    }
#endif
    pthread_t t;
    pthread_attr_t at;
    pthread_attr_init(&at);
    pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&t, &at, rec_dump_thread, NULL) == 0) pthread_setname_np(t, "ElneaRec-dump");
    pthread_attr_destroy(&at);
}
