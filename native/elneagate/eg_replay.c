// ElneaZH · 方案 B 单机重放（实现）
//
// 思路（全部用游戏自己的方法，不伪造网络层）：
//   联网时：connectWWW 把参数 Json.Serialize 成明文 → EncryptRJ256（名字闸门 eg_hook 在这里
//           拿到明文，rp_set_last_tx 记下）→ 发出 → 回包 DecryptRJ256（rec_dec_hook 在这里拿到
//           明文回包，rp_learn 把「刚才那条请求 → 这条回包」配对存进重放库 files/elneazh_replay.json）。
//   单机时（一个字节都不发）：
//     1) 接管 HttpSystem::connectWWW：照游戏自己「没网」分支的样子（condition=1 → Parse(request,null)
//        → 返回 null），不建 UnityWebRequest、不加密。
//     1b) get_IsReachable 单机时按成 **true**：TitlePanel.ScreenButtonClick 先调 disableLocalPlay()，
//        它返回 !IsReachable —— 手机断网时点标题页没反应。按成 true 后游戏照常发起登录，
//        而真正的发送口 connectWWW 已被上面接管，所以仍然一个字节都不出去。
//     2) HttpRequest::Parse(this, null) 被接管：用 get_Param + Json.Serialize 得到这次请求的参数，
//        算签名去重放库查回包，查到就照 Parse 成功分支的样子填 errorMessage / resultCode /
//        serverVersion / serviceMode / condition，再虚调 HttpNotify(result)（子类各自处理回包，
//        和联网成功时完全同一条路）。写接口（savedata / action 打点）直接造「Done」应答。
//        查不到的读接口：退回原始 Parse（照常报「没网」），不比现在更糟。
//
// 重放库：files/elneazh_replay.json。首次启动若不存在，就用录制版留下的 files/elneazh_netrec.log
// 配对生成；之后每次联网用自己的号录到的回包会就地覆盖，保持新鲜。不随包分发（含账号信息）。
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

#ifndef FILES
#define FILES "/data/data/jp.co.althi.elnea.en/files/"
#endif
#define REPLAY_PATH FILES "elneazh_replay.json"
// 外部目录备份：/sdcard/Android/data/<包>/files/（免权限；清应用缓存、内部库损坏时还能找回）
#define REPLAY_EXT "/sdcard/Android/data/jp.co.althi.elnea.en/files/elneazh_replay.json"
#define REPLAY_MAX (48L << 20)
#define TAG "ElneaReplay"
#define RPI(...) eg_plog(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define RPW(...) eg_plog(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// ---- il2cpp 外部（名字闸门里已解析过 string_new；这里只取函数指针）
typedef void *(*fn_s1)(const char *);
extern void *eg_name_gate_string_new(void);   // elneagate.c 暴露 g_string_new

// ---- 重放库：请求签名 -> 回包明文 JSON（整条 {"info":..,"result":..}）
typedef struct rp_e { char *key; char *val; struct rp_e *next; } rp_e;
#define RP_BUCKETS 256
static rp_e *g_map[RP_BUCKETS];
static pthread_mutex_t g_map_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_loaded;

static unsigned rp_hash(const char *s) {
    unsigned h = 2166136261u;
    for (; *s; s++) { h ^= (unsigned char) *s; h *= 16777619u; }
    return h & (RP_BUCKETS - 1);
}
static const char *rp_get(const char *key) {
    unsigned b = rp_hash(key);
    for (rp_e *e = g_map[b]; e; e = e->next) if (strcmp(e->key, key) == 0) return e->val;
    return NULL;
}
static void rp_put(const char *key, const char *val) {
    unsigned b = rp_hash(key);
    for (rp_e *e = g_map[b]; e; e = e->next)
        if (strcmp(e->key, key) == 0) { free(e->val); e->val = strdup(val); return; }
    rp_e *e = (rp_e *) malloc(sizeof *e);
    if (!e) return;
    e->key = strdup(key);
    e->val = strdup(val);
    e->next = g_map[b];
    g_map[b] = e;
}

// ---------------------------------------------------------- 请求签名
// 与 tools 里的 Python 保持一致：取路由键的值 + 其余顶层键名（排序），忽略易变键。
// 这里只做「够区分同一次会话内不同请求」即可，不追求完全等价。
static int rp_is_volatile(const char *k, size_t n) {
    // 只丢 connectWWW / addUniqArg 自动补的键（get_Param 里没有，加密明文里有）
    static const char *v[] = {"crypt_key", "user_id", "$_sys_ver_$", "user_uniq", NULL};
    for (int i = 0; v[i]; i++) if (strlen(v[i]) == n && strncmp(v[i], k, n) == 0) return 1;
    return 0;
}
static int rp_is_route(const char *k, size_t n) {
    static const char *v[] = {"table_name", "target", "mail_cmd", "gift_mode", "cmd", "action", NULL};
    for (int i = 0; v[i]; i++) if (strlen(v[i]) == n && strncmp(v[i], k, n) == 0) return 1;
    return 0;
}
// 极简顶层 JSON 扫描：只认 {"key":"value"...} 顶层键和字符串值；嵌套对象的键跳过。
// out 写 "route1=val1;route2=val2|key_a,key_b,key_c"（键名排序）。返回 1 成功。
static int rp_sign(const char *j, size_t n, char *out, size_t cap) {
    const char *p = j, *end = j + n;
    char route[256]; size_t ro = 0; route[0] = 0;
    char keys[64][48]; int nk = 0;
    int depth = 0;
    while (p < end) {
        char c = *p;
        if (c == '{' || c == '[') { depth++; p++; continue; }
        if (c == '}' || c == ']') { depth--; p++; continue; }
        if (c == '"' && depth == 1) {
            const char *ks = ++p;
            while (p < end && *p != '"') { if (*p == '\\') p++; p++; }
            size_t kl = (size_t) (p - ks);
            p++;
            while (p < end && (*p == ' ' || *p == ':')) p++;
            // 值
            const char *vs = NULL; size_t vl = 0; int vstr = 0;
            if (p < end && *p == '"') {
                vstr = 1; vs = ++p;
                while (p < end && *p != '"') { if (*p == '\\') p++; p++; }
                vl = (size_t) (p - vs); p++;
            } else {
                vs = p;
                while (p < end && *p != ',' && *p != '}' && *p != ']') p++;
                vl = (size_t) (p - vs);
            }
            if (rp_is_volatile(ks, kl)) continue;
            if (rp_is_route(ks, kl) && vstr) {
                int w = snprintf(route + ro, sizeof route - ro, "%.*s=%.*s;",
                                 (int) kl, ks, (int) vl, vs);
                if (w > 0 && (size_t) w < sizeof route - ro) ro += (size_t) w;
            } else if (nk < 64 && kl < 48 && !(kl > 0 && ks[0] >= '0' && ks[0] <= '9')) {
                memcpy(keys[nk], ks, kl); keys[nk][kl] = 0; nk++;
            }
        } else p++;
    }
    // 键名排序（小数组插入排序）
    for (int i = 1; i < nk; i++) {
        char t[48]; strcpy(t, keys[i]); int k2 = i - 1;
        while (k2 >= 0 && strcmp(keys[k2], t) > 0) { strcpy(keys[k2 + 1], keys[k2]); k2--; }
        strcpy(keys[k2 + 1], t);
    }
    size_t o = 0;
    o += (size_t) snprintf(out + o, cap - o, "%s|", route);
    for (int i = 0; i < nk && o < cap; i++)
        o += (size_t) snprintf(out + o, cap - o, "%s,", keys[i]);
    return 1;
}

// 写请求（存档 / 打点）：直接本地成功，不查库
static int rp_is_write(const char *j) {
    return strstr(j, "\"savedata\"") != NULL || strstr(j, "\"action\":\"") != NULL;
}

// 精确签名没中时的近似匹配：路由部分（'|' 之前）必须一致，键名重合最多者胜。
// 用于 get_Param 与加密明文之间差一两个自动补的键（如 local_gem）的情况。
static const char *rp_get_fuzzy(const char *sign) {
    const char *bar = strchr(sign, '|');
    if (!bar) return NULL;
    size_t rl = (size_t) (bar - sign);
    const char *best = NULL; int bestn = -1;
    for (int b = 0; b < RP_BUCKETS; b++)
        for (rp_e *e = g_map[b]; e; e = e->next) {
            const char *eb = strchr(e->key, '|');
            if (!eb || (size_t) (eb - e->key) != rl || strncmp(e->key, sign, rl) != 0) continue;
            int n = 0;
            const char *k = bar + 1;
            while (*k) {
                const char *c = strchr(k, ',');
                if (!c) break;
                char one[64]; size_t l = (size_t) (c - k);
                if (l + 2 < sizeof one) {
                    one[0] = (k == bar + 1) ? '|' : ','; memcpy(one + 1, k, l); one[l + 1] = ',';
                    one[l + 2] = 0;
                    if (strstr(eb, one) || (one[0] = (one[0] == '|') ? ',' : '|', strstr(eb, one))) n++;
                }
                k = c + 1;
            }
            if (n > bestn) { bestn = n; best = e->val; }
        }
    // 有路由值时路由一致就够；没有路由值时至少要重合一个键名，免得乱配
    if (rl == 0 && bestn <= 0) return NULL;
    return best;
}

// ---------------------------------------------------------- 最近一条请求明文
static __thread char *g_last_tx;
void rp_set_last_tx(char *s) {   // 接管 s 的所有权
    free(g_last_tx);
    g_last_tx = s;
}

// ---------------------------------------------------------- 载入 / 学习 重放库
// replay_seed.json 是 { "<sign>": "<responseJson>", ... }。这里做极简解析：顶层对象，
// 键和值都是字符串（值里的引号用 \" 转义）。
static char *rp_unescape(const char *s, size_t n) {
    char *o = (char *) malloc(n + 1); if (!o) return NULL;
    size_t j = 0;
    for (size_t i = 0; i < n; i++) {
        if (s[i] == '\\' && i + 1 < n) {
            char c = s[++i];
            o[j++] = (c == 'n') ? '\n' : (c == 't') ? '\t' : c;
        } else o[j++] = s[i];
    }
    o[j] = 0; return o;
}
static void rp_load(void) {
    if (g_loaded) return;
    g_loaded = 1;
    const char *path = REPLAY_PATH;
    FILE *f = fopen(path, "r");
    if (!f) { path = REPLAY_EXT; f = fopen(path, "r"); }
    if (!f) { RPW("replay库不存在: %s", REPLAY_PATH); return; }
    fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
    if (sz <= 2 || sz > REPLAY_MAX) { fclose(f); return; }
    char *buf = (char *) malloc((size_t) sz + 1);
    if (!buf) { fclose(f); return; }
    size_t rd = fread(buf, 1, (size_t) sz, f); buf[rd] = 0; fclose(f);
    const char *p = buf, *end = buf + rd;
    int n = 0;
    pthread_mutex_lock(&g_map_lock);
    while (p < end) {
        while (p < end && *p != '"') p++;
        if (p >= end) break;
        const char *ks = ++p;
        while (p < end && *p != '"') { if (*p == '\\') p++; p++; }
        char *key = rp_unescape(ks, (size_t) (p - ks)); p++;
        while (p < end && (*p == ' ' || *p == ':')) p++;
        if (p >= end || *p != '"') { free(key); continue; }
        const char *vs = ++p;
        while (p < end && *p != '"') { if (*p == '\\') p++; p++; }
        char *val = rp_unescape(vs, (size_t) (p - vs)); p++;
        if (key && val) { rp_put(key, val); n++; }
        free(key); free(val);
    }
    pthread_mutex_unlock(&g_map_lock);
    free(buf);
    RPI("replay库载入 %d 条（%s）", n, path);
}

// JSON 字符串转义写文件
static void rp_fput_escaped(FILE *f, const char *s) {
    for (; *s; s++) {
        if (*s == '"' || *s == '\\') { fputc('\\', f); fputc(*s, f); }
        else if (*s == '\n') { fputc('\\', f); fputc('n', f); }
        else if (*s == '\t') { fputc('\\', f); fputc('t', f); }
        else if ((unsigned char) *s >= 0x20) fputc(*s, f);
    }
}
// 锁内只把整库拷进内存（很快），落盘在锁外做，主线程查库不会被磁盘 IO 卡住
static char *rp_snapshot(size_t *out_len) {
    char *buf = NULL; size_t len = 0;
    FILE *f = open_memstream(&buf, &len);
    if (!f) return NULL;
    fputc('{', f);
    int first = 1;
    pthread_mutex_lock(&g_map_lock);
    for (int b = 0; b < RP_BUCKETS; b++)
        for (rp_e *e = g_map[b]; e; e = e->next) {
            if (!first) fputc(',', f);
            first = 0;
            fputc('"', f); rp_fput_escaped(f, e->key); fputc('"', f);
            fputc(':', f);
            fputc('"', f); rp_fput_escaped(f, e->val); fputc('"', f);
        }
    pthread_mutex_unlock(&g_map_lock);
    fputc('}', f);
    fclose(f);
    *out_len = len;
    return buf;
}
static void rp_write_file(const char *final_path, const char *buf, size_t len) {
    char tmp[256];
    snprintf(tmp, sizeof tmp, "%s.tmp", final_path);
    FILE *f = fopen(tmp, "w");
    if (!f) return;
    int bad = fwrite(buf, 1, len, f) != len;
    if (fclose(f) != 0) bad = 1;
    if (!bad) rename(tmp, final_path); else remove(tmp);   // 写完再换名：中途被杀不会留下半个库
}
static void rp_persist(void) {
    size_t len = 0;
    char *buf = rp_snapshot(&len);
    if (!buf) return;
    rp_write_file(REPLAY_PATH, buf, len);
    rp_write_file(REPLAY_EXT, buf, len);
    free(buf);
}
// 学习发生在主线程（Parse 里），整库几 MB，不能每条回包都同步重写：标脏，后台线程 2s 后落盘
static volatile int g_dirty;
static volatile int g_saver_started;
static void *rp_saver(void *a) {
    (void) a;
    for (;;) {
        sleep(2);
        if (g_dirty) { g_dirty = 0; rp_persist(); }
    }
    return NULL;
}
static void rp_mark_dirty(void) {
    g_dirty = 1;
    if (!g_saver_started) {
        g_saver_started = 1;
        pthread_t t;
        if (pthread_create(&t, NULL, rp_saver, NULL) == 0) pthread_detach(t);
        else { g_saver_started = 0; g_dirty = 0; rp_persist(); }
    }
}

// 联网时由 rp_parse_hook 设置：当前正在 Parse 的请求类名 + 用 get_Param 序列化的请求参数。
// 与单机查库时用的是同一套参数和签名，配对不再依赖「最近一次加密的请求」（排队请求会配错：
// 真机 10-22 00:26 重放库里签名 "|" 存的是存档恢复回包，单机时被当成 MasterCheckRequest 的回包 →
// 主数据检查死循环）。
static __thread const char *g_cur_cls;
static __thread char *g_cur_tx;

static int rp_is_empty_table(const char *v) {
    return v && strstr(v, "\"result\":\"empty data") != NULL;
}
static volatile int g_forced, g_full_learned;   // 补整表：改过日期的请求数 / 学到的整表数
static void rp_put_learned(const char *key, const char *val) {
    const char *old = rp_get(key);
    if (strstr(key, "table_name=") && !rp_is_empty_table(val) && (!old || rp_is_empty_table(old)))
        g_full_learned++;
    // 主数据表：已有整表时不让「empty data（没有更新）」覆盖掉，新装/没缓存时还要靠整表
    if (old && rp_is_empty_table(val) && !rp_is_empty_table(old)) return;
    rp_put(key, val);
}

// 联网时：rec_dec_hook 拿到一条回包明文 rx，与当前请求配对后存库
void rp_learn(const char *rx, size_t n) {
    const char *tx = g_cur_tx ? g_cur_tx : g_last_tx;
    if (!tx || !rx || n < 32) return;
    if (!strstr(rx, "\"$_sys_result_$\":\"Done.")) return;   // 只学成功回包
    char sign[512];
    if (!rp_sign(tx, strlen(tx), sign, sizeof sign)) return;
    rp_load();
    const char *body = strstr(rx, "response[JSON]=");
    const char *val = body ? body + 15 : rx;
    pthread_mutex_lock(&g_map_lock);
    if (g_cur_cls && g_cur_tx) {
        char csign[600];
        snprintf(csign, sizeof csign, "%s#%s", g_cur_cls, sign);
        rp_put_learned(csign, val);
    }
    // 不带类名的签名 "|"（版本检查 / 主数据检查 / 存档恢复都长这样）有歧义，只按类名存
    if (strcmp(sign, "|") != 0) rp_put_learned(sign, val);
    pthread_mutex_unlock(&g_map_lock);
    rp_mark_dirty();
}

// 库里有没有这张主数据表的整表回包（不是 "empty data"）
static int rp_has_full_table(const char *table) {
    char pat[128];
    snprintf(pat, sizeof pat, "table_name=%s;|", table);
    int ok = 0;
    pthread_mutex_lock(&g_map_lock);
    for (int b = 0; b < RP_BUCKETS && !ok; b++)
        for (rp_e *e = g_map[b]; e && !ok; e = e->next)
            if (strstr(e->key, pat) && e->val && !rp_is_empty_table(e->val)) ok = 1;
    pthread_mutex_unlock(&g_map_lock);
    return ok;
}

// ---------------------------------------------------------- 换身份后的登录：user_uniq 对齐
// 真机 10-22 00:14：换身份后游戏仍用旧游客账号（user_name/user_pass/login_key）登录，但 addUniqArg
// 补的是新设备码 user_uniq，服务器不认 → 每 0.5s 重试 10 次后跳账号页。重放库里有这个账号上次
// 成功登录的回包（含服务器记的 user_uniq），请求里同名账号就把 user_uniq 换回服务器认的那个。
static char *rp_json_str(const char *j, const char *key);
static char g_uniq_from[96], g_uniq_to[96];
static char *rp_find_uniq_for(const char *name) {
    char pat[160];
    snprintf(pat, sizeof pat, "\"user_name\":\"%s\"", name);
    char *best = NULL;
    pthread_mutex_lock(&g_map_lock);
    for (int b = 0; b < RP_BUCKETS && !best; b++)
        for (rp_e *e = g_map[b]; e && !best; e = e->next) {
            if (!e->val || !strstr(e->val, pat) || !strstr(e->val, "\"login_key\"")) continue;
            const char *res = strstr(e->val, "\"result\"");
            if (res) best = rp_json_str(res, "user_uniq");
        }
    pthread_mutex_unlock(&g_map_lock);
    return best;
}
static char *rp_replace(const char *s, const char *from, const char *to) {
    const char *h = strstr(s, from);
    if (!h) return NULL;
    size_t a = (size_t) (h - s), fl = strlen(from), tl = strlen(to), n = strlen(s);
    char *o = (char *) malloc(n - fl + tl + 1);
    if (!o) return NULL;
    memcpy(o, s, a); memcpy(o + a, to, tl); memcpy(o + a + tl, h + fl, n - a - fl + 1);
    return o;
}
// 返回改写后的请求明文（malloc），不需要改返回 NULL
char *eg_rp_fix_uniq(const char *tx) {
    if (!tx || eg_of_is_off() || !strstr(tx, "\"user_uniq\":\"")) return NULL;
    char *cur = rp_json_str(tx, "user_uniq");
    if (!cur) return NULL;
    char *name = rp_json_str(tx, "user_name");
    if (name && strstr(tx, "\"login_key\"")) {
        rp_load();
        char *srv = rp_find_uniq_for(name);
        if (srv && strcmp(srv, cur) != 0 && strlen(srv) < sizeof g_uniq_to && strlen(cur) < sizeof g_uniq_from) {
            if (strcmp(g_uniq_to, srv) != 0 || strcmp(g_uniq_from, cur) != 0)
                RPW("登录设备码对齐：账号 %s 服务器记的 user_uniq=%.6s…，本机=%.6s… → 发服务器记的", name, srv, cur);
            snprintf(g_uniq_from, sizeof g_uniq_from, "%s", cur);
            snprintf(g_uniq_to, sizeof g_uniq_to, "%s", srv);
        }
        free(srv);
    }
    free(name);
    char *out = NULL;
    if (g_uniq_to[0] && strcmp(cur, g_uniq_from) == 0) {
        char f[128], t[128];
        snprintf(f, sizeof f, "\"user_uniq\":\"%s\"", g_uniq_from);
        snprintf(t, sizeof t, "\"user_uniq\":\"%s\"", g_uniq_to);
        out = rp_replace(tx, f, t);
    }
    free(cur);
    return out;
}

// ---------------------------------------------------------- 联网时补齐整表
// 游戏每次启动都按表发 MasterRequest(table_name, table_date)，服务器只回 table_date 之后变过的行，
// 本机有缓存时几乎全是 "empty data"，重放库就永远攒不到整表（新装断网进不去）。
// 库里还没有整表的表，联网时把 table_date 改成很早的时间，让服务器回整表（与新装首次下载同一应答），
// 学进库后就不再改。只改日期字段，不碰账号。
char *eg_rp_fix_master(const char *tx) {
    if (!tx || eg_of_is_off()) return NULL;
    char *table = rp_json_str(tx, "table_name");
    if (!table) return NULL;
    char *out = NULL;
    rp_load();
    char *cur = rp_json_str(tx, "table_date");
    if (cur) {
        RPI("主数据请求 %s table_date=[%s]", table, cur);
        // 保险：改了 8 张以上还一张整表都没学到（服务器不认这个日期格式之类），本次进程不再改
        if (cur[0] && g_forced - g_full_learned < 8 && !rp_has_full_table(table)) {
            int digits = 1;
            for (const char *c = cur; *c; c++) if (*c < '0' || *c > '9') { digits = 0; break; }
            const char *old = digits ? "0" : "2000-01-01 00:00:00";
            char f[160], t[96];
            snprintf(f, sizeof f, "\"table_date\":\"%s\"", cur);
            snprintf(t, sizeof t, "\"table_date\":\"%s\"", old);
            if (strlen(cur) < 100) out = rp_replace(tx, f, t);
            if (out) { g_forced++; RPI("主数据补整表：%s table_date %s → %s", table, cur, old); }
        }
        free(cur);
    }
    free(table);
    return out;
}

// ---------------------------------------------------------- 首次：从录制日志生成重放库
static void rp_seed_from_netrec(void) {
    struct stat st;
    if (stat(REPLAY_PATH, &st) == 0 && st.st_size > 64) return;
    FILE *f = fopen(FILES "elneazh_netrec.log", "r");
    if (!f) return;
    fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
    if (sz <= 0 || sz > (64L << 20)) { fclose(f); return; }
    char *buf = (char *) malloc((size_t) sz + 1);
    if (!buf) { fclose(f); return; }
    size_t rd = fread(buf, 1, (size_t) sz, f); buf[rd] = 0; fclose(f);
    for (size_t i = 0; i < rd; i++) if (buf[i] == 0) buf[i] = ' ';   // 回包尾部的 \0 填充
    char *tx = NULL; int n = 0;
    char *p = buf;
    while ((p = strstr(p, "=== #")) != NULL) {
        char *eol = strchr(p, '\n'); if (!eol) break;
        int is_tx = strstr(p, " TX ") && strstr(p, " TX ") < eol;
        int is_rx = strstr(p, " RX ") && strstr(p, " RX ") < eol;
        char *body = eol + 1;
        char *next = strstr(body, "\n=== #");
        size_t bl = next ? (size_t) (next - body) : strlen(body);
        char save = body[bl]; body[bl] = 0;
        if (is_tx) { free(tx); tx = bl ? strdup(body) : NULL; }
        else if (is_rx && tx && bl > 32 && !strstr(body, "...[cut") &&
                 strstr(body, "\"$_sys_result_$\":\"Done.")) {
            char sign[512];
            if (rp_sign(tx, strlen(tx), sign, sizeof sign)) {
                const char *b = strstr(body, "response[JSON]=");
                // 去掉尾部填充（空格 / \0 / 换行）
                char *v = strdup(b ? b + 15 : body);
                if (v) {
                    size_t vl = strlen(v);
                    while (vl && (v[vl - 1] == ' ' || v[vl - 1] == '\n' || v[vl - 1] == '\r')) v[--vl] = 0;
                    pthread_mutex_lock(&g_map_lock); rp_put(sign, v); pthread_mutex_unlock(&g_map_lock);
                    free(v); n++;
                }
            }
            free(tx); tx = NULL;
        }
        body[bl] = save;
        p = next ? next + 1 : body + bl;
    }
    free(tx);
    free(buf);
    g_loaded = 1;
    rp_persist();
    RPI("从录制日志生成重放库：%d 条", n);
}

// ---------------------------------------------------------- 回包字段提取（只认扁平字符串）
static char *rp_json_str(const char *j, const char *key) {
    char pat[64]; snprintf(pat, sizeof pat, "\"%s\":\"", key);
    const char *p = strstr(j, pat); if (!p) return NULL;
    p += strlen(pat);
    const char *q = p;
    while (*q && *q != '"') { if (*q == '\\' && q[1]) q++; q++; }
    return rp_unescape(p, (size_t) (q - p));
}
// "result": 后面的值：对象则返回整个 {...} 子串，字符串则 *is_str=1 返回内容
static char *rp_result(const char *j, int *is_str) {
    *is_str = 0;
    const char *p = strstr(j, "\"result\":"); if (!p) return NULL;
    p += 9;
    while (*p == ' ') p++;
    if (*p == '"') {
        const char *q = ++p;
        while (*q && *q != '"') { if (*q == '\\' && q[1]) q++; q++; }
        *is_str = 1; return rp_unescape(p, (size_t) (q - p));
    }
    if (*p != '{') return NULL;
    int d = 0; const char *q = p; int instr = 0;
    for (; *q; q++) {
        if (instr) { if (*q == '\\' && q[1]) q++; else if (*q == '"') instr = 0; continue; }
        if (*q == '"') instr = 1;
        else if (*q == '{') d++;
        else if (*q == '}') { if (--d == 0) { q++; break; } }
    }
    char *o = (char *) malloc((size_t) (q - p) + 1); if (!o) return NULL;
    memcpy(o, p, (size_t) (q - p)); o[q - p] = 0; return o;
}

// ---------------------------------------------------------- hook：Parse / IsReachable
typedef void *(*fn_ser)(void *, void *);        // Json.Serialize(object, mi)
typedef void *(*fn_deser)(void *, void *);      // Json.Deserialize(string, mi)
typedef void *(*fn_getp)(void *, void *);       // HttpRequest::get_Param(this, mi)
typedef int  (*fn_iserr)(void *, void *);       // HttpRequest::get_IsError(this, mi)
typedef void (*fn_parse)(void *, void *, void *);   // Parse(this, www, mi)
typedef void *(*fn_conn)(void *, void *, void *); // HttpSystem::connectWWW(this, req, mi)
typedef void *(*fn_vget)(void *, const void *); // il2cpp_object_get_virtual_method
typedef void (*fn_wb)(void *, void **, void *); // il2cpp_gc_wbarrier_set_field
typedef void (*fn_notify)(void *, void *, const void *);

static fn_ser g_ser;
static fn_deser g_deser;
static fn_getp g_getp;
static fn_iserr g_iserr;
static fn_parse g_parse_orig;
static fn_conn g_conn_orig;
typedef int (*fn_reach)(void *);
static fn_reach g_reach_orig;
static fn_vget g_vget;
static fn_wb g_wb;
static fn_s1 g_snew;
static const void *g_notify_mi;   // HttpRequest::HttpNotify 的 MethodInfo（虚方法基）
static volatile int g_rp_installed;
static volatile int g_rp_ready;

int eg_replay_active(void) { return g_rp_ready; }

extern char *eg_rec_str(void *s, size_t *out_len);   // eg_rec.c

static void rp_setf(void *obj, int off, void *val) {
    void **slot = (void **) ((char *) obj + off);
    if (g_wb) g_wb(obj, slot, val); else *slot = val;
}

// 本机游戏版本（取自请求里的 "$_sys_ver_$":"Android Version 2.10.7"）
static char g_appver[32];
static void rp_note_appver(const char *tx) {
    char *v = rp_json_str(tx, "$_sys_ver_$");
    if (!v) return;
    const char *sp = strrchr(v, ' ');
    snprintf(g_appver, sizeof g_appver, "%s", sp ? sp + 1 : v);
    free(v);
}

// 最近一条录制回包里的服务端版本号（$_sys_ver_$，如 2605281924）。
// 真机 10-22 01:04：单机自造的 write-ack 不带 $_sys_ver_$ / $_sys_service_$，游戏收到空版本号
// 当成服务端版本变了，弹「请将应用更新到最新版本」并跳应用商店。自造回包统一补上。
static char g_srvver[48];

static int rp_emulate(void *self, const char *json) {
    int is_str = 0;
    char *res = rp_result(json, &is_str);
    char *ver = rp_json_str(json, "$_sys_ver_$");
    char *rc = rp_json_str(json, "$_sys_result_$");
    char *svc = rp_json_str(json, "$_sys_service_$");
    if (ver && ver[0]) snprintf(g_srvver, sizeof g_srvver, "%s", ver);
    if (!ver && g_srvver[0]) ver = strdup(g_srvver);
    if (!svc && g_appver[0]) svc = strdup(g_appver);
    void *resobj = NULL;
    if (res && !is_str) resobj = g_deser(g_snew(res), NULL);
    rp_setf(self, 0x20, (res && is_str) ? g_snew(res) : NULL);       // errorMessage
    rp_setf(self, 0x38, ver ? g_snew(ver) : NULL);                    // serverVersion
    rp_setf(self, 0x30, g_snew(rc ? rc : "Done."));                   // resultCode
    // 真机 23:49：录的回包 $_sys_service_$=2.10.8 > 本机 2.10.7，游戏随即提示「请更新到最新版本」并跳应用商店。
    // 单机时把服务端版本按成本机版本。
    if (svc && g_appver[0]) { free(svc); svc = strdup(g_appver); }
    rp_setf(self, 0x40, svc ? g_snew(svc) : NULL);                    // serviceMode
    *(int32_t *) ((char *) self + 0x10) = 2;                          // condition：完成
    if (g_iserr && g_iserr(self, NULL)) *(int32_t *) ((char *) self + 0x10) = 3;
    free(res); free(ver); free(rc); free(svc);
    // 虚调 HttpNotify(result)：子类（LoginRequest 等）各自的回包处理
    const void *mi = g_vget ? g_vget(self, g_notify_mi) : g_notify_mi;
    if (!mi) return 0;
    fn_notify fn = (fn_notify) (((uintptr_t) *(void *const *) mi) & 0x0000FFFFFFFFFFFFULL);
    fn(self, resobj, mi);
    return 1;
}

// 按类名取录制回包："类名#..." 的键，只有一条时返回它（多条取最长的那条，通常是完整回包）。调用方持锁。
static const char *rp_get_cls(const char *cls) {
    size_t n = strlen(cls);
    const char *best = NULL; size_t bl = 0;
    for (int b = 0; b < RP_BUCKETS; b++)
        for (rp_e *e = g_map[b]; e; e = e->next)
            if (strncmp(e->key, cls, n) == 0 && e->key[n] == '#') {
                size_t l = strlen(e->val);
                if (l > bl) { best = e->val; bl = l; }
            }
    return best;
}

// il2cpp 对象的类名（Il2CppClass: image, gc_desc, name, namespaze, ...）
static const char *rp_cls(void *obj) {
    if (!obj) return "?";
    void *k = *(void **) obj;
    if (!k) return "?";
    const char *n = *(const char **) ((char *) k + 0x10);
    return n ? n : "?";
}

#if defined(__aarch64__)
static void rp_parse_hook(void *self, void *www, void *mi) {
    if (www || !eg_of_is_off() || !g_rp_ready || !self) {
        char *ctx_tx = NULL;
        if (www && self && !eg_of_is_off() && g_getp && g_ser) {
            void *pp = g_getp(self, NULL);
            void *pj = pp ? g_ser(pp, NULL) : NULL;
            size_t pl = 0;
            ctx_tx = pj ? eg_rec_str(pj, &pl) : NULL;
            g_cur_tx = ctx_tx;
            g_cur_cls = rp_cls(self);
        }
        g_parse_orig(self, www, mi);
        g_cur_tx = NULL;
        g_cur_cls = NULL;
        free(ctx_tx);
        // 联网出错时把游戏自己记下的错误写进 native.log（www.error 或服务端 "." 开头的明文错误）
        const char *pc = self ? rp_cls(self) : "?";
        int cond = self ? *(int32_t *) ((char *) self + 0x10) : -1;
        if (self && !eg_of_is_off() && (strstr(pc, "Login") || strstr(pc, "Recov")))
            RPI("联网 Parse %s cond=%d www=%s", pc, cond, www ? "有" : "空");
        if (self && !eg_of_is_off() && cond == 3) {
            size_t a = 0, b = 0;
            char *em = eg_rec_str(*(void **) ((char *) self + 0x20), &a);
            char *rc = eg_rec_str(*(void **) ((char *) self + 0x30), &b);
            if (em && a > 600) em[600] = 0;
            RPW("联网请求出错 %s www=%s err=[%s] code=[%s]", rp_cls(self), www ? "有" : "空",
                 em ? em : "", rc ? rc : "");
            free(em); free(rc);
        }
        return;
    }
    const char *cls = rp_cls(self);
    void *param = g_getp(self, NULL);
    void *js = param ? g_ser(param, NULL) : NULL;
    size_t tl = 0;
    char *tx = js ? eg_rec_str(js, &tl) : NULL;
    if (!tx && param) {   // 真机 10-22 01:10：偶发 get_Param 序列化取不到 → 原逻辑当没网，登录重试 10 次后「通讯异常」。再取一次。
        js = g_ser(param, NULL);
        tx = js ? eg_rec_str(js, &tl) : NULL;
    }
    rp_load();
    const char *hit = NULL;
    char *made = NULL;
    char sign[512];
    sign[0] = 0;
    if (!tx) {
        // 拿不到请求明文：按类名找唯一的录制回包（LoginRequest、User*Request 都只有一条）
        RPW("单机：%s 取不到请求参数（param=%s js=%s），按类名重放", cls, param ? "有" : "空", js ? "有" : "空");
        if (strcmp(cls, "MasterRequest") == 0) {   // 主数据表很多张，类名分不出是哪张：回「没有更新」
            made = strdup("{\"info\":{\"$_sys_result_$\":\"Done.\"},\"result\":\"empty data\n\"}");
            hit = made;
        } else {
            pthread_mutex_lock(&g_map_lock);
            hit = rp_get_cls(cls);
            pthread_mutex_unlock(&g_map_lock);
        }
        if (!hit) { RPW("单机未命中，按没网处理: %s#?", cls); g_parse_orig(self, www, mi); return; }
        snprintf(sign, sizeof sign, "%s#(类名)", cls);
        tx = strdup("{}");
        tl = 2;
    }
    rp_note_appver(tx);
    if (rp_is_write(tx)) {
        // 写存档：有录到的同类写入回包就用它（带 crypt_key 等游戏要的字段），否则自造一份。
        // 真机 10-22 01:10：自造 ack 只有 save_count/user_id，游戏收到后退回标题画面。
        char csign[600];
        if (rp_sign(tx, tl, sign, sizeof sign)) {
            snprintf(csign, sizeof csign, "%s#%s", cls, sign);
            pthread_mutex_lock(&g_map_lock);
            hit = rp_get(csign);
            if (!hit) hit = rp_get(sign);
            pthread_mutex_unlock(&g_map_lock);
        }
        RPI("写存档 %s 签名 %s %s", cls, sign, hit ? "用录制回包" : "自造回包");
        if (!hit) {
            char *uid = rp_json_str(tx, "user_id");
            char *ck = NULL;
            pthread_mutex_lock(&g_map_lock);
            for (int b = 0; b < RP_BUCKETS && !ck; b++)
                for (rp_e *e = g_map[b]; e && !ck; e = e->next) {
                    if (!strstr(e->val, "crypt_key")) continue;
                    ck = rp_json_str(e->val, "crypt_key");
                    if (!uid) uid = rp_json_str(e->val, "user_id");
                }
            pthread_mutex_unlock(&g_map_lock);
            made = (char *) malloc(768);
            if (made) snprintf(made, 768, "{\"info\":{\"$_sys_result_$\":\"Done.\"},\"result\":"
                                          "{\"save_count\":\"1\",\"user_id\":\"%s\",\"crypt_key\":\"%s\",\"disable_gem\":\"0\"}}",
                               uid ? uid : "0", ck ? ck : "");
            free(uid); free(ck);
            hit = made;
        }
    } else if (!hit && rp_sign(tx, tl, sign, sizeof sign)) {
        char csign[600];
        snprintf(csign, sizeof csign, "%s#%s", cls, sign);
        RPI("请求 %s 签名 %s", cls, sign);
        // 签名只有 "|" 的请求（版本检查 / MasterCheckRequest / 存档恢复）只认带类名的记录：
        // 10-22 00:26 MasterCheckRequest 拿到了存档恢复回包 → 主数据检查死循环。
        int ambiguous = strcmp(sign, "|") == 0 || strcmp(cls, "UserWorldRecovRequest") == 0;
        pthread_mutex_lock(&g_map_lock);
        hit = rp_get(csign);
        if (!hit && !ambiguous) hit = rp_get(sign);
        if (!hit && !ambiguous) { hit = rp_get_fuzzy(sign); if (hit) RPI("近似命中: %s", sign); }
        pthread_mutex_unlock(&g_map_lock);
        if (!hit && ambiguous) RPW("单机：%s 还没有录到（需联网进一次游戏）", cls);
    }
    if (!hit && strstr(tx, "\"table_name\"")) {
        // 主数据表：本地已有缓存，回「没有更新」（与服务器对未更新表的应答一致）
        made = strdup("{\"info\":{\"$_sys_result_$\":\"Done.\"},\"result\":\"empty data\n\"}");
        hit = made;
    }
    if (!hit) {
        RPW("单机未命中，按没网处理: %s", sign);
        free(tx); g_parse_orig(self, www, mi); return;
    }
    if (made && !g_srvver[0]) {   // 本次进程还没重放过录制回包：从库里随便取一条的服务端版本号
        pthread_mutex_lock(&g_map_lock);
        for (int b = 0; b < RP_BUCKETS && !g_srvver[0]; b++)
            for (rp_e *e = g_map[b]; e && !g_srvver[0]; e = e->next) {
                char *v = rp_json_str(e->val, "$_sys_ver_$");
                if (v && v[0]) snprintf(g_srvver, sizeof g_srvver, "%s", v);
                free(v);
            }
        pthread_mutex_unlock(&g_map_lock);
    }
    RPI("单机重放: %s", made ? (rp_is_write(tx) ? "write-ack" : "empty-data") : sign);
    (void) cls;
    if (!rp_emulate(self, hit)) g_parse_orig(self, www, mi);
    free(made);
    free(tx);
}
static int rp_reach_hook(void *mi) {
    if (eg_of_is_off() && g_rp_ready) return 1;
    return g_reach_orig ? g_reach_orig(mi) : 0;
}

static void *rp_conn_hook(void *self, void *req, void *mi) {
    if (!eg_of_is_off() || !g_rp_ready || !req) return g_conn_orig(self, req, mi);
    static int n;
    if (n++ < 50) RPI("connectWWW 接管 #%d", n);
    *(int32_t *) ((char *) req + 0x10) = 1;   // 与原生没网分支一致：先置「请求中」
    rp_parse_hook(req, NULL, NULL);             // 重放（未命中则原始 Parse 报没网）
    return NULL;                                // 协程见 null 直接结束，不 yield、不 finishWWW
}
#endif

static void *rp_seed_thread(void *a) {
    (void) a;
    rp_seed_from_netrec();
    return NULL;
}

void eg_rp_install(void) {
    if (g_rp_installed) return;
    g_rp_installed = 1;
    g_snew = (fn_s1) eg_name_gate_string_new();
    g_vget = (fn_vget) eg_sym_find("il2cpp_object_get_virtual_method");
    g_wb = (fn_wb) eg_sym_find("il2cpp_gc_wbarrier_set_field");
    pthread_t t;
    if (pthread_create(&t, NULL, rp_seed_thread, NULL) == 0) pthread_detach(t);
#if defined(__aarch64__)
    int nf = 0;
    void *parse = eg_of_method_ptr("HttpRequest", "Parse", 1, &nf);
    void *conn = eg_of_method_ptr("HttpSystem", "connectWWW", 1, &nf);
    g_ser = (fn_ser) eg_of_method_ptr("MiniJSON.Json", "Serialize", 1, &nf);
    g_deser = (fn_deser) eg_of_method_ptr("MiniJSON.Json", "Deserialize", 1, &nf);
    g_getp = (fn_getp) eg_of_method_ptr("HttpRequest", "get_Param", 0, &nf);
    g_iserr = (fn_iserr) eg_of_method_ptr("HttpRequest", "get_IsError", 0, &nf);
    g_notify_mi = eg_of_method_info("HttpRequest", "HttpNotify", 1);
    if (!parse || !conn || !g_ser || !g_deser || !g_getp || !g_notify_mi || !g_snew) {
        RPW("重放未挂: parse=%p conn=%p ser=%p deser=%p getp=%p notify=%p snew=%p", parse, conn,
            (void *) g_ser, (void *) g_deser, (void *) g_getp, g_notify_mi, (void *) g_snew);
        return;
    }
    A64HookFunction(parse, (void *) rp_parse_hook, (void **) &g_parse_orig);
    A64HookFunction(conn, (void *) rp_conn_hook, (void **) &g_conn_orig);
    void *reach = eg_of_method_ptr("HttpSystem", "get_IsReachable", 0, &nf);
    if (reach) A64HookFunction(reach, (void *) rp_reach_hook, (void **) &g_reach_orig);
    if (g_parse_orig && g_conn_orig) g_rp_ready = 1;
    RPI("单机重放已挂 ready=%d reach=%p (wb=%p vget=%p)", g_rp_ready, (void *) g_reach_orig, (void *) g_wb, (void *) g_vget);
#endif
}
