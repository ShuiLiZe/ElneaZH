"""Add the injected ZhInit provider and SupportActivity (elneazh://support) to the game's binary AndroidManifest.xml. Usage: manifest_zh.py in.axml out.axml"""
import sys, pyaxml
from lxml import etree
A = '{http://schemas.android.com/apk/res/android}'
ax, _ = pyaxml.AXML.from_axml(open(sys.argv[1], 'rb').read())
x = ax.to_xml()
app = x.find('application')
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
assert b'zhinit' in _t and b'SupportActivity' in _t and b'elneazh' in _t
open(sys.argv[2], 'wb').write(out)
print('manifest ok', len(out))
