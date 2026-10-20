// ElneaZH · 单机模式 native 闸门（il2cpp 按名解析 + inline hook）
//
// 由 elneagate.c 的 JNI 入口驱动（OfflineGate.java 调用）。两张钩子表：
//   1) 面板入口族 Add*Panel：单机时回调直接返回 NULL —— onAdd 不执行，面板不会建出来，
//      点主菜单里商店 / 邮箱 / 好友 / PVP / 排行 / 公告 / 活动 / 广告 / 内购那些入口没反应，
//      底层也不会去请求服务器；线程池任务（如 AddGemBuyListPanel 的 Param 回调）被挡在源头。
//   2) HttpSystem 的发送函数：单机时不发，直接按游戏自己的「通信失败」处理（回调 false）；
//      资源/扩展包下载（AssetBundleManager）不在这一层，照常放行。
//
// 安全阀：按名字解析不到就跳过 + 打日志，绝不 hook 错函数；开关是运行时现读（OfflineGate.isOff()）。
#ifndef EG_OFFLINE_H
#define EG_OFFLINE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

// 安装：解析 names 里的类/方法并挂第一个能解析的方法。返回 0 成功 / 负数为原因码。
//   names 每行一条：<namespace>\t<class>\t<method>，method 空表示「类的 .cctor」
int eg_of_install(int target, const char *names);

// 取消安装（把第一次安装的钩子摘掉）。返回被摘掉的数量。
int eg_of_uninstall(void);

// 统计：[0]=已挂数量 [1]=面板拦截次数 [2]=HTTP 拦截次数 [3]=解析尝试的老版本数
void eg_of_stats(int out[4]);

// 单个函数按名字调用（自检用）：返回 1 表示调用成功。
int eg_of_call(const char *ns, const char *klass, const char *method, int argc, void **argv);

// 运行时探测：libil2cpp.so 里能 dlsym 到几个我们依赖的 API。结果写成 "a,b,c" 计数用。
int eg_of_api_probe(void);

#ifdef __cplusplus
}
#endif

#endif  // EG_OFFLINE_H
