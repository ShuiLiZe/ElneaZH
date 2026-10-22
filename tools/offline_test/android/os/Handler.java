package android.os;

/** 极小 stub：post 直接在当前线程跑。 */
public class Handler {
    public Handler(Looper l) {}
    public boolean post(Runnable r) { r.run(); return true; }
}
