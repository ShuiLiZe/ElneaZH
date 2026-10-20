// ElneaZH · 上传前名字闸门（C 侧纯逻辑）
//
// 只做一件事：把 EncryptRJ256 收到的明文 JSON 里 base64(zlib(...)) 的存档块解开，
// 把其中「0x01 + 1 字节长度 + UTF-8」名字槽里的中文换成固定拼音，再压回去。
//
// 与 Java 侧 inj/com/shuilize/elneazh/inj/UploadNames.java 严格同构（夹具逐字节一致，
// 由 tools/gate_test.c 与 tools/verify_upload_names.py 交叉验证）。
#ifndef EG_NAMES_H
#define EG_NAMES_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// 把「中文（UTF-8）」映射成 ASCII 拼音；返回 0 表示不处理（调用方保持原样）。
// 实现方必须恒定：同一输入永远同一输出。
typedef int (*eg_mapper_fn)(void *ud, const char *name_utf8, size_t name_len,
                            char *out, size_t out_cap, size_t *out_len);

typedef struct {
    eg_mapper_fn map;
    void *ud;
    int hits;          // 本次实际替换掉的名字个数
} eg_filter_ctx;

// 输入/输出都是 zlib 压缩后的原始字节。成功返回新分配的缓冲区（*out_len），
// 失败或无改动返回 NULL（调用方必须原样使用旧数据）。
unsigned char *eg_filter_zlib(eg_filter_ctx *ctx, const unsigned char *in, size_t in_len,
                              size_t *out_len);

// 只扫原始字节（不走 zlib）：给 JNI 自检用，与线上路径同一个函数。
unsigned char *eg_filter_raw_public(eg_filter_ctx *ctx, const unsigned char *raw, size_t len,
                                    size_t *out_len);

// 扫描一遍，数出名字槽个数（自检/日志用）。
int eg_count_slots(const unsigned char *raw, size_t len);

// 名字槽识别：i 处若是「0x01 + len(1..64 可打印，且是合法 UTF-8)」返回 len，否则 -1。
int eg_slot_len(const unsigned char *raw, size_t len, size_t i);

// UTF-8 里有没有汉字（CJK 统一表意文字 / 兼容表意 / 扩展 A）。
int eg_has_han(const char *s, size_t n);

// 把明文 JSON 里所有 base64(zlib) 字面量就地替换（返回新分配的 UTF-8 串）。
// 任何一步不合规 -> 返回 NULL，调用方原样使用旧明文。
char *eg_filter_json(eg_filter_ctx *ctx, const char *plain, size_t plain_len, size_t *out_len);

#ifdef __cplusplus
}
#endif

#endif
