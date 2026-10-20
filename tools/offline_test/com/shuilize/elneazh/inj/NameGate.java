package com.shuilize.elneazh.inj;

/**
 * 测试用 stub：真 {@link NameGate} 要 native 库，JDK 下跑不了。
 * 这里只留 {@link NameFix} 编译时用到的那一个方法，什么都不做。
 */
final class NameGate {
    private NameGate() {}
    static boolean load(android.content.Context ctx) { return false; }
    static synchronized void remember(android.content.Context ctx, String zh, String ascii) {}
    static String describe() { return "stub"; }
}
