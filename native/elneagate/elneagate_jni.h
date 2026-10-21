// ElneaZH · native 内部共享的全局（不对外）
#ifndef ELNEAGATE_JNI_H
#define ELNEAGATE_JNI_H

#include <jni.h>

// 进程里的 JavaVM（JNI_OnLoad 里记下；给需要调 Java 的后台逻辑用）。
extern JavaVM *eg_vm;
JavaVM *eg_vm_get(void);

// 阶段日志的单调计数器（eg_log.h 里每条 ElneaGate 日志都要读它）。
extern volatile int eg_log_step;

#endif  // ELNEAGATE_JNI_H
