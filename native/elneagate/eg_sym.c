// ElneaZH · il2cpp 符号解析（跨 Android 链接器命名空间）
//
// 为什么单独一个文件：dlsym(RTLD_DEFAULT, "il2cpp_string_new") 在 Android 上只搜「本进程的
// RTLD_DEFAULT 作用域」——也就是 libelneagate.so 自己被 dlopen 时所在的命名空间（game 的
// classloader-namespace）。libil2cpp.so 是 game 的 assets/ 里由 Unity 自己 dlopen 的，
// 未必出现在那个作用域里，于是 dlsym 明明函数就在内存里却返回 NULL。
//
// 这里按四级依次下降，任何一级成功就用它，四个都失败返回 NULL（调用方写原因、不崩）：
//
//   1) dlsym(RTLD_DEFAULT, name)                       —— 最便宜的常见情况
//   2) dlopen("libil2cpp.so", RTLD_NOW | RTLD_NOLOAD)  —— 拿句柄再 dlsym（同命名空间也可）
//   3) dlopen("<maps 里的绝对路径>", RTLD_NOW|NOLOAD)   —— 命名空间按路径建句柄兜底
//   4) /proc/self/maps 找基址 + 读 ELF .dynsym 自己查 —— 只读内存，不加载任何东西
//
// 第 4 级是真兜底：只用到 PT_LOAD / PT_DYNAMIC，不依赖动态链接器，也不写任何内存。
#include "eg_sym.h"

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define TAG "ElneaSym"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// 只保留一份「libil2cpp.so 在哪」的结论；跨线程读，不做锁（最坏重复解析一次）
static volatile int g_stage = EG_SYM_STAGE_NONE;
static void *g_handle;
static uintptr_t g_lib_base;
static char g_lib_path[256];

void *eg_lib(const char *soname) {
    if (!soname || !soname[0]) return NULL;
    // 1) 已经在 RTLD_DEFAULT 里？dlsym 一个必然存在的符号来问
    void *h = dlopen(soname, RTLD_NOW | RTLD_NOLOAD);
    if (h) return h;
    // 2) 从 maps 里找到它的绝对路径，再按路径 NOLOAD 建句柄
    return eg_dlopen_from_maps(soname);
}

void *eg_dlopen_from_maps(const char *soname) {
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) return NULL;
    char line[512];
    char path[256];
    path[0] = 0;
    size_t sl = strlen(soname);
    while (fgets(line, sizeof line, f)) {
        char *p = strchr(line, '/');
        if (!p) continue;
        size_t l = strlen(p);
        while (l && (p[l - 1] == '\n' || p[l - 1] == '\r')) p[--l] = 0;
        if (l < sl) continue;
        if (strcmp(p + l - sl, soname) != 0) continue;
        memset(path, 0, sizeof path);
        strncpy(path, p, sizeof path - 1);
        break;
    }
    fclose(f);
    if (!path[0]) return NULL;
    void *h = dlopen(path, RTLD_NOW | RTLD_NOLOAD);
    if (h) LOGI("dlopen(%s, NOLOAD) ok from maps", path);
    return h;
}

uintptr_t eg_lib_base(const char *soname) {
    if (!soname || !soname[0]) return 0;
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) return 0;
    char line[512];
    size_t sl = strlen(soname);
    uintptr_t best = 0;
    uintptr_t bestoff = (uintptr_t) -1;
    while (fgets(line, sizeof line, f)) {
        unsigned long long lo = 0, hi = 0, off = 0;
        if (sscanf(line, "%llx-%llx %*s %llx", &lo, &hi, &off) != 3) continue;
        char *p = strchr(line, '/');
        if (!p) continue;
        size_t l = strlen(p);
        while (l && (p[l - 1] == '\n' || p[l - 1] == '\r')) p[--l] = 0;
        if (l < sl || strcmp(p + l - sl, soname) != 0) continue;
        // 取文件偏移最小的一段：它就是 vaddr 0 的映射，base = 段起 - 偏移
        if (off < bestoff) {
            bestoff = off;
            best = (uintptr_t) lo - (uintptr_t) off;
        }
    }
    fclose(f);
    return best;
}

// ---------------------------------------------------------------- ELF .dynsym 自查（第 4 级）

static int mem_ok(uintptr_t a, size_t n) {
    if (!a) return 0;
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) return 0;
    char line[512];
    int ok = 0;
    while (fgets(line, sizeof line, f)) {
        unsigned long long lo = 0, hi = 0;
        if (sscanf(line, "%llx-%llx", &lo, &hi) != 2) continue;
        if (a >= lo && a + n <= hi) { ok = 1; break; }
    }
    fclose(f);
    return ok;
}

static void *dynsym_lookup(uintptr_t base, const char *name) {
    const ElfW(Ehdr) *eh = (const ElfW(Ehdr) *) base;
    if (!mem_ok(base, sizeof *eh) || memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0) return NULL;
    if (eh->e_machine != EM_AARCH64 && eh->e_machine != EM_ARM) return NULL;

    const ElfW(Phdr) *ph = (const ElfW(Phdr) *) (base + eh->e_phoff);
    if (!mem_ok((uintptr_t) ph, eh->e_phnum * sizeof *ph)) return NULL;

    uintptr_t dyn = 0;
    size_t dynsz = 0;
    uintptr_t symtab = 0, strtab = 0;
    size_t symsz = 0, strsz = 0;
    uintptr_t addend = 0;   // 非 PIE（dlpi_addr==0）时直接用 p_vaddr
    for (int i = 0; i < eh->e_phnum; i++) {
        if (ph[i].p_type == PT_DYNAMIC) {
            dyn = base + ph[i].p_vaddr;
            dynsz = ph[i].p_memsz;
        } else if (ph[i].p_type == PT_LOAD && ph[i].p_offset == 0) {
            addend = base - (uintptr_t) ph[i].p_vaddr;
        }
    }
    (void) addend;
    if (!dyn || !mem_ok(dyn, dynsz)) return NULL;

    // 遍历 .dynamic：DT_SYMTAB / DT_STRTAB / DT_HASH / DT_GNU_HASH 只需前三个 + 两个尺寸
    const ElfW(Dyn) *d = (const ElfW(Dyn) *) dyn;
    uintptr_t gnuhash = 0, hash = 0;
    for (size_t i = 0; i < dynsz / sizeof *d; i++) {
        uintptr_t v = (uintptr_t) d[i].d_un.d_ptr;
        switch (d[i].d_tag) {
            case DT_SYMTAB: symtab = v; break;
            case DT_STRTAB: strtab = v; break;
            case DT_STRSZ: strsz = (size_t) v; break;
            case DT_SYMENT: symsz = (size_t) v; break;
            case DT_GNU_HASH: gnuhash = v; break;
            case DT_HASH: hash = v; break;
            default: break;
        }
    }
    if (!symtab || !strtab || !strsz) return NULL;
    if (!symsz) symsz = sizeof(ElfW(Sym));

    // 符号表条数：优先 sysv hash 的 nchain；否则从 gnu hash 反推最大 index
    size_t nsyms = 0;
    if (hash && mem_ok(hash, 2 * sizeof(uint32_t))) {
        const uint32_t *h = (const uint32_t *) hash;
        nsyms = h[1];
    } else if (gnuhash && mem_ok(gnuhash, 16)) {
        const uint32_t *g = (const uint32_t *) gnuhash;
        uint32_t nbuckets = g[0], symbias = g[1], bloom = g[2], nbloom = g[3];
        const uint32_t *buckets = g + 4 + (nbloom * sizeof(ElfW(Addr)) / 4);
        if (!mem_ok((uintptr_t) buckets, nbuckets * 4)) return NULL;
        uint32_t maxsym = 0;
        for (uint32_t i = 0; i < nbuckets; i++)
            if (buckets[i] > maxsym) maxsym = buckets[i];
        if (!maxsym) return NULL;
        const uint32_t *chain = buckets + nbuckets;
        if (!mem_ok((uintptr_t) chain, (maxsym - symbias + 1) * 4 + 4)) return NULL;
        uint32_t v = maxsym;
        while (!(chain[v - symbias] & 1u)) {
            v++;
            if (!mem_ok((uintptr_t) chain, (v - symbias + 1) * 4 + 4)) return NULL;
        }
        nsyms = (size_t) v + 1;
    } else {
        return NULL;
    }
    if (!mem_ok(symtab, nsyms * symsz)) return NULL;
    if (!mem_ok(strtab, strsz)) return NULL;

    const ElfW(Sym) *syms = (const ElfW(Sym) *) symtab;
    const char *strs = (const char *) strtab;
    for (size_t i = 0; i < nsyms; i++) {
        if (syms[i].st_name == 0 || syms[i].st_name >= strsz) continue;
        if (syms[i].st_shndx == SHN_UNDEF) continue;
        if (syms[i].st_value == 0) continue;
        const char *n = strs + syms[i].st_name;
        if (strcmp(n, name) != 0) continue;
        uintptr_t addr = base + (uintptr_t) syms[i].st_value;
        LOGI("dynsym hit %s @ %p", name, (void *) addr);
        return (void *) addr;
    }
    return NULL;
}

// ---------------------------------------------------------------- 对外

// 返回 1 已就绪（至少要能用：handle 或 base 之一）
static int ensure_lib(void) {
    if (g_stage == EG_SYM_STAGE_READY) return 1;
    if (g_stage == EG_SYM_STAGE_FAILED) return 0;
    if (!g_handle) g_handle = dlopen("libil2cpp.so", RTLD_NOW | RTLD_NOLOAD);
    if (!g_handle) {
        void *h = eg_dlopen_from_maps("libil2cpp.so");
        if (h) g_handle = h;
    }
    if (!g_lib_base) g_lib_base = eg_lib_base("libil2cpp.so");
    if (!g_handle && !g_lib_base) {
        g_stage = EG_SYM_STAGE_FAILED;
        LOGW("libil2cpp.so not found (handle=0 base=0)");
        return 0;
    }
    g_stage = EG_SYM_STAGE_READY;
    return 1;
}

void *eg_sym_find(const char *name) {
    if (!name || !name[0]) return NULL;
    // 1) RTLD_DEFAULT
    void *p = dlsym(RTLD_DEFAULT, name);
    if (p) { g_stage = g_stage > EG_SYM_STAGE_DEFAULT ? g_stage : EG_SYM_STAGE_DEFAULT; return p; }
    if (!ensure_lib()) return NULL;
    // 2) 句柄
    if (g_handle) {
        p = dlsym(g_handle, name);
        if (p) { g_stage = EG_SYM_STAGE_HANDLE; return p; }
    }
    // 3) maps 路径句柄
    if (!g_handle) {
        void *h = eg_dlopen_from_maps("libil2cpp.so");
        if (h) {
            g_handle = h;
            p = dlsym(h, name);
            if (p) { g_stage = EG_SYM_STAGE_MAPS; return p; }
        }
    }
    // 4) 读 .dynsym
    if (!g_lib_base) g_lib_base = eg_lib_base("libil2cpp.so");
    if (g_lib_base) {
        p = dynsym_lookup(g_lib_base, name);
        if (p) { g_stage = EG_SYM_STAGE_DYNSYM; return p; }
    }
    return NULL;
}

// 解析一串必需符号：全部拿到才写进 out[] 并返回 1；缺哪个就写 missing 里第一个名字
int eg_sym_bind(const char **names, void **out, int n, char *missing, size_t missing_len) {
    int ok = 1;
    for (int i = 0; i < n; i++) {
        out[i] = eg_sym_find(names[i]);
        if (!out[i]) {
            ok = 0;
            if (missing && missing_len) {
                snprintf(missing, missing_len, "%s", names[i]);
            }
            break;
        }
    }
    return ok;
}

const char *eg_sym_stage(void) {
    switch (g_stage) {
        case EG_SYM_STAGE_DEFAULT: return "rtld-default";
        case EG_SYM_STAGE_HANDLE: return "dlopen-handle";
        case EG_SYM_STAGE_MAPS: return "maps-path";
        case EG_SYM_STAGE_DYNSYM: return "elf-dynsym";
        case EG_SYM_STAGE_READY: return "libil2cpp-only";
        case EG_SYM_STAGE_FAILED: return "no-libil2cpp";
        default: return "none";
    }
}
