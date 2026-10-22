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

// libil2cpp.so 的加载基址（dlpi_addr）。
//
// **首选 dl_iterate_phdr**（不受 Android 链接器命名空间限制，也不读文件），
// 拿不到才退回 /proc/self/maps；找到一次就缓存（库地址不会变，只会在还没 dlopen 时「暂时没有」）。
// 找不到返回 0 —— 调用方**必须**检查 0，绝不能拿 0 + 偏移去读写（真机 SIGSEGV 就是这么来的：
// x0=0，x25=0x18ad7e4，见 HANDOFF 17:17:21 那条）。
uintptr_t eg_lib_base(const char *soname);

// libil2cpp.so 是否已经在内存里映射（= 可以安全算 base+offset）。
// 轮询用：只有 dl_iterate_phdr / maps，绝不 dlopen、绝不解析符号。
int eg_lib_present(void);

// 强制重新查一次（库可能刚刚才 dlopen）。成功返回基址，失败 0。
uintptr_t eg_lib_base_refresh(void);

// 缓存的基址（0 = 还没有）。名字闸门 / 单机闸门共享这一份，谁先解析谁写。
uintptr_t eg_lib_base_cached(void);

// 地址区间 [a, a+n) 是否在某个已映射区间里。
//
// ⚠ maps 只在**第一次 / 超过 200ms 的过期**时读一次，之后全是查内存里的区间表；
// 旧版每次都 fopen + 全文件扫描，在 dynsym_lookup 里被调用几十次 —— 那是真机主线程被卡住
// 的直接原因之一（见 eg_log.h 的头注释与 HANDOFF）。
int eg_mem_ok(uintptr_t a, size_t n);

// 最后一次成功的解析方式（给 selfcheck 写原因用）
const char *eg_sym_stage(void);

// maps 快照现状：段数 / 距上次读取的毫秒数（-1 = 还没读过）。日志与自检用。
void eg_maps_stat(int *ranges, long long *age_ms);

#endif  // EG_SYM_H
