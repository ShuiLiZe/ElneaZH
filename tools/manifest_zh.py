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
act.set(A + 'excludeFromRecents', 'true')
act.set(A + 'configChanges', '0x40003fff')
act.set(A + 'screenOrientation', '1')
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
open(sys.argv[2], 'wb').write(out)
print('manifest ok', len(out))
