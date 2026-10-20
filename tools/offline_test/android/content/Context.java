package android.content;

import java.util.HashMap;
import java.util.Map;

/** 只为本地跑规则测试而写的极小 stub：够 Offline / NameFix 存取开关。 */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private static final Map<String, SharedPreferences> PRES = new HashMap<String, SharedPreferences>();
    private final java.util.Map<String, Object> extra = new HashMap<String, Object>();
    public SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences p = PRES.get(name);
        if (p == null) { p = new SharedPreferences(); PRES.put(name, p); }
        return p;
    }
    public Context getApplicationContext() { return this; }
    public android.content.res.AssetManager getAssets() { return new android.content.res.AssetManager(); }
}
