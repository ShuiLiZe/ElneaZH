// ElneaZH · 单机模式 native 闸门（接口）
//
// 与名字闸门（eg_names / NameGate）同一个 .so，但挂的是完全不同的一批方法：
// 用 il2cpp 运行时 API 按**名字**解析出来，再 inline hook。
//
// 钩子族编号（Java 侧 OfflineGate.TARGET_* 必须一致）：
//   1 PANEL   纯副作用型打开入口：单机时不执行，入口不创建
//   2 HTTP    HttpSystem 发送方法：单机时返回 0（不发，走游戏自己的失败分支）
//   3 GATE    Add*Panel 工厂：单机时工厂照跑但造出来的面板立刻收掉（不返回 NULL）
//   4 BUTTON  按钮 / 菜单格点击回调：单机时不执行原回调，并把入口对象 SetActive(false)
//   5 HIDE    入口对象自身 SetActive / set_active / set_visible / 刷新：先跑原方法再隐藏
//   6 MASKS   AbstractPanel 遮罩：单机时不加遮罩，页面保持可用
#ifndef ELNEAZH_EG_OFFLINE_H
#define ELNEAZH_EG_OFFLINE_H

#ifdef __cplusplus
extern "C" {
#endif

#define EG_TARGET_PANEL  1
#define EG_TARGET_HTTP   2
#define EG_TARGET_GATE   3
#define EG_TARGET_BUTTON 4
#define EG_TARGET_HIDE   5
#define EG_TARGET_MASKS  6

#define EG_TARGET_MIN    1
#define EG_TARGET_MAX    6

// 统计槽数：已挂 / 入口不创建 / 请求拦截 / 隐藏或收面板 / 遮罩跳过 / 解析失败 / 回调异常
#define EG_STATS_SLOTS   7

// 挂一族钩子。names 是若干 "命名空间\t类名\t方法名" 行（'\n' 分隔，'#' 开头为注释）。
// 返回新挂上的数量；<0 表示整族失败：
//   -1 il2cpp API 还看不到（libil2cpp 未加载）
//   -4 当前 ABI 没有 hook 引擎（armeabi-v7a）
//   -5 target 编号非法
int eg_of_install(int target, const char *names);

// 还原所有 hook，返回还原数量
int eg_of_uninstall(void);

// 开关：'1' 开 / '0' 关。hook 回调每次现读，切换不用重进游戏
void eg_of_set_state(const char *state);

// 总闸（调试用）：0 时无论 state 是什么都不拦
void eg_of_set_enabled(int on);

// 统计：out[0]=已挂 out[1]=入口不创建 out[2]=请求拦截 out[3]=隐藏/收面板
//       out[4]=遮罩跳过 out[5]=解析失败 out[6]=回调异常
void eg_of_stats(int out[EG_STATS_SLOTS]);

void eg_of_reset_stats(void);

// 依赖的 il2cpp API 能 dlsym 到几个（期望 5）
int eg_of_api_probe(void);

// 只解析不调用：能不能按名字找到 ns::klass::method（ns 传 "*" 表示不限）
int eg_of_resolvable(const char *ns, const char *klass, const char *method);

// 只查不调用：这个类在不在运行时里（不区分命名空间）
int eg_of_class_present(const char *klass);

#ifdef __cplusplus
}
#endif

#endif  // ELNEAZH_EG_OFFLINE_H
