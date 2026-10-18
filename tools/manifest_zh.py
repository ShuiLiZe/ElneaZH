"""Add the injected ZhInit provider to the game's binary AndroidManifest.xml. Usage: manifest_zh.py in.axml out.axml"""
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
ax2 = pyaxml.AXML(); ax2.from_xml(x); ax2.compute()
out = ax2.pack()
chk, _ = pyaxml.AXML.from_axml(out)
assert b'zhinit' in etree.tostring(chk.to_xml())
open(sys.argv[2], 'wb').write(out)
print('manifest ok', len(out))
