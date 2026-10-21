package android.content;

import java.util.HashMap;
import java.util.Map;

/** 只为本地跑规则测试而写的极小 stub：够 Offline / NameFix 存取开关。 */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private static final java.io.File TMP = new java.io.File(System.getProperty("java.io.tmpdir"), "elneazh_test");
    private static final Map<String, SharedPreferences> PRES = new HashMap<String, SharedPreferences>();
    private static java.io.File DIR;
    private final java.util.Map<String, Object> extra = new HashMap<String, Object>();
    public SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences p = PRES.get(name);
        if (p == null) { p = new SharedPreferences(); PRES.put(name, p); }
        return p;
    }
    public Context getApplicationContext() { return this; }
    public java.io.File getFilesDir() { return DIR != null ? DIR : new java.io.File(TMP, "files"); }
    /** 测试里换一个 files 目录（模拟干净的一轮启动）。 */
    public void setFilesDir(java.io.File d) { DIR = d; }
    public java.io.File getExternalFilesDir(String type) { return new java.io.File(TMP, "ext"); }
    public String getPackageName() { return "com.althi.elnea"; }
    /** 测试里手动指定 assets 根（解包 .so 那条路径要用）。 */
    public void setAssetRoot(java.io.File root) { android.content.res.AssetManager.ROOT = root; }
    public android.content.res.AssetManager getAssets() { return new android.content.res.AssetManager(); }
}
