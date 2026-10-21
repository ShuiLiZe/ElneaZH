package com.shuilize.elneazh.inj;

/**
 * SelfCheck 的编译桩（只给 CI 里那组纯 JDK 单测用）。
 *
 * <p>{@code OfflineGate} / {@code NativeLoad} 会回调 {@code SelfCheck.rewriteAsync}，javac 顺着
 * -sourcepath 解析就会把真的 SelfCheck 拉进来，它又 import 了 Share / Dialog / WindowManager……
 * android stub 撑不住。这里给一个空实现顶替，行为（单机判定、身份清理规则）一点不受影响。
 *
 * <p>{@link #render} 只给 {@code NativeGateTest} 用：桩里给出和真类同名的三段关键行，
 * 让「selfcheck 里必须写明 native disabled by crash guard」这条检查能在纯 JDK 下跑。
 */
final class SelfCheck {

    static void run(android.content.Context ctx) {}

    static void rewriteAsync(android.content.Context ctx) {}

    static String render(android.content.Context ctx) {
        StringBuilder r = new StringBuilder();
        r.append("native guard: ").append(NativeGuard.describe()).append('\n');
        if (!NativeGuard.allowed()) {
            r.append("native disabled by crash guard: ").append(NativeGuard.reason()).append('\n');
        }
        r.append("native load: ").append(NativeLoad.describe()).append('\n');
        r.append("offline native: stub\n");
        return r.toString();
    }

    private SelfCheck() {}
}
