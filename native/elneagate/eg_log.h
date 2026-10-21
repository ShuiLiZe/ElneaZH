// ElneaZH · native 侧统一日志（ElneaGate）
//
// 为什么要它：真机事故「单机开/关都黑屏、几十秒后进程退出、logcat 三条闸门标签一条都没有」
// 说明主/Unity 线程被卡死（ANR / Unity 看门狗杀进程），而不是崩溃（没有 tombstone）。
// 要一眼看出卡在哪个阶段，日志必须同时带：
//
//   线程名(pthread_getname_np) + tid(gettid) + 阶段号(#N) + 与上一条的间隔 ms
//
// 阶段号是单调递增的：logcat 里最后一条 ElneaGate 的 #N 就是「卡在哪」的答案。
// 全部是 ANDROID_LOG_INFO 级（W 级只给真正的失败），方便 `adb logcat -s ElneaGate`。
#ifndef EG_LOG_H
#define EG_LOG_H

#include <android/log.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>
#include <sys/syscall.h>

// minSdk 24：pthread_getname_np 是 API 26 才有的公开函数。低版本直接用 tid 替代，
// 反正 logcat 里 `threadtime` 格式自带线程名。
#if defined(__ANDROID_API__) && __ANDROID_API__ < 26
#define EG_NO_THREAD_NAME 1
#endif

#define EG_LOG_TAG "ElneaGate"

// 单调时钟毫秒（不受系统时间调整影响）
static inline long long eg_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long) ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

// 当前线程名 + tid，写成 "name(tid)"；API < 26 没有公开的取线程名接口，退化成 "tid(N)"。
static inline const char *eg_thread_desc(char *buf, size_t cap) {
    long tid = (long) syscall(SYS_gettid);
#if defined(EG_NO_THREAD_NAME)
    snprintf(buf, cap, "tid(%ld)", tid);
#else
    char name[32];
    name[0] = 0;
    if (pthread_getname_np(pthread_self(), name, sizeof name) != 0 || !name[0]) {
        snprintf(buf, cap, "tid(%ld)", tid);
    } else {
        snprintf(buf, cap, "%s(%ld)", name, tid);
    }
#endif
    return buf;
}

// 单调递增的阶段号：跨线程共用，每条日志都带，最后一条就是卡住的位置。
// 定义在 eg_boot.c；为 0 表示 native 还没被加载过（`.so` 加载失败时 logcat 里连 #0 都没有）。
extern volatile int eg_log_step;

// 打印一条阶段日志：`#12 [ElneaOffline-install(31234) t=12345ms] install: ...`
// 返回自增后的阶段号。
__attribute__((format(printf, 1, 2)))
static inline int eg_ilog(const char *fmt, ...) {
    char tb[48];
    eg_thread_desc(tb, sizeof tb);
    int step = __sync_add_and_fetch((int *) &eg_log_step, 1) - 1;
    long long now = eg_now_ms();
    char msg[768];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    // 阶段号、线程名/tid、单调毫秒，外加消息本身 —— 一行就够定位「卡在哪」。
    __android_log_print(ANDROID_LOG_INFO, EG_LOG_TAG, "#%d [%s t=%lldms] %s", step, tb, now, msg);
    return step;
}

// 打「别的线程」的日志（比如从后台线程报主线程状态），thread 传描述串。
__attribute__((format(printf, 2, 3)))
static inline int eg_ilog_t(const char *thread, const char *fmt, ...) {
    int step = __sync_add_and_fetch((int *) &eg_log_step, 1) - 1;
    long long now = eg_now_ms();
    char msg[768];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, EG_LOG_TAG, "#%d [%s t=%lldms] %s", step,
                        thread ? thread : "?", now, msg);
    return step;
}

#define EG_W(fmt, ...) do { \
    char _tb[48]; eg_thread_desc(_tb, sizeof _tb); \
    __android_log_print(ANDROID_LOG_WARN, EG_LOG_TAG, "[%s t=%lldms] " fmt, _tb, eg_now_ms(), ##__VA_ARGS__); \
} while (0)

#define EG_I(fmt, ...) eg_ilog(fmt, ##__VA_ARGS__)

#endif  // EG_LOG_H
