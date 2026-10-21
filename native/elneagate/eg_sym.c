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
// ⚠ 主线程阻塞事故的直接原因之一就出在这里：旧版每一次 mem_ok()（第四级里被调用几十次）
// 都 fopen("/proc/self/maps") 并整文件扫一遍，eg_lib_base / eg_dlopen_from_maps 也各扫一遍，
// 解析一个方法就是 O(n²) 的文件读取。现在改成「一次读取 → 解析成区间表 → 后续全查内存表」，
// 并且表带 200ms 缓存（libil2cpp 刚 dlopen 时才需要刷新，不会每 500ms 重读整张 maps）。
#include "eg_sym.h"

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "eg_log.h"

#define TAG "ElneaSym"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// 只保留一份「libil2cpp.so 在哪」的结论；跨线程读，不做锁（最坏重复解析一次）
static volatile int g_stage = EG_SYM_STAGE_NONE;
static void *g_handle;
static uintptr_t g_lib_base;
static char g_lib_path[256];

// ---------------------------------------------------------------- maps 快照（一次读取，查内存表）

#define EG_MAX_MAPS 2048
#define EG_MAPS_TTL_MS 200

typedef struct {
    uintptr_t lo, hi, off;
    int has_path;
} eg_map_range;

static eg_map_range g_maps[EG_MAX_MAPS];
static int g_maps_count;
static long long g_maps_at;
static pthread_mutex_t g_maps_lock = PTHREAD_MUTEX_INITIALIZER;

// 读一次 /proc/self/maps，解析成区间表（带路径的行把路径拼到 paths 里）。返回读到的段数。
// 只在 maps 锁里调用；调用方负责决定是否要刷新。
static int eg_maps_reload(void) {
    long long t0 = eg_now_ms();
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) { g_maps_count = 0; g_maps_at = eg_now_ms(); return 0; }
    int n = 0;
    char line[512];
    while (n < EG_MAX_MAPS && fgets(line, sizeof line, f)) {
        unsigned long long lo = 0, hi = 0, off = 0;
        if (sscanf(line, "%llx-%llx %*s %llx", &lo, &hi, &off) != 3) continue;
        g_maps[n].lo = (uintptr_t) lo;
        g_maps[n].hi = (uintptr_t) hi;
        g_maps[n].off = (uintptr_t) off;
        g_maps[n].has_path = strchr(line, '/') != NULL;
        n++;
    }
    fclose(f);
    g_maps_count = n;
    g_maps_at = eg_now_ms();
    eg_ilog("maps: loaded %d ranges in %lldms", n, eg_now_ms() - t0);
    return n;
}

// 拿一份不超过 TTL 的快照；force=1 时无条件重读（换 soname 找新库时用）。
static void eg_maps_ensure(int force) {
    pthread_mutex_lock(&g_maps_lock);
    if (force || g_maps_count == 0 || eg_now_ms() - g_maps_at > EG_MAPS_TTL_MS) {
        eg_maps_reload();
    }
    pthread_mutex_unlock(&g_maps_lock);
}

// 地址区间是否在某个映射里（真实 mprotect 前置检查；不碰文件）
static int mem_in_map(uintptr_t a, size_t n) {
    if (!a) return 0;
    eg_maps_ensure(0);
    int ok = 0;
    pthread_mutex_lock(&g_maps_lock);
    for (int i = 0; i < g_maps_count; i++) {
        if (a >= g_maps[i].lo && a + n <= g_maps[i].hi) { ok = 1; break; }
    }
    pthread_mutex_unlock(&g_maps_lock);
    return ok;
}

static int mem_ok(uintptr_t a, size_t n) { return mem_in_map(a, n); }

// 旧名保留（eg_sym.h 里对外）：等价于 mem_in_map。
int eg_mem_ok(uintptr_t a, size_t n) { return mem_in_map(a, n); }

// 从快照里找 soname 对应的绝对路径（取文件偏移最小的那一段）。找到返回 1。
// 单独 fopen 一次 maps（不常调：只在 handle 拿不到时），逐行按路径尾巴匹配。
static int eg_maps_find_path(const char *soname, char *out, size_t cap) {
    if (!soname || !soname[0] || !out || cap < 2) return 0;
    size_t sl = strlen(soname);
    long long t0 = eg_now_ms();
    FILE *f = fopen("/proc/self/maps", "re");
    if (!f) return 0;
    int found = 0;
    char line[512];
    uintptr_t bestoff = (uintptr_t) -1;
    while (fgets(line, sizeof line, f)) {
        unsigned long long lo = 0, hi = 0, off = 0;
        if (sscanf(line, "%llx-%llx %*s %llx", &lo, &hi, &off) != 3) continue;
        char *p = strchr(line, '/');
        if (!p) continue;
        size_t l = strlen(p);
        while (l && (p[l - 1] == '\n' || p[l - 1] == '\r')) p[--l] = 0;
        if (l < sl || strcmp(p + l - sl, soname) != 0) continue;
        if ((uintptr_t) off < bestoff) {
            bestoff = (uintptr_t) off;
            snprintf(out, cap, "%s", p);
            found = 1;
        }
    }
    fclose(f);
    if (found) eg_ilog("maps: %s -> %s (%lldms)", soname, out, eg_now_ms() - t0);
    return found;
}

void *eg_lib(const char *soname) {
    if (!soname || !soname[0]) return NULL;
    // 1) 已经在 RTLD_DEFAULT 里？dlsym 一个必然存在的符号来问
    void *h = dlopen(soname, RTLD_NOW | RTLD_NOLOAD);
    if (h) return h;
    // 2) 从 maps 里找到它的绝对路径，再按路径 NOLOAD 建句柄
    return eg_dlopen_from_maps(soname);
}

void *eg_dlopen_from_maps(const char *soname) {
    char path[256];
    long long t0 = eg_now_ms();
    if (!eg_maps_find_path(soname, path, sizeof path) || !path[0]) return NULL;
    void *h = dlopen(path, RTLD_NOW | RTLD_NOLOAD);
    if (h) {
        LOGI("dlopen(%s, NOLOAD) ok from maps (%lldms)", path, eg_now_ms() - t0);
        snprintf(g_lib_path, sizeof g_lib_path, "%s", path);
    }
    return h;
}

// soname 的加载基址（dlpi_addr）。先用 dl_iterate_phdr（O(模块数)，不读文件），
// 再退回 /proc/self/maps 里文件偏移为 0 那一段的起点。
static void *g_found_base;
static const char *g_found_soname;

static int eg_phdr_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    (void) data;
    if (!info->dlpi_name || !g_found_soname) return 0;
    const char *n = info->dlpi_name;
    size_t l = strlen(n), w = strlen(g_found_soname);
    if (l < w || strcmp(n + l - w, g_found_soname) != 0) return 0;
    g_found_base = (void *) (uintptr_t) info->dlpi_addr;
    return 1;   // 停
}

uintptr_t eg_lib_base(const char *soname) {
    if (!soname || !soname[0]) return 0;
    long long t0 = eg_now_ms();
    g_found_soname = soname;
    g_found_base = NULL;
    dl_iterate_phdr(eg_phdr_cb, NULL);
    if (g_found_base) {
        eg_ilog("base %s = %p (phdr, %lldms)", soname, g_found_base, eg_now_ms() - t0);
        return (uintptr_t) g_found_base;
    }
    // 退回 maps：文件偏移 0 那一段的 lo 就是基址
    size_t sl = strlen(soname);
    uintptr_t best = 0, bestoff = (uintptr_t) -1;
    FILE *f = fopen("/proc/self/maps", "re");
    if (f) {
        char line[512];
        while (fgets(line, sizeof line, f)) {
            unsigned long long lo = 0, hi = 0, off = 0;
            if (sscanf(line, "%llx-%llx %*s %llx", &lo, &hi, &off) != 3) continue;
            char *p = strchr(line, '/');
            if (!p) continue;
            size_t l = strlen(p);
            while (l && (p[l - 1] == '\n' || p[l - 1] == '\r')) p[--l] = 0;
            if (l < sl || strcmp(p + l - sl, soname) != 0) continue;
            if ((uintptr_t) off < bestoff) {
                bestoff = (uintptr_t) off;
                best = (uintptr_t) lo - (uintptr_t) off;
            }
        }
        fclose(f);
    }
    eg_ilog("base %s = %p (maps, %lldms)", soname, (void *) best, eg_now_ms() - t0);
    return best;
}

// ---------------------------------------------------------------- ELF .dynsym 自查（第 4 级）

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
        uint32_t nbuckets = g[0], symbias = g[1], nbloom = g[3];
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
    // 一次性把整张符号表检查一遍，循环里不再逐条查 maps（旧版就是这样 O(n²) 的）
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

// 返回 1 已就绪（至少要能用：handle 或 base 之一）。
// 只试一次：库还没 dlopen 时不要把「没有」记成永久失败，留给下一轮（有上限）。
static int ensure_lib(void) {
    if (!g_handle) g_handle = dlopen("libil2cpp.so", RTLD_NOW | RTLD_NOLOAD);
    if (!g_handle) {
        void *h = eg_dlopen_from_maps("libil2cpp.so");
        if (h) g_handle = h;
    }
    if (!g_lib_base) g_lib_base = eg_lib_base("libil2cpp.so");
    if (!g_handle && !g_lib_base) {
        g_stage = EG_SYM_STAGE_FAILED;
        return 0;
    }
    if (g_stage == EG_SYM_STAGE_NONE || g_stage == EG_SYM_STAGE_FAILED)
        g_stage = EG_SYM_STAGE_READY;
    return 1;
}

void *eg_sym_find(const char *name) {
    if (!name || !name[0]) return NULL;
    long long t0 = eg_now_ms();
    // 1) RTLD_DEFAULT
    void *p = dlsym(RTLD_DEFAULT, name);
    if (p) {
        if (g_stage < EG_SYM_STAGE_DEFAULT) g_stage = EG_SYM_STAGE_DEFAULT;
        return p;
    }
    if (!ensure_lib()) {
        // 每次逐符号打印会把日志刷爆：只打阶段变化（调用方自己按轮次限流）。
        return NULL;
    }
    // 2) 句柄
    if (g_handle) {
        p = dlsym(g_handle, name);
        if (p) {
            g_stage = EG_SYM_STAGE_HANDLE;
            eg_ilog("sym %s @ %p via handle (%lldms)", name, p, eg_now_ms() - t0);
            return p;
        }
    }
    // 3) maps 路径句柄
    if (!g_handle) {
        void *h = eg_dlopen_from_maps("libil2cpp.so");
        if (h) {
            g_handle = h;
            p = dlsym(h, name);
            if (p) {
                g_stage = EG_SYM_STAGE_MAPS;
                eg_ilog("sym %s @ %p via maps-path (%lldms)", name, p, eg_now_ms() - t0);
                return p;
            }
        }
    }
    // 4) 读 .dynsym（纯内存解析，maps 已快照）
    if (!g_lib_base) g_lib_base = eg_lib_base("libil2cpp.so");
    if (g_lib_base) {
        p = dynsym_lookup(g_lib_base, name);
        if (p) {
            g_stage = EG_SYM_STAGE_DYNSYM;
            eg_ilog("sym %s @ %p via elf-dynsym (%lldms)", name, p, eg_now_ms() - t0);
            return p;
        }
    }
    g_stage = EG_SYM_STAGE_FAILED;
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

// 一次 /proc/self/maps 读了多少段、多久：真机上「读 maps 太慢」这种事要能被一眼看出来。
void eg_maps_stat(int *ranges, long long *age_ms) {
    pthread_mutex_lock(&g_maps_lock);
    if (ranges) *ranges = g_maps_count;
    if (age_ms) *age_ms = g_maps_count ? eg_now_ms() - g_maps_at : -1;
    pthread_mutex_unlock(&g_maps_lock);
}
