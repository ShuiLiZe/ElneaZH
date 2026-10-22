package android.content.res;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 极小 stub。默认没有 assets（两个方法都抛 —— 正好验证「解包失败也不崩」）；
 * 用 {@link #ROOT} 指一个目录就能真的提供文件，本地测「解包 + 不可加载」那条路径。
 */
public class AssetManager {
    /** 非空时按这个目录当真 assets 根。 */
    public static File ROOT;

    public InputStream open(String path) throws IOException {
        if (ROOT == null) throw new IOException("no assets in test: " + path);
        File f = new File(ROOT, path);
        if (!f.isFile()) throw new IOException("asset missing: " + path);
        return new FileInputStream(f);
    }

    public AssetFileDescriptor openFd(String path) throws IOException {
        if (ROOT == null) throw new IOException("no assets in test: " + path);
        File f = new File(ROOT, path);
        if (!f.isFile()) throw new IOException("asset missing: " + path);
        return new AssetFileDescriptor(f, f.length());
    }

    public String[] list(String dir) {
        if (ROOT == null) return null;
        File[] fs = new File(ROOT, dir).listFiles();
        if (fs == null) return null;
        String[] n = new String[fs.length];
        for (int i = 0; i < fs.length; i++) n[i] = fs[i].getName();
        return n;
    }
}
