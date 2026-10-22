package com.shuilize.elneazh.inj;

/**
 * 测试用 stub：真 {@link NameGate} 要 native 库，JDK 下跑不了。
 *
 * 这里只留两个「够编译、够证明签名」的转发：
 *   * {@link NameFix} 编译时用到的 {@code load} / {@code remember} / {@code describe}；
 *   * {@link NativeGateTest} 要验证的「install 立刻返回」与「后台线程可等待」两个入口 ——
 *     它们的语义与真类一致（立刻返回 / 只给测试用），这样测试至少能守住签名与调用方式，
 *     真跑后台线程的那一份在真机上（见 HANDOFF 的验证清单）。
 */
final class NameGate {
    private static final long STARTED_AT = System.currentTimeMillis();
    private static volatile int result = Integer.MIN_VALUE;

    private NameGate() {}

    static boolean load(android.content.Context ctx) { return false; }

    static synchronized void remember(android.content.Context ctx, String zh, String ascii) {}

    static String describe() { return "stub"; }

    /**
     * 与真类同样的契约：**立刻返回**（不做任何等待），返回值只是「上次已知结果」。
     * stub 里模拟「后台线程 150ms 后给出 -100」。
     */
    static int install(final android.content.Context ctx, int tries) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                }
                result = -100;   // 测试环境没有 native 库
            }
        }, "ElneaGate-install-stub");
        t.setDaemon(true);
        t.start();
        return result;
    }

    /** 只给测试用：等后台线程结束。主线程绝不调它。 */
    static int awaitInstall(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && result == Integer.MIN_VALUE) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return result;
    }

    static long startedAt() { return STARTED_AT; }
}
