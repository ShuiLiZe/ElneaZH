#!/usr/bin/env python3
"""Build an installable TEST apk that carries frida-gadget (no root needed on the phone).

Why a dedicated script: the app's own rebuild path (ApkRebuilder.java) knows nothing about
native gadget libs, and Android 6+ refuses to load a .so that is not page-aligned inside the
zip (it extracts the file and the dynamic linker mmaps it, so alignment matters).

Steps: (optional) apply the ELSP patch to libil2cpp.so -> write every entry STORED-free as
before -> zipalign -p 4 -> apksigner with app/keystore/elneazh.p12.

usage:
  python tools/make_gadget_apk.py base.apk out.apk \
      [--so-patch app/src/main/assets/elneazh/so.elsp] \
      [--manifest app/src/main/assets/elneazh/AndroidManifest.xml] \
      [--dex build/classes3.dex] \
      [--gadget-arm64 libfrida-gadget-arm64.so] [--gadget-arm libfrida-gadget-arm.so] \
      [--hook tools/gadget_hook.js] [--no-sign]
"""
import argparse, os, shutil, struct, subprocess, sys, tempfile, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KEYSTORE = os.path.join(ROOT, 'app', 'keystore', 'elneazh.p12')
PAGE = 16384          # keep in sync with ApkRebuilder.SO_ALIGN
CONFIG = b'{ "interaction": { "type": "script", "path": "libhook.js", "on_change": "reload" } }'


def read(path):
    with open(path, 'rb') as f:
        return f.read()


def apply_elsp(so, elsp):
    """Apply an ELSP v1 payload (same format as app/.../core/SoPatcher.java)."""
    assert elsp[:4] == b'ELSP', 'not an ELSP payload'
    n = struct.unpack_from('<I', elsp, 8)[0]
    out = bytearray(so)
    off = 12
    for i in range(n):
        # entry layout (see tools/so_patch_zh.py): u32 file_off, u32 len, orig[len], u32 len_new, new[len_new]
        fo, ln = struct.unpack_from('<II', elsp, off)
        off += 8
        old = elsp[off:off + ln]
        off += ln
        newlen = struct.unpack_from('<I', elsp, off)[0]
        off += 4
        new = elsp[off:off + newlen]
        off += newlen
        cur = bytes(out[fo:fo + ln])
        if cur != old:
            # the zero-filled slot case (e.g. the master-table path string) has a shorter payload than
            # the original span; that is fine as long as the slot was empty
            assert ln >= newlen and cur[newlen:] == b'\0' * (ln - newlen), \
                'so patch %d mismatch at %#x (have %s want %s)' % (i, fo, cur[:8].hex(), old[:8].hex())
        out[fo:fo + len(new)] = new
    return bytes(out)


def sdk_build_tools():
    for base in (os.environ.get('ANDROID_SDK_ROOT'), os.environ.get('ANDROID_HOME'),
                 os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk')):
        bt = os.path.join(base or '', 'build-tools')
        if os.path.isdir(bt):
            for v in sorted(os.listdir(bt), reverse=True):
                if os.path.isfile(os.path.join(bt, v, 'zipalign.exe' if os.name == 'nt' else 'zipalign')):
                    return os.path.join(bt, v)
    return None


class Writer:
    """Writes zip entries at page boundaries, STORED for .so so they stay mmap-able."""

    def __init__(self, path):
        self.f = open(path, 'wb')
        self.cd = []

    def add(self, name, data, time_=(2024, 1, 1, 0, 0, 0), method=zipfile.ZIP_DEFLATED):
        if method == zipfile.ZIP_STORED:
            pad = (-(self.f.tell() + 30 + len(name.encode()))) % PAGE
            body = data + b'\0' * pad
        else:
            pad = 0
            body = _deflate(data)
        off = self.f.tell()
        crc = _crc(data)
        n = name.encode()
        extra = struct.pack('<HH', 0xD935, 2 + 8) + struct.pack('<HQI', PAGE, len(data), 0) if method == zipfile.ZIP_STORED else b''
        if extra:
            # Android uses this extra field (0xD935 = "alignment") for uncompressed entries
            pad = (-(off + 30 + len(n) + len(extra))) % PAGE
            extra = struct.pack('<HH', 0xD935, 2 + 8) + struct.pack('<HQI', PAGE, len(data), pad)
        flags = 0
        self.f.write(struct.pack('<IHHHHHIIIHH', 0x04034B50, 20, flags, method, time_[3], time_[4], crc,
                                 len(body), len(data), len(n), len(extra)))
        self.f.write(n)
        self.f.write(extra)
        self.f.write(body)
        self.cd.append((name, method, crc, len(body), len(data), off, time_))

    def close(self):
        start = self.f.tell()
        for name, method, crc, csize, usize, off, t in self.cd:
            n = name.encode()
            extra = struct.pack('<HH', 0xD935, 2 + 8) + struct.pack('<HQI', PAGE, usize, 0) if method == zipfile.ZIP_STORED else b''
            self.f.write(struct.pack('<IHHHHHHIIIHHHHHII', 0x02014B50, 20, 20, 0, method, t[3], t[4], crc,
                                     csize, usize, len(n), len(extra), 0, 0, 0, 0o644 << 16, off))
            self.f.write(n)
            self.f.write(extra)
        end = self.f.tell()
        self.f.write(struct.pack('<IHHHHIIH', 0x06054B50, 0, 0, len(self.cd), len(self.cd),
                                 end - start, start, 0))
        self.f.close()


def _crc(data):
    import zlib
    return zlib.crc32(data) & 0xffffffff


def _deflate(data):
    import zlib
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    return c.compress(data) + c.flush()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('base')
    ap.add_argument('out')
    ap.add_argument('--so-patch')
    ap.add_argument('--manifest')
    ap.add_argument('--dex')
    ap.add_argument('--gadget-arm64')
    ap.add_argument('--gadget-arm')
    ap.add_argument('--hook', default=os.path.join(ROOT, 'tools', 'gadget_hook.js'))
    ap.add_argument('--no-sign', action='store_true')
    a = ap.parse_args()

    elsp = read(a.so_patch) if a.so_patch else None
    axml = read(a.manifest) if a.manifest else None
    dex = read(a.dex) if a.dex else None
    hook = read(a.hook) if a.hook and os.path.isfile(a.hook) else None
    gadgets = {'arm64-v8a': a.gadget_arm64, 'armeabi-v7a': a.gadget_arm}

    tmpdir = tempfile.mkdtemp(prefix='gadgetapk')
    unsigned = os.path.join(tmpdir, 'u.apk')
    zin = zipfile.ZipFile(a.base)
    w = Writer(unsigned)
    so_done = 0
    for info in zin.infolist():
        name = info.filename
        data = zin.read(name)
        if elsp and name in ('lib/arm64-v8a/libil2cpp.so', 'lib/armeabi-v7a/libil2cpp.so'):
            data = apply_elsp(data, elsp)
            so_done += 1
        elif axml and name == 'AndroidManifest.xml':
            data = axml
        ext = os.path.splitext(name)[1].lower()
        store = ext in ('.so', '.arsc') or name.startswith('lib/')
        w.add(name, data, info.date_time, zipfile.ZIP_STORED if store else zipfile.ZIP_DEFLATED)
    zin.close()

    added = 0
    if dex:
        w.add('classes3.dex', dex)
        added += 1
    if hook:
        w.add('assets/elneazh/libhook.js', hook)
        added += 1
    for abi, path in gadgets.items():
        if not path or not os.path.isfile(path):
            continue
        w.add('lib/%s/libfrida-gadget.so' % abi, read(path), method=zipfile.ZIP_STORED)
        w.add('lib/%s/libgadget.config.so' % abi, CONFIG)
        added += 2
    w.close()

    final = unsigned
    if not a.no_sign:
        bt = sdk_build_tools()
        signer = os.path.join(bt, 'apksigner.bat' if os.name == 'nt' else 'apksigner') if bt else None
        if not (signer and os.path.isfile(signer) and os.path.isfile(KEYSTORE)):
            sys.exit('apksigner or keystore missing')
        signed = os.path.join(tmpdir, 's.apk')
        r = subprocess.run([signer, 'sign', '--ks', KEYSTORE, '--ks-type', 'pkcs12',
                            '--ks-pass', 'pass:elneazh', '--ks-key-alias', 'elneazh',
                            '--key-pass', 'pass:elneazh', '--out', signed, unsigned],
                           capture_output=True)
        if r.returncode != 0:
            sys.exit('apksigner failed: ' + r.stderr.decode('utf-8', 'replace')[:600])
        final = signed
    shutil.copyfile(final, a.out)
    print('ok %s (%.1f MB) so_patched=%d dex=%s gadget=%d' % (
        a.out, os.path.getsize(a.out) / 1e6, so_done, 'yes' if dex else 'no', added))
    shutil.rmtree(tmpdir, ignore_errors=True)


if __name__ == '__main__':
    main()
