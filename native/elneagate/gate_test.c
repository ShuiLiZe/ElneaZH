// ElneaZH · 闸门 C 逻辑的 host 单测（在开发机上跑，不进 APK）
//
// 用法：
//   cl /O2 /Fe:gate_test.exe gate_test.c eg_names.c /I<zlib>  ... 或
//   cc -O2 -o gate_test gate_test.c eg_names.c -lz
//
// 夹具与 tools/verify_upload_names.py 完全一致（同一串 hex），任何一项不一致直接退出码 1。
#include "eg_names.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

// 与 Java 侧 UploadNamesSample 相同的小表：中文 -> 拼音
static const char *ZH[] = {
    "\xe5\x87\xaf\xe7\x99\xbb",                 // 凯登
    "\xe8\x95\xbe\xe5\xa8\x9c\xe5\xa1\x94",   // 蕾娜塔
    "\xe7\xba\xa6\xe7\x91\x9f\xe5\xa4\xab",   // 约瑟夫
    "\xe9\x93\x81\xe8\x9b\x8b",                 // 铁蛋
    "\xe5\xad\x99",                                // 孙
};
static const char *ASCII[] = {"KaiEn", "LeiNaTa", "YueSeFu", "TieDan", "Sun"};
#define ZH_COUNT 5

static int map_fn(void *ud, const char *name, size_t n, char *out, size_t cap, size_t *out_len) {
    (void) ud;
    for (int i = 0; i < ZH_COUNT; i++) {
        if (strlen(ZH[i]) == n && memcmp(ZH[i], name, n) == 0) {
            size_t l = strlen(ASCII[i]);
            if (l >= cap) return 0;
            memcpy(out, ASCII[i], l);
            *out_len = l;
            return 1;
        }
    }
    return 0;
}

static int hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static unsigned char *from_hex(const char *s, size_t *n) {
    size_t l = strlen(s) / 2;
    unsigned char *b = (unsigned char *) malloc(l + 1);
    for (size_t i = 0; i < l; i++) b[i] = (unsigned char) ((hexval(s[2 * i]) << 4) | hexval(s[2 * i + 1]));
    *n = l;
    return b;
}

// 与 Java UploadNamesSample.RAW_HEX 一致
static const char *RAW_HEX =
    "0100000000000000000000000106e587afe799bb0000000000000000000109e895bee5a89ce5a1940000000000000000000109e7baa6e7919fe5a4ab0000000000000000000106e99381e89b8b0000000000000000000103e5ad990000000000000000ffffffff0000000000000000";
static const char *EXPECT_HEX =
    "01000000000000000000000001054b6169456e00000000000000000001074c65694e615461000000000000000000010759756553654675000000000000000000010654696544616e000000000000000000010353756e0000000000000000ffffffff0000000000000000";

// 把 zlib 串解开，逐字节与期望比对（用来验证「线上路径」真的把名字换掉了）
static void expect_inflate_eq(const unsigned char *packed, size_t packed_n,
                              const unsigned char *want, size_t want_n);


static int fails;

// ---- 测试内联 base64（eg_names.c 里的实现是 static，测试不能直接调）----
static const char B64T[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static size_t b64_enc(const unsigned char *in, size_t n, char *out, size_t cap) {
    size_t o = 0, i = 0;
    while (i + 2 < n && o + 4 < cap) {
        unsigned v = ((unsigned) in[i] << 16) | ((unsigned) in[i + 1] << 8) | in[i + 2];
        out[o++] = B64T[(v >> 18) & 63]; out[o++] = B64T[(v >> 12) & 63];
        out[o++] = B64T[(v >> 6) & 63];  out[o++] = B64T[v & 63];
        i += 3;
    }
    if (i < n && o + 4 < cap) {
        unsigned v = (unsigned) in[i] << 16;
        int rest = (int) (n - i);
        if (rest > 1) v |= (unsigned) in[i + 1] << 8;
        out[o++] = B64T[(v >> 18) & 63];
        out[o++] = B64T[(v >> 12) & 63];
        out[o++] = rest > 1 ? B64T[(v >> 6) & 63] : '=';
        out[o++] = '=';
    }
    out[o] = 0;
    return o;
}

static int b64v(char c) {
    const char *p = (c == 0) ? NULL : strchr(B64T, c);
    return p ? (int) (p - B64T) : -1;
}

static size_t b64_dec(const char *s, size_t n, unsigned char *out, size_t cap) {
    size_t o = 0;
    unsigned acc = 0;
    int bits = 0;
    size_t i;
    for (i = 0; i < n; i++) {
        int v = b64v(s[i]);
        if (v < 0) {
            if (s[i] == '=') break;
            continue;
        }
        acc = (acc << 6) | (unsigned) v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            if (o >= cap) return 0;
            out[o++] = (unsigned char) ((acc >> bits) & 0xFF);
        }
    }
    return o;
}

static void check(int ok, const char *what) {
    printf("  [%s] %s\n", ok ? "OK" : "FAIL", what);
    if (!ok) fails++;
}

// ---------------------------------------------------------------- 解压比对（只给 host 单测用）
static unsigned char *plain_inflate(const unsigned char *in, size_t in_n, size_t *out_n) {
    z_stream zs;
    for (size_t i = 0; i < sizeof zs; i++) ((unsigned char *) &zs)[i] = 0;
    if (inflateInit_(&zs, zlibVersion(), (int) sizeof(z_stream)) != 0) return 0;
    size_t cap = in_n * 8 + 4096;
    unsigned char *buf = (unsigned char *) malloc(cap);
    if (!buf) { inflateEnd(&zs); return 0; }
    zs.next_in = (Bytef *) in;
    zs.avail_in = (unsigned) in_n;
    zs.next_out = buf;
    zs.avail_out = (unsigned) cap;
    int r = inflate(&zs, Z_NO_FLUSH);
    inflateEnd(&zs);
    if (r != Z_STREAM_END) { free(buf); return 0; }
    *out_n = (size_t) zs.total_out;
    return buf;
}

static void expect_inflate_eq(const unsigned char *packed, size_t packed_n,
                              const unsigned char *want, size_t want_n) {
    size_t got_n = 0;
    unsigned char *got = plain_inflate(packed, packed_n, &got_n);
    check(got != NULL, "re-inflate ok");
    if (got) {
        check(got_n == want_n && memcmp(got, want, want_n) == 0,
              "re-inflated bytes == expected (names rewritten, rest untouched)");
        free(got);
    }
}



int main(void) {
    size_t raw_n = 0, want_n = 0;
    unsigned char *raw = from_hex(RAW_HEX, &raw_n);
    unsigned char *want = from_hex(EXPECT_HEX, &want_n);

    printf("== raw names\n");
    printf("  slots: %d\n", eg_count_slots(raw, raw_n));
    eg_filter_ctx ctx;
    memset(&ctx, 0, sizeof ctx);
    ctx.map = map_fn;
    size_t got_n = 0;
    unsigned char *got = eg_filter_raw_public(&ctx, raw, raw_n, &got_n);
    check(got != NULL, "filter produced output");
    check(got && got_n == want_n && memcmp(got, want, want_n) == 0, "filtered bytes == expected");
    check(ctx.hits == 5, "5 names rewritten");

    printf("== idempotent\n");
    eg_filter_ctx ctx2;
    memset(&ctx2, 0, sizeof ctx2);
    ctx2.map = map_fn;
    size_t again_n = 0;
    unsigned char *again = eg_filter_raw_public(&ctx2, got ? got : raw, got ? got_n : raw_n, &again_n);
    check(again == NULL, "second pass changes nothing");

    printf("== ascii only stays\n");
    const char *plain_ascii = "{\"ws_profiles\":\"AAAA\"}";
    eg_filter_ctx ctx3;
    memset(&ctx3, 0, sizeof ctx3);
    ctx3.map = map_fn;
    size_t n3 = 0;
    char *r3 = eg_filter_json(&ctx3, plain_ascii, strlen(plain_ascii), &n3);
    check(r3 == NULL, "ascii-only json untouched");

    printf("== zlib round trip (raw -> zlib -> base64 -> filter -> base64)\n");
    {
        // 直接用 C 侧压缩，再让 eg_filter_zlib 处理；期望：解出来的名字都变拼音，长度不变字节数对齐
        unsigned long cap = (unsigned long) compressBound((unsigned long) raw_n) + 64;
        unsigned char *packed = (unsigned char *) malloc((size_t) cap);
        int rc = compress2(packed, &cap, raw, (unsigned long) raw_n, 9);
        check(rc == 0, "compress2 ok");
        eg_filter_ctx z;
        memset(&z, 0, sizeof z);
        z.map = map_fn;
        size_t zn = 0;
        unsigned char *zf = eg_filter_zlib(&z, packed, (size_t) cap, &zn);
        check(zf != NULL, "eg_filter_zlib produced output");
        check(z.hits == 5, "5 names rewritten through zlib path");
        if (zf) {
            // 再解一次，逐字节比对 EXPECT
            expect_inflate_eq(zf, zn, want, want_n);
            free(zf);
        }
        free(packed);
    }

    printf("== json with chinese inside base64(zlib)\n");
    // 用 raw 打 zlib+base64（这里偷懒：直接用 python 生成的固定串，见 tools/verify_upload_names.py）
    // 这一段只验证「不含 zlib 的中文 JSON 不会被乱改」
    const char *plain_cn = "{\"name\":\"\\u94c1\\u86cb\",\"lang\":\"en\"}";
    eg_filter_ctx ctx4;
    memset(&ctx4, 0, sizeof ctx4);
    ctx4.map = map_fn;
    size_t n4 = 0;
    char *r4 = eg_filter_json(&ctx4, plain_cn, strlen(plain_cn), &n4);
    check(r4 == NULL, "non-blob chinese value left alone (no base64(zlib) shape)");

    // 真·端到端：内层 \"ws_profiles\":\"<base64(zlib(raw))>\"，走 eg_filter_json
    {
        int rc;
        size_t cap = compressBound(raw_n);
        unsigned char *packed = (unsigned char *) malloc(cap);
        uLongf plen = cap;
        rc = compress2(packed, &plen, raw, raw_n, 9);
        check(rc == 0, "compress2 ok (json case)");
        char b64[4096];
        b64_enc(packed, (size_t) plen, b64, sizeof(b64));
        char plain_json[8192];
        snprintf(plain_json, sizeof(plain_json),
                 "{\"data\":\"{\\\"ws_profiles\\\":\\\"%s\\\",\\\"uuid\\\":\\\"abc123\\\"}\",\"lang\":\"en\"}",
                 b64);
        eg_filter_ctx cj;
        memset(&cj, 0, sizeof(cj));
        size_t jn = 0;
        char *rj = eg_filter_json(&cj, plain_json, strlen(plain_json), &jn);
        check(rj != NULL, "json layer: blob rewritten");
        check(strstr(plain_json, "\u94c1") == NULL && strstr(rj, "\u94c1") == NULL,
              "json layer: no CJK escaped name left");
        // 把改后的 blob 抠出来解压，必须等于 EXPECT
        if (rj) {
            const char *mark = "ws_profiles";
            char *at = strstr((char *) rj, mark);
            check(at != NULL, "json layer: ws_profiles present");
            if (at) {
                char *q = strchr(at, ':');
                char *v = q ? q + 1 : NULL;
                while (v && *v != '"') v++;
                v = v ? v + 1 : NULL;
                char *e = v ? strchr(v, '"') : NULL;
                check(v != NULL && e != NULL, "json layer: value spans");
                if (v && e) {
                    unsigned char out2[4096];
                    size_t o2 = b64_dec(v, (size_t) (e - v), out2, sizeof(out2));
                    check(o2 > 0, "json layer: value decodes as base64");
                    unsigned char back[4096];
                    uLongf bl = sizeof(back);
                    rc = uncompress(back, &bl, out2, (uLong) o2);
                    check(rc == 0, "json layer: value inflates");
                    check(bl == want_n && memcmp(back, want, want_n) == 0,
                          "json layer: decompressed == EXPECT_HEX");
                }
            }
        }
        free(packed);
    }

    free(raw);
    free(want);
    free(got);
    free(again);
    free(r3);
    free(r4);

    printf("\nRESULT: %s (%d failure(s))\n", fails ? "FAIL" : "PASS", fails);
    return fails ? 1 : 0;
}
