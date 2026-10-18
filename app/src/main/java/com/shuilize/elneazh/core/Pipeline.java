package com.shuilize.elneazh.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole localization job, independent of Android so it can be tested on a desktop JVM.
 * Input: base APK (+ optional split APKs). Output: signed APK(s) in outDir.
 */
public final class Pipeline {
    public static final String DATA_ENTRY = "assets/bin/Data/data.unity3d";
    public static final String META_ENTRY = "assets/bin/Data/Managed/Metadata/global-metadata.dat";
    public static final String BASE_SHA256 = "dc1796ffac62ea29796772f320c4218a922216aa417c4689abaeb6c0e917a155";

    public interface Listener {
        /** step: 0 verify, 1 patch, 2 rebuild, 3 sign; fraction 0..1 within step */
        void onStep(int step, float fraction, String message);
    }

    public static final class FriendlyException extends Exception {
        public FriendlyException(String msg) { super(msg); }
        public FriendlyException(String msg, Throwable cause) { super(msg, cause); }
    }

    private final File workDir;
    private final Signer signer;

    public Pipeline(File workDir, Signer signer) {
        this.workDir = workDir;
        this.signer = signer;
    }

    /** Index of the APK (in apks) that contains data.unity3d, or -1. */
    public static int findDataApk(List<File> apks) {
        for (int i = 0; i < apks.size(); i++) if (ApkRebuilder.hasEntry(apks.get(i), DATA_ENTRY)) return i;
        return -1;
    }

    /**
     * @param apks         base APK first, then splits (may be just one)
     * @param patch        bsdiff (BSDIFF40) patch file
     * @param expectedNewSha optional SHA-256 of the patched data.unity3d (null to skip)
     * @return signed output APKs, same order as input
     */
    public List<File> run(List<File> apks, File patch, String expectedNewSha, Listener l) throws Exception {
        return run(apks, patch, expectedNewSha, null, null, l);
    }

    /**
     * @param metaPatch       optional bsdiff patch for global-metadata.dat (hardcoded strings / name whitelist), or null
     * @param expectedMetaSha optional SHA-256 of the patched metadata
     */
    public List<File> run(List<File> apks, File patch, String expectedNewSha, File metaPatch, String expectedMetaSha, Listener l) throws Exception {
        if (!workDir.isDirectory() && !workDir.mkdirs()) throw new FriendlyException("无法创建缓存目录");
        for (File f : apks) if (!f.canRead()) throw new FriendlyException("无法读取游戏安装包：" + f.getPath());

        int di = findDataApk(apks);
        if (di < 0) throw new FriendlyException("在安装包中找不到 " + DATA_ENTRY + "，这可能不是 Elnea Kingdom。");
        File dataApk = apks.get(di);

        // 0. verify original data
        l.onStep(0, 0f, "正在校验原版资源…");
        String sha;
        try {
            sha = ApkRebuilder.sha256OfEntry(dataApk, DATA_ENTRY, (d, t) -> l.onStep(0, t > 0 ? (float) d / t : 0f, "正在校验原版资源…"));
        } catch (IOException e) {
            throw new FriendlyException("读取安装包失败：" + e.getMessage(), e);
        }
        if (!BASE_SHA256.equalsIgnoreCase(sha)) {
            throw new FriendlyException("游戏版本不匹配：需要 Elnea Kingdom 2.10.7 英文原版（未修改）。\n如果已经汉化过，请先卸载并重新安装商店原版。");
        }

        // 1. extract + patch
        File orig = new File(workDir, "orig.unity3d");
        File patched = new File(workDir, "zh.unity3d");
        l.onStep(1, 0f, "正在提取原版资源…");
        ApkRebuilder.extract(dataApk, DATA_ENTRY, orig);
        l.onStep(1, 0.05f, "正在应用汉化补丁…");
        try {
            BsPatch.apply(orig, patch, patched, (d, t) -> l.onStep(1, 0.05f + 0.95f * d / Math.max(1, t), "正在应用汉化补丁…"));
        } catch (IOException e) {
            throw new FriendlyException("应用补丁失败：" + e.getMessage(), e);
        } finally {
            orig.delete();
        }
        if (expectedNewSha != null && !expectedNewSha.isEmpty()) {
            l.onStep(1, 1f, "正在校验汉化结果…");
            String got = ApkRebuilder.sha256OfFile(patched);
            if (!expectedNewSha.equalsIgnoreCase(got)) throw new FriendlyException("补丁结果校验失败，汉化文件可能已损坏。请重新下载汉化器。");
        }

        // 1b. metadata (optional)
        int mi = -1;
        File metaPatched = null;
        if (metaPatch != null && metaPatch.length() > 0) {
            for (int i = 0; i < apks.size(); i++) if (ApkRebuilder.hasEntry(apks.get(i), META_ENTRY)) { mi = i; break; }
            if (mi >= 0) {
                File metaOrig = new File(workDir, "orig.metadata");
                metaPatched = new File(workDir, "zh.metadata");
                l.onStep(1, 1f, "正在汉化程序文本…");
                ApkRebuilder.extract(apks.get(mi), META_ENTRY, metaOrig);
                try {
                    BsPatch.apply(metaOrig, metaPatch, metaPatched, null);
                } catch (IOException e) {
                    throw new FriendlyException("应用程序文本补丁失败：" + e.getMessage(), e);
                } finally {
                    metaOrig.delete();
                }
                if (expectedMetaSha != null && !expectedMetaSha.isEmpty()
                        && !expectedMetaSha.equalsIgnoreCase(ApkRebuilder.sha256OfFile(metaPatched)))
                    throw new FriendlyException("程序文本补丁校验失败：游戏版本可能不是 2.10.7 原版。");
            }
        }

        // 2. rebuild + 3. sign, per APK
        List<File> out = new ArrayList<>();
        try {
            for (int i = 0; i < apks.size(); i++) {
                final int idx = i;
                File unsigned = new File(workDir, "unsigned_" + i + ".apk");
                File signed = new File(workDir, i == 0 ? "ElneaKingdom_zh.apk" : "ElneaKingdom_zh_split" + i + ".apk");
                l.onStep(2, 0f, "正在重新打包…");
                java.util.Map<String, File> rep = new java.util.HashMap<>();
                if (i == di) rep.put(DATA_ENTRY, patched);
                if (i == mi && metaPatched != null) rep.put(META_ENTRY, metaPatched);
                ApkRebuilder.rebuild(apks.get(i), rep, unsigned, workDir,
                        (d, t) -> l.onStep(2, ((float) idx + (float) d / Math.max(1, t)) / apks.size(), "正在重新打包…"));
                l.onStep(3, (float) idx / apks.size(), "正在签名…");
                try {
                    signer.sign(unsigned, signed);
                } catch (Exception e) {
                    throw new FriendlyException("签名失败：" + e.getMessage(), e);
                } finally {
                    unsigned.delete();
                }
                out.add(signed);
            }
        } finally {
            patched.delete();
            if (metaPatched != null) metaPatched.delete();
        }
        l.onStep(3, 1f, "完成");
        return out;
    }
}
