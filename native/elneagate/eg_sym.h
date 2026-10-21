// ElneaZH · il2cpp 符号解析（dlsym 命名空间兜底）
#ifndef EG_SYM_H
#define EG_SYM_H

#include <stddef.h>
#include <stdint.h>

enum {
    EG_SYM_STAGE_NONE = 0,
    EG_SYM_STAGE_DEFAULT = 1,   // dlsym(RTLD_DEFAULT) 直接命中
    EG_SYM_STAGE_HANDLE = 2,    // dlopen("libil2cpp.so", NOLOAD) + dlsym
    EG_SYM_STAGE_MAPS = 3,      // 从 /proc/self/maps 取绝对路径再 dlopen(NOLOAD)
    EG_SYM_STAGE_DYNSYM = 4,    // 基址 + ELF .dynsym 自查（纯读）
    EG_SYM_STAGE_READY = 5,     // 只找到库、还没解析符号
    EG_SYM_STAGE_FAILED = 6,    // 连 libil2cpp.so 都没找到
};

// 按名字解析一个 libil2cpp 导出符号；四级依次尝试，失败返回 NULL（不崩）
void *eg_sym_find(const char *name);

// 解析一串必需符号；全部拿到返回 1。缺的第一个名字写进 missing。
int eg_sym_bind(const char **names, void **out, int n, char *missing, size_t missing_len);

// dlopen 一个已在内存里的 so（NOLOAD）：先按 soname，再按 /proc/self/maps 的绝对路径
void *eg_lib(const char *soname);
void *eg_dlopen_from_maps(const char *soname);

// /proc/self/maps 里 soname 的加载基址（文件偏移 0 那段）；找不到返回 0
uintptr_t eg_lib_base(const char *soname);

// 最后一次成功的解析方式（给 selfcheck 写原因用）
const char *eg_sym_stage(void);

#endif  // EG_SYM_H
