// ElneaZH · 阶段日志：JNI_OnLoad 里干的每一件事都打一条 ElneaGate（I 级）
//
// 真机事故（单机开/关都黑屏、几十秒后进程退出、logcat 一条闸门日志都没有）需要一条能定位
// 「卡在哪」的线索。这一组日志的要求：
//
//   * 进入 / 退出成对（`enter` / `exit <ms>ms`），中间每一步都带耗时；
//   * 每条都带线程名 + tid（见 eg_log.h），明显是主线程还是后台线程；
//   * 阶段号单调递增，logcat 里最后一条 ElneaGate 的 #N 就是卡住的位置；
//   * JNI_OnLoad 里**只做 O(1) 的事**（登记指针、起后台线程），绝不 dlsym 大循环、绝不读
//     /proc/self/maps、绝不等 libil2cpp、绝不调 il2cpp API —— 这些全在后台线程里做。
#include "elneagate_jni.h"

#include <jni.h>

#include "eg_sym.h"

#include "eg_log.h"

#include <stdarg.h>
#include <string.h>
#include <sys/stat.h>

#define EG_FLOG_PATH "/data/data/jp.co.althi.elnea.en/files/elneazh_native.log"
#define EG_FLOG_CAP (512 * 1024)
static pthread_mutex_t g_flog_lock = PTHREAD_MUTEX_INITIALIZER;
static FILE *g_flog;
static long g_flog_size;

void eg_flog(int prio, const char *tag, const char *msg) {
    if (!msg) return;
    pthread_mutex_lock(&g_flog_lock);
    if (!g_flog) {
        // 每个进程第一次写时清空（只留这一轮）
        g_flog = fopen(EG_FLOG_PATH, "w");
        g_flog_size = 0;
    }
    if (g_flog && g_flog_size < EG_FLOG_CAP) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        struct tm tm;
        localtime_r(&ts.tv_sec, &tm);
        int n = fprintf(g_flog, "%02d:%02d:%02d.%03ld %c %s(%ld): %s\n", tm.tm_hour, tm.tm_min,
                        tm.tm_sec, ts.tv_nsec / 1000000L,
                        prio >= ANDROID_LOG_ERROR ? 'E' : prio >= ANDROID_LOG_WARN ? 'W' : 'I',
                        tag ? tag : "?", (long) syscall(SYS_gettid), msg);
        if (n > 0) g_flog_size += n;
        fflush(g_flog);
    }
    pthread_mutex_unlock(&g_flog_lock);
}

void eg_plog(int prio, const char *tag, const char *fmt, ...) {
    char msg[900];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    __android_log_print(prio, tag, "%s", msg);
    eg_flog(prio, tag, msg);
}

JavaVM *eg_vm;
volatile int eg_log_step;

JavaVM *eg_vm_get(void) { return eg_vm; }

// 主线程只做 O(1)：记下 JavaVM、起一个后台线程。
// 返回 JNI_VERSION_1_6 表示「加载成功」，之后 nativeInstall 系列才会被 Java 调进来。
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    long long t0 = eg_now_ms();
    eg_vm = vm;
    EG_I("JNI_OnLoad enter (size=%d B)", (int) sizeof(void *));
    // 这里不做任何可能阻塞的事：不 dlsym il2cpp、不读 /proc、不起线程、不建锁。
    // 名字表/单机钩子全部由 Java 侧在**后台线程**里主动调 nativeInstall 推进。
    EG_I("JNI_OnLoad exit in %lldms (no il2cpp api touched on this thread)",
         eg_now_ms() - t0);
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    EG_I("JNI_OnUnload");
}

// 自检 / 日志用：让 Java 知道现在 native 的阶段号与符号解析阶段。
JNIEXPORT jstring JNICALL Java_com_shuilize_elneazh_inj_NativeLoad_nativeBootReport(
        JNIEnv *env, jclass cls) {
    (void) cls;
    char buf[256];
    snprintf(buf, sizeof buf, "jvm=%p sym_stage=%s steps=%d",
             (void *) eg_vm, eg_sym_stage(), (int) eg_log_step);
    return (*env)->NewStringUTF(env, buf);
}
