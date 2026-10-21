package com.shuilize.elneazh.inj;

/**
 * SelfCheck 的编译桩（只给 CI 里那组纯 JDK 单测用）。
 *
 * <p>{@code OfflineGate} / {@code NativeLoad} 会回调 {@code SelfCheck.rewriteAsync}，javac 顺着
 * -sourcepath 解析就会把真的 SelfCheck 拉进来，它又 import 了 Share / Dialog / WindowManager……
 * android stub 撑不住。这里给一个空实现顶替，行为（单机判定、身份清理规则）一点不受影响。
 */
final class SelfCheck {

    static void run(android.content.Context ctx) {}

    static void rewriteAsync(android.content.Context ctx) {}

    private SelfCheck() {}
}
