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
