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
//   7 ENC     Cipher::EncryptRJ256 兜底闸：单机时请求体一律不出去（按固定偏移挂，不靠名字解析）
//   8 LPLAY   本地装玩：把 TitlePanel「需要输入账号」的状态按成「用本地存档」，
//             单机时不弹账号输入面板、不弹通信错误框
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
// 兜底族：Cipher::EncryptRJ256（每个 sns04 请求体的唯一出口）。不需要 il2cpp 按名解析，
// 用固定偏移挂；单机时直接返回 NULL，让调用方走自己的「加密/发送失败」分支。
#define EG_TARGET_ENC    7
// 本地装玩：按 TitlePanel 自己的本地游玩字段把状态按住（不伪造回包）。
#define EG_TARGET_LPLAY  8

#define EG_TARGET_MIN    1
#define EG_TARGET_MAX    8

// 统计槽数：已挂 / 入口不创建 / 请求拦截 / 隐藏或收面板 / 遮罩跳过 / 解析失败 / 回调异常 / 兜底闸
#define EG_STATS_SLOTS   8

// Cipher::EncryptRJ256(string) 在 libil2cpp.so 里的偏移（base 2.10.7，与名字闸门同一入口）。
#define EG_ENC_OFFSET    0x18ad7e4u
#define EG_ENC_PROLOGUE_LEN 8

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
//       out[4]=遮罩跳过 out[5]=解析失败 out[6]=回调异常 out[7]=兜底闸已挂
void eg_of_stats(int out[EG_STATS_SLOTS]);

void eg_of_reset_stats(void);

// 依赖的 il2cpp API 能 dlsym 到几个（期望 5）
int eg_of_api_probe(void);

// 兜底闸状态：1 = 已挂在 EncryptRJ256 上，0 = 还没挂，负数 = 挂失败原因
int eg_of_enc_state(void);

// 现在是不是「单机开」（hook 每次现读同一个值；界面/自检可据此断言）
int eg_of_is_off(void);

// 只解析不调用：能不能按名字找到 ns::klass::method（ns 传 "*" 表示不限）
int eg_of_resolvable(const char *ns, const char *klass, const char *method);

// 只查不调用：这个类在不在运行时里（不区分命名空间）
int eg_of_class_present(const char *klass);

// 自检：把每一个 hook 目标（已挂 / 未挂）写成多行台账，
// 每行含 名字 / 解析到的地址 / hook 方式 / 结果或失败原因。返回写入字符数。
int eg_of_hook_report(char *buf, int cap);

// 名字闸门（elneagate.c）是否已经把固定偏移上的 EncryptRJ256 挂上。
// 两个闸门共享同一个入口偏移：谁先挂谁负责，另一个不再动那段指令。
int eg_name_gate_hooked(void);
// 名字闸门实际挂的 EncryptRJ256 地址（按名字解析得来；没挂返回 NULL）
void *eg_name_gate_target(void);
// 按名字解析方法入口（剥 PAC）；运行时未就绪返回 NULL 且 *not_found=0
void *eg_of_method_ptr(const char *klass, const char *method, int argc, int *not_found);

// 方案 B 录制（eg_rec.c）：联网时录请求/回包明文 + 进程内方法表 dump
void eg_rec_install(void);
void eg_rec_req(void *plain_str, void *fp);

#ifdef __cplusplus
}
#endif

#endif  // ELNEAZH_EG_OFFLINE_H
