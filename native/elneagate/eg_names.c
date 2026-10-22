// ElneaZH · 上传前名字闸门（C 侧纯逻辑实现）
//
// 设计原则：宁可漏改，绝不写坏。任何一步「看不懂」就返回 NULL，调用方用原明文。
#include "eg_names.h"

#include <stdlib.h>
#include <string.h>
#include <zlib.h>

// ------------------------------------------------------------------ 基础

static int eg_is_han_cp(unsigned cp) {
    return (cp >= 0x4E00 && cp <= 0x9FFF)     // CJK 统一表意
        || (cp >= 0x3400 && cp <= 0x4DBF)     // 扩展 A
        || (cp >= 0xF900 && cp <= 0xFAFF)     // 兼容表意
        || (cp >= 0x20000 && cp <= 0x2FA1F);  // 扩展 B+
}

// 解一个 UTF-8 码点：返回消耗字节数，非法返回 0。允许超长? 不允许（严格）。
static int eg_utf8_next(const unsigned char *s, size_t n, size_t i, unsigned *cp) {
    unsigned c = s[i];
    if (c < 0x80) { *cp = c; return 1; }
    int len; unsigned min;
    if ((c & 0xE0) == 0xC0) { len = 2; min = 0x80; *cp = c & 0x1F; }
    else if ((c & 0xF0) == 0xE0) { len = 3; min = 0x800; *cp = c & 0x0F; }
    else if ((c & 0xF8) == 0xF0) { len = 4; min = 0x10000; *cp = c & 0x07; }
    else return 0;
    if (i + (size_t) len > n) return 0;
    for (int k = 1; k < len; k++) {
        unsigned d = s[i + k];
        if ((d & 0xC0) != 0x80) return 0;
        *cp = (*cp << 6) | (d & 0x3F);
    }
    if (*cp < min) return 0;                        // 超长编码
    if (*cp >= 0xD800 && *cp <= 0xDFFF) return 0;   // 代理区
    if (*cp > 0x10FFFF) return 0;
    return len;
}

int eg_has_han(const char *s, size_t n) {
    if (!s) return 0;
    size_t i = 0;
    while (i < n) {
        unsigned cp;
        int k = eg_utf8_next((const unsigned char *) s, n, i, &cp);
        if (k == 0) { i++; continue; }
        if (eg_is_han_cp(cp)) return 1;
        i += (size_t) k;
    }
    return 0;
}

// 严格合法性：整串必须能被严格解码（用于名字槽识别）。
static int eg_utf8_valid(const unsigned char *s, size_t n) {
    size_t i = 0;
    while (i < n) {
        unsigned cp;
        int k = eg_utf8_next(s, n, i, &cp);
        if (k == 0) return 0;
        i += (size_t) k;
    }
    return i == n;
}

int eg_slot_len(const unsigned char *raw, size_t len, size_t i) {
    if (!raw || i + 2 > len) return -1;
    if (raw[i] != 0x01) return -1;
    int n = raw[i + 1];
    // 上限 255（长度字节的极限），不是 64：拼音比汉字长，长名字不能被漏掉。
    if (n < 1 || n > 255) return -1;
    if (i + 2 + (size_t) n > len) return -1;
    for (int k = 0; k < n; k++) {
        unsigned char v = raw[i + 2 + k];
        if (v < 0x20 || v == 0x7F) return -1;
    }
    if (!eg_utf8_valid(raw + i + 2, (size_t) n)) return -1;
    return n;
}

int eg_count_slots(const unsigned char *raw, size_t len) {
    if (!raw) return 0;
    int n = 0;
    size_t i = 0;
    while (i < len) {
        int sl = eg_slot_len(raw, len, i);
        if (sl < 0) { i++; continue; }
        n++;
        i += 2 + (size_t) sl;
    }
    return n;
}

// ------------------------------------------------------------------ zlib

static unsigned char *eg_inflate(const unsigned char *in, size_t in_len, size_t *out_len) {
    if (!in || in_len < 2 || in[0] != 0x78) return NULL;
    z_stream zs;
    memset(&zs, 0, sizeof zs);
    if (inflateInit(&zs) != Z_OK) return NULL;
    size_t cap = in_len * 6 + 4096;
    unsigned char *buf = (unsigned char *) malloc(cap);
    if (!buf) { inflateEnd(&zs); return NULL; }
    zs.next_in = (Bytef *) in;
    zs.avail_in = (uInt) in_len;
    size_t used = 0;
    for (;;) {
        if (used == cap) {
            size_t ncap = cap * 2;
            if (ncap > (64u << 20)) { free(buf); inflateEnd(&zs); return NULL; }  // 上限 64MB
            unsigned char *nb = (unsigned char *) realloc(buf, ncap);
            if (!nb) { free(buf); inflateEnd(&zs); return NULL; }
            buf = nb;
            cap = ncap;
        }
        zs.next_out = buf + used;
        zs.avail_out = (uInt) (cap - used);
        int r = inflate(&zs, Z_NO_FLUSH);
        used = cap - zs.avail_out;
        if (r == Z_STREAM_END) break;
        if (r == Z_OK) continue;
        free(buf);
        inflateEnd(&zs);
        return NULL;
    }
    inflateEnd(&zs);
    *out_len = used;
    return buf;
}

static unsigned char *eg_deflate(const unsigned char *in, size_t in_len, size_t *out_len) {
    uLongf cap = compressBound((uLong) in_len) + 64;
    unsigned char *buf = (unsigned char *) malloc(cap);
    if (!buf) return NULL;
    if (compress2(buf, &cap, in, (uLong) in_len, 9) != Z_OK) { free(buf); return NULL; }
    *out_len = cap;
    return buf;
}

// ------------------------------------------------------------------ 名字槽过滤

// 返回新缓冲区，无改动返回 NULL。
static unsigned char *eg_filter_raw(eg_filter_ctx *ctx, const unsigned char *raw, size_t len,
                                    size_t *out_len) {
    unsigned char *cur = NULL;      // 累积结果（无改动前为 NULL）
    size_t cur_len = 0;
    size_t i = 0;
    while (i < len) {
        const unsigned char *src = cur ? cur : raw;
        size_t src_len = cur ? cur_len : len;
        int sl = eg_slot_len(src, src_len, i);
        if (sl < 0) { i++; continue; }
        if (!eg_has_han((const char *) src + i + 2, (size_t) sl)) { i += 2 + (size_t) sl; continue; }
        char ascii[256];
        size_t an = 0;
        // map 约定：返回非 0 = 已映射（见 eg_names.h / elneagate.c eg_map）。
        // 旧代码写成 `!= 0` 就跳过 —— 等于「找到了反而不换」，真机上一个名字都没换过。
        if (ctx->map(ctx->ud, (const char *) src + i + 2, (size_t) sl, ascii, sizeof ascii, &an) == 0
                || an == 0 || an > 255) {
            i += 2 + (size_t) sl;
            continue;
        }
        // 只收纯 ASCII（拼音）
        int ok = 1;
        for (size_t k = 0; k < an; k++) {
            unsigned char c = (unsigned char) ascii[k];
            if (c < 0x20 || c >= 0x7F) { ok = 0; break; }
        }
        if (!ok) { i += 2 + (size_t) sl; continue; }

        size_t nlen = src_len - (size_t) sl + an;
        unsigned char *dst = (unsigned char *) malloc(nlen);
        if (!dst) { free(cur); return NULL; }
        memcpy(dst, src, i);
        dst[i] = 0x01;
        dst[i + 1] = (unsigned char) an;
        memcpy(dst + i + 2, ascii, an);
        memcpy(dst + i + 2 + an, src + i + 2 + (size_t) sl, src_len - (i + 2 + (size_t) sl));
        free(cur);
        cur = dst;
        cur_len = nlen;
        ctx->hits++;
        i += 2 + an;
    }
    if (!cur) return NULL;
    *out_len = cur_len;
    return cur;
}

// 给 JNI 自检用的公开别名（内部 eg_filter_raw 不带 zlib）
unsigned char *eg_filter_raw_public(eg_filter_ctx *ctx, const unsigned char *raw, size_t len,
                                    size_t *out_len) {
    if (!ctx || !raw) return NULL;
    return eg_filter_raw(ctx, raw, len, out_len);
}

unsigned char *eg_filter_zlib(eg_filter_ctx *ctx, const unsigned char *in, size_t in_len,
                              size_t *out_len) {
    if (!ctx || !in) return NULL;
    size_t raw_len = 0;
    unsigned char *raw = eg_inflate(in, in_len, &raw_len);
    if (!raw) return NULL;
    size_t fixed_len = 0;
    unsigned char *fixed = eg_filter_raw(ctx, raw, raw_len, &fixed_len);
    free(raw);
    if (!fixed) return NULL;                     // 无改动
    size_t pack_len = 0;
    unsigned char *packed = eg_deflate(fixed, fixed_len, &pack_len);
    free(fixed);
    if (!packed) return NULL;
    *out_len = pack_len;
    return packed;
}

// ------------------------------------------------------------------ base64

static const char EG_B64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static int eg_b64_val(unsigned char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+' || c == '-') return 62;
    if (c == '/' || c == '_') return 63;
    return -1;
}

// 长度 >=24、4 的倍数、合法字符、前 4 字符解出首字节 0x78
static int eg_looks_zlib_b64(const char *v, size_t n) {
    if (n < 24 || (n % 4) != 0) return 0;
    for (size_t i = 0; i < n; i++) {
        if (eg_b64_val((unsigned char) v[i]) < 0 && v[i] != '=') return 0;
    }
    int a = eg_b64_val((unsigned char) v[0]), b = eg_b64_val((unsigned char) v[1]);
    if (a < 0 || b < 0) return 0;
    return (((a << 2) | (b >> 4)) & 0xFF) == 0x78;
}

static unsigned char *eg_b64_decode(const char *s, size_t n, size_t *out_len) {
    if (n % 4) return NULL;
    size_t cap = n / 4 * 3 + 3;
    unsigned char *out = (unsigned char *) malloc(cap);
    if (!out) return NULL;
    size_t o = 0;
    for (size_t i = 0; i < n; i += 4) {
        int v[4];
        int pad = 0;
        for (int k = 0; k < 4; k++) {
            unsigned char c = (unsigned char) s[i + k];
            if (c == '=') { v[k] = 0; pad++; }
            else {
                v[k] = eg_b64_val(c);
                if (v[k] < 0) { free(out); return NULL; }
            }
        }
        if (pad > 2) { free(out); return NULL; }
        unsigned t = ((unsigned) v[0] << 18) | ((unsigned) v[1] << 12) | ((unsigned) v[2] << 6) | (unsigned) v[3];
        out[o++] = (unsigned char) (t >> 16);
        if (pad < 2) out[o++] = (unsigned char) (t >> 8);
        if (pad < 1) out[o++] = (unsigned char) t;
    }
    *out_len = o;
    return out;
}

static char *eg_b64_encode(const unsigned char *in, size_t n, size_t *out_len) {
    size_t cap = ((n + 2) / 3) * 4 + 1;
    char *out = (char *) malloc(cap);
    if (!out) return NULL;
    size_t o = 0, i = 0;
    while (i + 3 <= n) {
        unsigned t = ((unsigned) in[i] << 16) | ((unsigned) in[i + 1] << 8) | in[i + 2];
        out[o++] = EG_B64[(t >> 18) & 63];
        out[o++] = EG_B64[(t >> 12) & 63];
        out[o++] = EG_B64[(t >> 6) & 63];
        out[o++] = EG_B64[t & 63];
        i += 3;
    }
    if (n - i == 1) {
        unsigned t = (unsigned) in[i] << 16;
        out[o++] = EG_B64[(t >> 18) & 63];
        out[o++] = EG_B64[(t >> 12) & 63];
        out[o++] = '=';
        out[o++] = '=';
    } else if (n - i == 2) {
        unsigned t = ((unsigned) in[i] << 16) | ((unsigned) in[i + 1] << 8);
        out[o++] = EG_B64[(t >> 18) & 63];
        out[o++] = EG_B64[(t >> 12) & 63];
        out[o++] = EG_B64[(t >> 6) & 63];
        out[o++] = '=';
    }
    out[o] = 0;
    *out_len = o;
    return out;
}

// 单个 base64(zlib) 值：解开 -> 过滤 -> 压回 -> base64。无改动/失败返回 NULL。
static char *eg_filter_one(eg_filter_ctx *ctx, const char *b64, size_t n, size_t *out_len) {
    if (!eg_looks_zlib_b64(b64, n)) return NULL;
    size_t pack_len = 0;
    unsigned char *packed = eg_b64_decode(b64, n, &pack_len);
    if (!packed) return NULL;
    if (pack_len < 2 || packed[0] != 0x78) { free(packed); return NULL; }
    size_t rep_len = 0;
    unsigned char *rep = eg_filter_zlib(ctx, packed, pack_len, &rep_len);
    free(packed);
    if (!rep) return NULL;
    size_t enc_len = 0;
    char *enc = eg_b64_encode(rep, rep_len, &enc_len);
    free(rep);
    if (!enc) return NULL;
    // 拼音比汉字长，允许变长；只做个「解错了」的兜底上限（4 倍 + 4KB），超了当没改。
    if (enc_len > n * 4 + 4096) { free(enc); return NULL; }
    *out_len = enc_len;
    return enc;
}

// ------------------------------------------------------------------ JSON 层

static int eg_is_ws(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; }

// i 指向开引号，返回收引号位置（处理 \\ 与 \"），失败 -1
static long eg_string_end(const char *s, size_t n, size_t i) {
    size_t j = i + 1;
    while (j < n) {
        char c = s[j];
        if (c == '\\') {
            if (j + 1 < n && (s[j + 1] == '"' || s[j + 1] == '\\')) { j += 2; continue; }
            j++;
            continue;
        }
        if (c == '"') return (long) j;
        j++;
    }
    return -1;
}

typedef struct {
    char *p;
    size_t n;
    size_t cap;
} eg_sb;

static int eg_sb_add(eg_sb *b, const char *s, size_t n) {
    if (b->n + n + 1 > b->cap) {
        size_t nc = b->cap ? b->cap : 256;
        while (nc < b->n + n + 1) nc *= 2;
        char *np = (char *) realloc(b->p, nc);
        if (!np) return -1;
        b->p = np;
        b->cap = nc;
    }
    memcpy(b->p + b->n, s, n);
    b->n += n;
    b->p[b->n] = 0;
    return 0;
}

// 在一个「已转义」的字面量里找内层 \"key\":\"value\"，替换 value。
// 返回新串（malloc），无改动返回 NULL。
static char *eg_rewrite_nested(eg_filter_ctx *ctx, const char *lit, size_t n, size_t *out_len) {
    eg_sb sb;
    memset(&sb, 0, sizeof sb);
    size_t i = 0;
    int touched = 0;
    while (i < n) {
        // 找下一个 \" 
        size_t q = n;
        for (size_t k = i; k + 1 < n; k++) {
            if (lit[k] == '\\' && lit[k + 1] == '"') { q = k; break; }
        }
        if (q == n) {
            if (eg_sb_add(&sb, lit + i, n - i) != 0) goto fail;
            break;
        }
        if (eg_sb_add(&sb, lit + i, q + 2 - i) != 0) goto fail;
        size_t start = q + 2;
        long e = -1;
        size_t scan = start;
        for (;;) {
            size_t c = n;
            for (size_t k = scan; k + 1 < n; k++) {
                if (lit[k] == '\\' && lit[k + 1] == '"') { c = k; break; }
            }
            if (c == n) break;
            size_t bs = 0;
            for (size_t b = c; b > start && lit[b - 1] == '\\'; b--) bs++;
            if (bs % 2 == 0) { e = (long) c; break; }
            scan = c + 2;
        }
        if (e < 0) {
            if (eg_sb_add(&sb, lit + q, n - q) != 0) goto fail;
            break;
        }
        size_t val_len = (size_t) e - start;
        size_t rep_len = 0;
        char *rep = eg_filter_one(ctx, lit + start, val_len, &rep_len);
        if (rep) {
            // 把 '/' 转成 '\/' 保持与原串同样的转义风格
            for (size_t k = 0; k < rep_len; k++) {
                if (rep[k] == '/') {
                    if (eg_sb_add(&sb, "\\/", 2) != 0) { free(rep); goto fail; }
                } else {
                    if (eg_sb_add(&sb, rep + k, 1) != 0) { free(rep); goto fail; }
                }
            }
            free(rep);
            touched = 1;
        } else {
            if (eg_sb_add(&sb, lit + start, val_len) != 0) goto fail;
        }
        if (eg_sb_add(&sb, "\\\"", 2) != 0) goto fail;
        i = (size_t) e + 2;
    }
    if (!touched) { free(sb.p); return NULL; }
    *out_len = sb.n;
    return sb.p;
fail:
    free(sb.p);
    return NULL;
}

char *eg_filter_json(eg_filter_ctx *ctx, const char *plain, size_t plain_len, size_t *out_len) {
    if (!ctx || !plain) return NULL;
    // 注意：真实请求体里的中文只出现在 base64(zlib(...)) 里面，明文 JSON 本身是纯 ASCII。
    // 旧代码在这里用「明文里没汉字就跳过」，结果 save2.php 的 ws_profiles / ws_familys 永远被跳过
    // （2026-10-21 用真机抓到的 save2 明文本地复现：0 个名字被换）。
    // 现在只按形状筛：没有内层存档块（\"ws_）就直接放行，省掉无关接口的扫描。
    {
        int has_ws = 0;
        for (size_t k = 0; k + 4 < plain_len; k++) {
            if (plain[k] == '\\' && plain[k + 1] == '"' && plain[k + 2] == 'w' && plain[k + 3] == 's'
                    && plain[k + 4] == '_') { has_ws = 1; break; }
        }
        if (!has_ws) return NULL;
    }
    eg_sb sb;
    memset(&sb, 0, sizeof sb);
    size_t i = 0, copied = 0, n = plain_len;
    int touched = 0;
    while (i < n) {
        if (plain[i] != '"') { i++; continue; }
        long close = eg_string_end(plain, n, i);
        if (close < 0) break;
        size_t cs = (size_t) close;
        // 前面（跳过空白）必须是 ':' 才算「值」
        long k = (long) i - 1;
        while (k >= 0 && eg_is_ws(plain[k])) k--;
        if (k >= 0 && plain[k] == ':') {
            size_t nested_len = 0;
            char *nested = eg_rewrite_nested(ctx, plain + i + 1, cs - (i + 1), &nested_len);
            if (nested) {
                if (eg_sb_add(&sb, plain + copied, (i + 1) - copied) != 0) { free(nested); goto fail; }
                if (eg_sb_add(&sb, nested, nested_len) != 0) { free(nested); goto fail; }
                free(nested);
                copied = cs;
                touched = 1;
            }
        }
        i = cs + 1;
    }
    if (!touched) { free(sb.p); return NULL; }
    if (eg_sb_add(&sb, plain + copied, n - copied) != 0) goto fail;
    *out_len = sb.n;
    return sb.p;
fail:
    free(sb.p);
    return NULL;
}
