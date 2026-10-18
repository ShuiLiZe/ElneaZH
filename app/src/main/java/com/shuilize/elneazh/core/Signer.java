package com.shuilize.elneazh.core;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collections;

/** v1 + v2 + v3 signing with apksig, using a PKCS#8 RSA key + X.509 certificate (DER). */
public final class Signer {
    private final PrivateKey key;
    private final X509Certificate cert;

    public Signer(InputStream pk8, InputStream x509) throws Exception {
        key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readAll(pk8)));
        cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(readAll(x509)));
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toByteArray();
    }

    public void sign(File in, File out) throws Exception {
        ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder(
                "ELNEAZH", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setLibraryPageAlignmentBytes(16384)
                .setCreatedBy("ElneaZH")
                .build()
                .sign();
    }
}
