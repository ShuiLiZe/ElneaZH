"""Make the libil2cpp.so byte patch (ELSP v1) used by the app.
Derived from the old ElneaAssistant so_patch.bin (base/so/contents_redirect.elsp), but the master-table path
points to the game's own private dir (no root, no /sdcard):  /data/data/jp.co.althi.elnea.en/files/zh/
  0x8f0b18  path string (48 zero bytes free before the stub)
  0x8f0b48  stub: adrp x0,path; add x0,x0,#0xb18; bl il2cpp string ctor; ret
  0x18aa994 (contents path getter) -> b stub
  0x1ab3588 (version changed -> delete & redownload tables?) -> return false
Format: b'ELSP', u32 1, u32 n, n * (u32 off, u32 len, orig, u32 len, new). Usage: so_patch_zh.py libil2cpp.so out.elsp"""
import struct, sys
PATH = b'/data/data/jp.co.althi.elnea.en/files/zh/\0'
assert len(PATH) <= 0x30
P = [(0x8f0b18, bytes(len(PATH)), PATH),
     (0x8f0b4b, b'\0', b'\x90'),
     (0x8f0b4d, bytes(11), bytes.fromhex('602c91e3f03894c0035fd6')),
     (0x18aa994, bytes.fromhex('ff4302d1'), bytes.fromhex('6d08c117')),
     (0x1ab3588, bytes.fromhex('fe0f1df8f65701a9'), bytes.fromhex('e0031f2ac0035fd6'))]
so = open(sys.argv[1], 'rb').read()
out = b'ELSP' + struct.pack('<II', 1, len(P))
for off, o, n in P:
    assert so[off:off+len(o)] == o, hex(off)
    out += struct.pack('<II', off, len(o)) + o + struct.pack('<I', len(n)) + n
open(sys.argv[2], 'wb').write(out)
print('so patch ok', len(out))
