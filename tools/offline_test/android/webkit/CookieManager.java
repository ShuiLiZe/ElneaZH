import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 离线单测用最小桩：CookieManager 在纯 JDK 上没有实现，
 * 这里只记录调用次数，供 PlayerPrefsTest 断言「换身份时确实清了 cookie」。
 */
public class CookieManager {
    public static int removed = 0;
    public static int flushes = 0;
    public static boolean fakeHas = false;

    private static final CookieManager INSTANCE = new CookieManager();

    public static CookieManager getInstance() { return INSTANCE; }

    public void setAcceptCookie(boolean accept) {}

    public void removeAllCookies(ValueCallback<Boolean> cb) {
        removed++;
        fakeHas = false;
        if (cb != null) cb.onReceiveValue(Boolean.TRUE);
    }

    public void flush() { flushes++; }

    public boolean hasCookies() { return fakeHas; }

    public interface ValueCallback<T> { void onReceiveValue(T value); }

    /** 测试辅助：模拟「清不干净」的机型。 */
    public static void resetForTest() { removed = 0; flushes = 0; fakeHas = false; }
}
