# 原版 APK（Elnea Kingdom 2.10.7）

分 4 段存放（单文件不超过 GitHub 限制）。还原并校验：

```bash
cat base/apk/ElneaKingdom_2.10.7.apk.part* > ElneaKingdom_2.10.7.apk
sha256sum ElneaKingdom_2.10.7.apk   # 应为 5117bbaaa301ad2e4535280918173da9500cb2c96bab012ce6040bf1a2bcf90c
```

内含 lib/arm64-v8a/libil2cpp.so、assets/bin/Data/Managed/Metadata/global-metadata.dat、assets/bin/Data/data.unity3d。仅自用。
