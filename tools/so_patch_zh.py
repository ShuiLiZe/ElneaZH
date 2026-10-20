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
  0x1c3fdb0 (VA 0x1c43db0, dead tail of TitlePanel.ContactButtonClick after the 0x34-byte support stub, func ends 0x1c43e1c)
            -> king_stub: first king of a NEW world is 「杨广」, male, Asia(yellow) skin; see king_stub() below.
  0x193e590 (VA 0x1942590) KingData.CreateNewKing: `bl GameSystem.CreateAdult` -> `bl king_stub`.
  0x19a08d4/0x19a08d8 (VA 0x19a48d4/0x19a48d8) HumanData.get_limitFriendCount, player branch:
            Range(52, 102, 52 + FriendListSlot items) -> Range(888, 888, ...) = player friend limit 888 (local, not premium).
            NPC branch (32) untouched. Friends are a List grown by addFriend, no fixed buffer.
  0x19c53c8 (VA 0x19c93c8) HumanData.SelectTalkFriend: `add w9,w8,#30` (player bonus) -> `bl king_visit_stub`
            (VA 0x1c05b18, dead tail of InfoMenuPanel.ContactButtonClick after its 0x34-byte support stub, 0x3c bytes).
            When the thinking NPC (x19) is the king and the friend is the player, the score becomes 100 (always picks
            the player, still once a day via IsAlreadyTalk); everyone else keeps 30/60(+30).
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
# ---- 初始国王「杨广」 ----
# KingData.CreateNewKing (VA 0x194249c): when there is no king and no heir it does
#   sex = SaveInfo.get_NextSex(); CreateAdult(NameData.Name(sex), NameData.Family, sex)  (age = AgeAdult = 6, random look)
#   then GenerationData/NewGeneration + setupNewKing (no marriage anywhere -> a fresh, single adult).
# KingData.ctor makes GenerationData(initGen=-1); so the FIRST king of a world is the one created while
# [[KingData+0x18]+0x18] (currentGeneration) < 0. Only that case is changed; later heirless kings stay random.
KING_CALL = 0x1942590          # bl CreateAdult inside CreateNewKing (x19 = KingData there)
KING_STUB = 0x1c43db0
CREATE_ADULT = 0x1a756d4       # GameSystem.CreateAdult(string pname, string fname, SexCode sex)
STRING_NEW = 0x172cedc         # il2cpp_string_new (dynsym)
HUMAN_SKIN = 0x19a5330         # HumanData.ChangeSkinColor(SkinColors) -> ProfileData.ChangeSkinColor
KING_NAME = '杨广'.encode('utf-8') + b'\0'
KING_SKIN = 1                  # AppearanceData.SkinColors: 0 Caucasian(白) / 1 Asia(黄)

def king_stub(va):
    bl = lambda pc, t: 0x94000000 | (((t - pc) >> 2) & 0x3FFFFFF)
    b = lambda pc, t: 0x14000000 | (((t - pc) >> 2) & 0x3FFFFFF)
    name_off = 0x58
    ins = [0xF9400E68,                          # 00 ldr x8,[x19,#0x18]   (KingData.kingData)
           0xB4000288,                          # 04 cbz x8, orig (+0x50)
           0xB9401908,                          # 08 ldr w8,[x8,#0x18]   (currentGeneration)
           0x36F80248,                          # 0c tbz w8,#31, orig (+0x48)
           0xA9BE7BFD, 0x910003FD,              # 10 stp x29,x30,[sp,#-32]!; mov x29,sp
           0xF9000BE1,                          # 18 str x1,[sp,#16]  (family name)
           0x10000000 | (((name_off - 0x1c) >> 2) << 5),  # 1c adr x0,name
           bl(va + 0x20, STRING_NEW),           # 20 x0 = "杨广"
           0xF9400BE1,                          # 24 ldr x1,[sp,#16]
           0x52800042,                          # 28 mov w2,#2 (Male)
           0xAA1F03E3,                          # 2c mov x3,xzr
           bl(va + 0x30, CREATE_ADULT),         # 30
           0xF9000BE0,                          # 34 str x0,[sp,#16]
           0xB40000A0,                          # 38 cbz x0, done (+0x14)
           0x52800001 | (KING_SKIN << 5),       # 3c mov w1,#skin
           0xAA1F03E2,                          # 40 mov x2,xzr
           bl(va + 0x44, HUMAN_SKIN),           # 44
           0xF9400BE0,                          # 48 done: ldr x0,[sp,#16]
           0xA8C27BFD,                          # 4c ldp x29,x30,[sp],#32
           0xD65F03C0,                          # 50 ret
           b(va + 0x54, CREATE_ADULT)]          # 54 orig: tail-call CreateAdult unchanged
    code = struct.pack('<%dI' % len(ins), *ins)
    assert len(code) == name_off
    code += KING_NAME
    return code + bytes((-len(code)) % 8)

so = open(sys.argv[1], 'rb').read()
for va, orig in STUBS:
    o = so[va - 0x4000: va - 0x4000 + 0x34]
    if orig: assert o.hex() == orig, hex(va)
    P.append((va - 0x4000, o, support_stub(va)))
# king: stub in the dead tail of TitlePanel.ContactButtonClick (bytes there are asserted below), then redirect the call
ks = king_stub(KING_STUB)
assert KING_STUB >= 0x1c43d74 + 0x34 and KING_STUB + len(ks) <= 0x1c43e1c
P.append((KING_STUB - 0x4000, so[KING_STUB - 0x4000: KING_STUB - 0x4000 + len(ks)], ks))
kc = so[KING_CALL - 0x4000: KING_CALL - 0x4000 + 4]
assert struct.unpack('<I', kc)[0] == 0x94000000 | (((CREATE_ADULT - KING_CALL) >> 2) & 0x3FFFFFF), 'CreateNewKing call site moved'
P.append((KING_CALL - 0x4000, kc, struct.pack('<I', 0x94000000 | (((KING_STUB - KING_CALL) >> 2) & 0x3FFFFFF))))
# ---- 国王更常主动来找玩家（只对国王）----
KV_CALL, KV_STUB, IS_KING = 0x19c93c8, 0x1c05b18, 0x199f628
def kv_stub(va):
    bl = lambda pc, t: 0x94000000 | (((t - pc) >> 2) & 0x3FFFFFF)
    ins = [0xA9BE7BFD,                 # 00 stp x29,x30,[sp,#-32]!
           0xA90123E0,                 # 04 stp x0,x8,[sp,#16]
           0x360000A0,                 # 08 tbz w0,#0,+0x14 (-> 1c: not the player, w0 bit0 = 0)
           0xAA1303E0,                 # 0c mov x0,x19 (the NPC that is choosing)
           0xAA1F03E1,                 # 10 mov x1,xzr
           bl(va + 0x14, IS_KING),     # 14 bl HumanData.get_IsKing
           0x2A0003EA,                 # 18 mov w10,w0
           0xA94123E0,                 # 1c ldp x0,x8,[sp,#16]
           0xA8C27BFD,                 # 20 ldp x29,x30,[sp],#32
           0x11007909,                 # 24 add w9,w8,#30 (original)
           0x7200015F,                 # 28 tst w10,#1
           0x52800C8B,                 # 2c mov w11,#100
           0x1A891169,                 # 30 csel w9,w11,w9,ne
           0x7200001F,                 # 34 tst w0,#1  (flags the caller's csel expects)
           0xD65F03C0]                 # 38 ret
    return struct.pack('<%dI' % len(ins), *ins)
kv = kv_stub(KV_STUB)
assert len(kv) == 0x1c05b54 - KV_STUB
kvo = so[KV_STUB - 0x4000: KV_STUB - 0x4000 + len(kv)]
P.append((KV_STUB - 0x4000, kvo, kv))
kc = so[KV_CALL - 0x4000: KV_CALL - 0x4000 + 4]
assert kc.hex() == '09790011', 'SelectTalkFriend site moved ' + kc.hex()
P.append((KV_CALL - 0x4000, kc, struct.pack('<I', 0x94000000 | (((KV_STUB - KV_CALL) >> 2) & 0x3FFFFFF))))
# ---- 玩家交友上限 888（HumanData.get_limitFriendCount 玩家分支，本地常量，与付费无关）----
FRIEND_MIN, FRIEND_MAX, FRIEND_LIMIT = 0x19a48d4, 0x19a48d8, 888
for va, orig, reg in ((FRIEND_MIN, '80068052', 0), (FRIEND_MAX, 'c10c8052', 1)):   # mov w0,#52 / mov w1,#102
    o = so[va - 0x4000: va - 0x4000 + 4]
    assert o.hex() == orig, 'friend limit site moved %x' % va
    P.append((va - 0x4000, o, struct.pack('<I', 0x52800000 | (FRIEND_LIMIT << 5) | reg)))
# ---- 维护/账号门禁（_hark/maintenance-login.md 第 5 节）----
# 全是 4 字节等长原地替换：不写桩、不占洞、不碰上面任何一条。
#   P_LOGIN_NO_ACCOUNT_B0/B1  TitlePanel.accountCheck：isAccountInput 恒 0，永不 openAccountPanel
#   P_LOGIN_NO_RETRY          TitlePanel.onErrorLoginRequest1：登录失败 >9 次不再弹「输入账号」
#   P_UUID_NO_CACHE_0/1       UuidPlugin.GetUUID：不采信 PlayerPrefs["uuid"]，每次冷启动重造 althi_uniq
# P_UUID_NO_CACHE_* 会让每次冷启动都变成服务器端「新号」；只想进游戏、想留服务器存档的人
# 可以注释掉下面两行（只留前三行）再生成。
LOGIN_PATCHES = [
    ('P_LOGIN_NO_ACCOUNT_B0', 0x1C3E62C, '68A24439', '08008052'),   # ldrb w8,[x19,#0x128] -> mov w8,wzr
    ('P_LOGIN_NO_ACCOUNT_B1', 0x1C3E630, 'A8000034', '1F2003D5'),   # cbz w8,<open>        -> nop
    ('P_LOGIN_NO_RETRY',      0x1C3FFB8, '6D000054', '6D000014'),   # b.le -> b (onErrorLoginRequest1+0x8)
    ('P_UUID_NO_CACHE_0',     0x1C8B478, 'C0040036', '1F2003D5'),   # tbz w0,#0,0x1C8F510  -> nop
    ('P_UUID_NO_CACHE_1',     0x1C8B490, '80030036', '1F2003D5'),   # tbz w0,#0,0x1C8F500  -> nop
]
for _n, _off, _o, _w in LOGIN_PATCHES:
    _cur = so[_off:_off + 4]
    if _cur.hex().upper() != _o.upper():
        print('[warn] %s @0x%X 原字节不符（期望 %s 实际 %s），跳过' % (_n, _off, _o, _cur.hex()))
        continue
    P.append((_off, _cur, bytes.fromhex(_w)))
out = b'ELSP' + struct.pack('<II', 1, len(P))
for off, o, n in P:
    assert so[off:off+len(o)] == o, hex(off)
    out += struct.pack('<II', off, len(o)) + o + struct.pack('<I', len(n)) + n
open(sys.argv[2], 'wb').write(out)
print('so patch ok', len(out))
