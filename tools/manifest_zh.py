"""Add the injected ZhInit provider and SupportActivity (elneazh://support) to the game's binary AndroidManifest.xml. Usage: manifest_zh.py in.axml out.axml"""
import sys, pyaxml
from lxml import etree
A = '{http://schemas.android.com/apk/res/android}'
ax, _ = pyaxml.AXML.from_axml(open(sys.argv[1], 'rb').read())
x = ax.to_xml()
app = x.find('application')
# 存档备份放根目录 /sdcard/艾尔尼亚存档备份 需要「所有文件访问」（用户在存档管理里手动授权；不授权就退回 Documents）
_have = {e.get(A + 'name') for e in x.findall('uses-permission')}
for perm in ('android.permission.MANAGE_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.READ_EXTERNAL_STORAGE'):
    if perm not in _have:
        u = etree.Element('uses-permission'); u.set(A + 'name', perm); app.addprevious(u)
p = etree.SubElement(app, 'provider')
p.set(A + 'name', 'com.shuilize.elneazh.inj.ZhInit')
p.set(A + 'exported', 'false')
p.set(A + 'authorities', 'jp.co.althi.elnea.en.zhinit')
p.set(A + 'initOrder', '1000')
# 「交流与支持」: title-screen Support → 交流群与支持 calls Application.OpenURL("elneazh://support") (so patch)
act = etree.SubElement(app, 'activity')
act.set(A + 'theme', '@1030010')  # android:style/Theme.Translucent.NoTitleBar (same as ProxyBillingActivity)
act.set(A + 'name', 'com.shuilize.elneazh.inj.SupportActivity')
act.set(A + 'exported', 'true')  # targetSdk 36: implicit intents only reach exported components
# 导入存档也要从文件选择器进来（ActionOpenDocument，不依赖 SupportActivity 的恢复入口）
act.set(A + 'noHistory', 'true')
act.set(A + 'excludeFromRecents', 'true')
act.set(A + 'configChanges', '0x40003fff')
act.set(A + 'screenOrientation', '1')
# 「导出存档 / 导出名字对照表」用系统分享面板发文件：游戏自带 androidx.core（classes.dex 里有
# androidx/core/content/FileProvider），直接复用它，不必再往 classes3.dex 里塞 implementation。
# 1.5MB 的 classes3.dex 里塞 FileProvider 会同时把 androidx.core 的依赖面拉进来，风险大于收益。
fp = etree.SubElement(app, 'provider')
fp.set(A + 'name', 'androidx.core.content.FileProvider')
fp.set(A + 'exported', 'false')
fp.set(A + 'authorities', 'jp.co.althi.elnea.en.zhfiles')
fp.set(A + 'grantUriPermissions', 'true')
md = etree.SubElement(fp, 'meta-data')
md.set(A + 'name', 'android.support.FILE_PROVIDER_PATHS')
# 原 APK 里现成的 xml 资源只有这三张（aapt2 dump resources 核实：7f100000 backup_rules、
# 7f100001 billingclient_phenotype、7f100002 image_share_filepaths），没有 file_paths，所以复用
# image_share_filepaths：<paths><files-path name="image_provider_images" path="image_provider/"/></paths>。
# 也就是说分享文件必须放在 filesDir/image_provider/ 下才能出 uri（见 Share.outDir）。
md.set(A + 'resource', '@7f100002')
app.set(A + 'requestLegacyExternalStorage', 'true')
f = etree.SubElement(act, 'intent-filter')
for tag, val in (('action', 'android.intent.action.VIEW'), ('category', 'android.intent.category.DEFAULT'),
                 ('category', 'android.intent.category.BROWSABLE')):
    e = etree.SubElement(f, tag); e.set(A + 'name', val)
e = etree.SubElement(f, 'data'); e.set(A + 'scheme', 'elneazh'); e.set(A + 'host', 'support')
ax2 = pyaxml.AXML(); ax2.from_xml(x); ax2.compute()
out = ax2.pack()
chk, _ = pyaxml.AXML.from_axml(out)
_t = etree.tostring(chk.to_xml())
assert b'MANAGE_EXTERNAL_STORAGE' in _t and b'zhinit' in _t and b'SupportActivity' in _t and b'elneazh' in _t
assert b'FileProvider' in _t and b'zhfiles' in _t and b'FILE_PROVIDER_PATHS' in _t, 'share provider missing'
assert b'requestLegacyExternalStorage' in _t, 'legacy storage flag missing'
open(sys.argv[2], 'wb').write(out)
print('manifest ok', len(out))
