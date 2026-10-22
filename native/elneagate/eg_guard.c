// ElneaZH · 崩溃保护（native 侧）：报告当前是否允许加载，以及这一轮跑的耗时上限。
//
// 真机事故：单机开/关**都**黑屏，几十秒后进程退出；没有 tombstone、没有 AndroidRuntime 异常、
// 闸门日志一条都没有 —— 典型的「主线程被卡住 → ANR / Unity 看门狗杀进程」。
// 这种情况下用户只看到黑屏，没有任何自愈手段。所以加一层启动保护，规则由 Java 侧执行：
//
//   files/elneazh_native_boot  启动时写（含 pid / 时间 / 包名），进主界面或 60s 后清掉；
//   若上一轮**连续 2 次**没清掉，下一次启动 Java 侧直接「不加载 native」，
//   selfcheck 写明 `native disabled by crash guard`，拼音闸门与单机 native 全跳过，Java 兜底照旧。
//
// native 这边只回答两个问题：这一轮允许装吗（Java 算好以后 push 进来）、以及最多等多久。
#include <jni.h>

#include <stddef.h>

#include "elneagate_jni.h"
#include "eg_log.h"

static volatile int g_allowed = 1;        // 1 = 允许加载（Java 侧 crash guard 的结论）
static volatile int g_window_ms = 30000;  // 后台安装窗口上限（毫秒），Java 侧可调

// 窗口夹到 [3s, 120s]：太小没意义，太大又回到「等太久」。
static int interval_clamp(int ms) {
    if (ms < 3000) return 3000;
    if (ms > 120000) return 120000;
    return ms;
}

// Java 侧灌入：allowed = 0 表示这一轮因 crash guard 跳过 native。
JNIEXPORT jint JNICALL
Java_com_shuilize_elneazh_inj_NativeLoad_nativeGuard(JNIEnv *env, jclass cls, jint allowed,
                                                     jint window_ms) {
    (void) env;
    (void) cls;
    if (window_ms > 0) g_window_ms = interval_clamp(window_ms);
    g_allowed = allowed ? 1 : 0;
    EG_I("guard: allowed=%d window=%dms", g_allowed, g_window_ms);
    return g_allowed;
}

JNIEXPORT jint JNICALL
Java_com_shuilize_elneazh_inj_NativeLoad_nativeAllowed(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return g_allowed;
}

JNIEXPORT jint JNICALL
Java_com_shuilize_elneazh_inj_NativeLoad_nativeWindow(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return g_window_ms;
}
