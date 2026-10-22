package android.os;

/** 只为本地跑规则测试而写的极小 stub：NativeGuard 的标记文件要写 pid。 */
public final class Process {
    private Process() {}
    public static int myPid() { return 4242; }
    public static int myTid() { return 4243; }
}
