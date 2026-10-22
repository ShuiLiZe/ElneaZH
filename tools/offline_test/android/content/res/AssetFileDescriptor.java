package android.content.res;

import java.io.File;
import java.io.IOException;

/** 极小 stub：OfflineGate 解包 .so 时只用到 getLength()/close()。 */
public class AssetFileDescriptor {
    private final long len;
    public AssetFileDescriptor(long len) { this.len = len; }
    public AssetFileDescriptor(File f, long len) { this.len = len; }
    public long getLength() { return len; }
    public void close() throws IOException {}
}
