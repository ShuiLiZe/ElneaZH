package android.content.res;

import java.io.IOException;
import java.io.InputStream;

/** 极小 stub。测试环境里没有 assets，两个方法都抛 —— 正好验证「解包失败也不崩」。 */
public class AssetManager {
    public InputStream open(String path) throws IOException { throw new IOException("no assets in test: " + path); }
    public AssetFileDescriptor openFd(String path) throws IOException { throw new IOException("no assets in test: " + path); }
}
