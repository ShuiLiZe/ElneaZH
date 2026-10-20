"""Make the libil2cpp.so byte patch (ELSP v1) used by the app.
Derived from the old ElneaAssistant so_patch.bin (base/so/contents_redirect.elsp), but the master-table path
points to the game's own private dir (no root, no /sdcard):  /data/data/jp.co.althi.elnea.en/files/zh/
  0x8f0b18  path string (48 zero bytes free before the stub)
  0x18aa994 (contents path getter, VA 0x18ae994) -> adrp x0,path; add x0,#0xb18; b il2cpp_string_new thunk (VA 0x172cedc)
  NOTE: the old ElneaAssistant patch put its stub at file 0x8f0b48, which lies in the read-only first LOAD
  segment (and its branch mixed file offsets with VAs: code segment VA = file offset + 0x4000) -> instant crash.
  0x1ab3588 (version changed -> delete & redownload tables?) -> return false
  0x1c3fd74 TitlePanel.ContactButtonClick (VA 0x1c43d74; title screen Support -> 「交流群与支持」, originally
            AddWebPanel(URLType.Contact)) -> Application.OpenURL("elneazh://support"):
            stp x29,x30,[sp,#-16]!; mov x29,sp; adr x0,str; bl il2cpp_string_new thunk (VA 0x172cedc);
            mov x1,xzr; bl UnityEngine.Application.OpenURL (VA 0x333c0e8); ldp x29,x30,[sp],#16; ret; str
            Unity sends ACTION_VIEW elneazh://support -> injected SupportActivity (manifest_zh.py) shows 交流与支持.
  0x1c01ae4 InfoMenuPanel.ContactButtonClick (VA 0x1c05ae4, in-game Support menu) -> same stub.
  (AccountSettingPanel.ContactButtonClick VA 0x1b8cf88, the forgot-password help, is left original.)
Format: b'ELSP', u32 1, u32 n, n * (u32 off, u32 len, orig, u32 len, new). Usage: so_patch_zh.py libil2cpp.so out.elsp"""
import struct, sys
PATH = b'/data/data/jp.co.althi.elnea.en/files/zh/\0'
assert len(PATH) <= 0x30
P = [(0x8f0b18, bytes(len(PATH)), PATH),
     (0x18aa994, bytes.fromhex('ff4302d1fe5f06a9f65707a9'), bytes.fromhex('0082ffd000602c9150f9f917')),
     (0x1ab3588, bytes.fromhex('fe0f1df8f65701a9'), bytes.fromhex('e0031f2ac0035fd6'))]



def support_stub(va):
    """ContactButtonClick body -> Application.OpenURL("elneazh://support"); 0x34 bytes (both targets are >= 0x70)."""
    bl = lambda pc, t: 0x94000000 | (((t - pc) >> 2) & 0x3FFFFFF)
    ins = [0xA9BF7BFD, 0x910003FD, 0x100000C0,          # stp x29,x30,[sp,#-16]!; mov x29,sp; adr x0,#0x18 (-> str)
           bl(va + 12, 0x172cedc), 0xAA1F03E1,          # bl il2cpp_string_new thunk; mov x1,xzr (MethodInfo)
           bl(va + 20, 0x333c0e8),                      # bl UnityEngine.Application.OpenURL(string)
           0xA8C17BFD, 0xD65F03C0]                      # ldp x29,x30,[sp],#16; ret
    code = struct.pack('<8I', *ins) + b'elneazh://support\0'
    return code + bytes(0x34 - len(code))

# file offset = VA - 0x4000 in the code segment
STUBS = [(0x1c43d74, 'fe57bea9f44f01a954f30090f5df0090f30300aa88067e39b5ae44f928010037e0df009000ac44f9e885eb97e0df00b000d040f9'),  # TitlePanel
         (0x1c05ae4, None)]  # InfoMenuPanel (in-game Support menu); AccountSettingPanel's contact (password help) stays original
so = open(sys.argv[1], 'rb').read()
for va, orig in STUBS:
    o = so[va - 0x4000: va - 0x4000 + 0x34]
    if orig: assert o.hex() == orig, hex(va)
    P.append((va - 0x4000, o, support_stub(va)))
out = b'ELSP' + struct.pack('<II', 1, len(P))
for off, o, n in P:
    assert so[off:off+len(o)] == o, hex(off)
    out += struct.pack('<II', off, len(o)) + o + struct.pack('<I', len(n)) + n
open(sys.argv[2], 'wb').write(out)
print('so patch ok', len(out))
