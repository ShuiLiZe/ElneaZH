/* ElneaZH frida-gadget probe  (TEST BUILD ONLY, never shipped on main).
 *
 * Loaded by libfrida-gadget.so on first dlopen of the game. Writes to
 *   /sdcard/Documents/艾尔尼亚存档备份/elneazh_probe.txt   (user can read it from a file manager)
 *   /sdcard/Android/data/<pkg>/files/elneazh_probe.txt     (fallback, no permission needed)
 *   /data/data/<pkg>/files/elneazh_probe.txt               (fallback, adb/root)
 *
 * What it collects
 *   1. every il2cpp_string_new / il2cpp_string_new_utf16 whose text looks like an API call
 *      (acc=, *.php, lang=) -> the plaintext BEFORE encryption, and its length
 *   2. System.Security.Cryptography method pointers (Key/IV setters, CreateEncryptor,
 *      ToBase64String ...) resolved through the il2cpp runtime API -> method file offsets
 *   3. the bytes handed to those crypto methods (key / IV / ciphertext) and a base64 copy
 *   4. anything that reaches write()/send() already looks like acc=... (i.e. no encryption
 *      at the Java/curl layer)
 * Every line is timestamped; failures are logged instead of thrown.
 */
'use strict';

var PKGS = ['jp.co.althi.elnea.en', 'jp.co.althi.elnea'];
var lines = [];
var seen = {};

function log(s) {
  var t = new Date().toISOString().substr(11, 12) + ' ' + s;
  lines.push(t);
  if (lines.length > 3000) lines.splice(0, 1000);
  try { console.log(t); } catch (e) {}
}

function flush() {
  var text = lines.join('\n') + '\n';
  var dirs = [];
  PKGS.forEach(function (p) {
    dirs.push('/sdcard/Documents/艾尔尼亚存档备份/elneazh_probe.txt');
    dirs.push('/storage/emulated/0/Android/data/' + p + '/files/elneazh_probe.txt');
    dirs.push('/data/data/' + p + '/files/elneazh_probe.txt');
  });
  for (var i = 0; i < dirs.length; i++) {
    try {
      var f = new File(dirs[i], 'w');
      f.write(text);
      f.flush();
      f.close();
      return;
    } catch (e) { }
  }
}

function note(tag, s) {
  if (!s || s.length < 4) return;
  var k = tag + '|' + s.slice(0, 200);
  if (seen[k]) return;
  seen[k] = 1;
  log(tag + ' ' + s);
  flush();
}

function ab2hex(b, max) {
  var s = '';
  var n = Math.min(b.length, max || 256);
  for (var i = 0; i < n; i++) s += ('0' + b[i].toString(16)).slice(-2);
  return s + (b.length > n ? '...(' + b.length + 'B)' : '');
}
var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function base64(b) {
  var s = '', i;
  for (i = 0; i + 2 < b.length; i += 3) {
    var v = (b[i] << 16) | (b[i + 1] << 8) | b[i + 2];
    s += B64[(v >> 18) & 63] + B64[(v >> 12) & 63] + B64[(v >> 6) & 63] + B64[v & 63];
  }
  if (i < b.length) {
    var v2 = (b[i] << 16) | (i + 1 < b.length ? b[i + 1] << 8 : 0);
    s += B64[(v2 >> 18) & 63] + B64[(v2 >> 12) & 63] + (i + 1 < b.length ? B64[(v2 >> 6) & 63] : '=') + '=';
  }
  return s;
}

/* ---------------- IL2CPP export helpers ---------------- */
function exp(name, ret, args) {
  try {
    var p = Module.getExportByName('libil2cpp.so', name);
    if (!p || p.isNull()) return null;
    return new NativeFunction(p, ret, args);
  } catch (e) { return null; }
}

var api = null;
function loadApi() {
  if (api) return api;
  api = {
    domainGet: exp('il2cpp_domain_get', 'pointer', []),
    asmGet: exp('il2cpp_domain_get_assemblies', 'pointer', ['pointer', 'pointer']),
    asmImage: exp('il2cpp_assembly_get_image', 'pointer', ['pointer']),
    imgName: exp('il2cpp_image_get_name', 'pointer', ['pointer']),
    imgClassCount: exp('il2cpp_image_get_class_count', 'size_t', ['pointer']),
    imgClass: exp('il2cpp_image_get_class', 'pointer', ['pointer', 'size_t']),
    clsName: exp('il2cpp_class_get_name', 'pointer', ['pointer']),
    clsNamespace: exp('il2cpp_class_get_namespace', 'pointer', ['pointer']),
    clsFromName: exp('il2cpp_class_from_name', 'pointer', ['pointer', 'pointer', 'pointer']),
    methFromName: exp('il2cpp_class_get_method_from_name', 'pointer', ['pointer', 'pointer', 'int']),
    methPointer: exp('il2cpp_method_get_pointer', 'pointer', ['pointer']),
    methName: exp('il2cpp_method_get_name', 'pointer', ['pointer']),
    fieldFromName: exp('il2cpp_class_get_field_from_name', 'pointer', ['pointer', 'pointer']),
    fieldGetValue: exp('il2cpp_field_get_value', 'void', ['pointer', 'pointer', 'pointer']),
    fieldStaticGetValue: exp('il2cpp_field_static_get_value', 'void', ['pointer', 'pointer']),
    stringNew: exp('il2cpp_string_new', 'pointer', ['pointer']),
    typeObject: exp('il2cpp_class_get_type', 'pointer', ['pointer']),
    objNew: exp('il2cpp_object_new', 'pointer', ['pointer']),
    clsMethods: exp('il2cpp_class_get_methods', 'pointer', ['pointer', 'pointer'])
  };
  return api;
}

function cstr(p) {
  try { return p.isNull() ? null : p.readUtf8String(); } catch (e) { return null; }
}

/* ---------------- 1. plaintext capture ---------------- */
var YES = /acc=|sns04|\.php|lang=en|user_rename|CheckCharacterName|Elnea|"key"|Rijndael|AES/i;

function hookStrings(IL) {
  var p8 = Module.getExportByName('libil2cpp.so', 'il2cpp_string_new');
  if (p8) {
    Interceptor.attach(p8, {
      onEnter: function (a) {
        try {
          var s = a[0].readUtf8String(6000);
          if (s && YES.test(s)) note('str8', JSON.stringify(s.slice(0, 5000)));
        } catch (e) { }
      }
    });
    log('hooked il2cpp_string_new @' + p8.sub(IL.base));
  }
  var p16 = Module.getExportByName('libil2cpp.so', 'il2cpp_string_new_utf16');
  if (p16) {
    Interceptor.attach(p16, {
      onEnter: function (a) {
        try {
          var s = a[0].readUtf16String(a[1].toInt32());
          if (s && YES.test(s)) note('str16', JSON.stringify(s.slice(0, 5000)));
        } catch (e) { }
      }
    });
    log('hooked il2cpp_string_new_utf16 @' + p16.sub(IL.base));
  }
}

/* ---------------- 2. managed crypto discovery ---------------- */
function dumpCrypto(IL) {
  var A = loadApi();
  if (!A.asmGet) { log('il2cpp runtime API not exported, skip reflection'); return; }
  var dom = A.domainGet();
  var n = Memory.alloc(4);
  var asms = A.asmGet(dom, n);
  var count = n.readU32();
  log('assemblies=' + count);
  var cn = 0;
  var mlist = ['set_Key', 'set_IV', 'get_Key', 'get_IV', 'CreateEncryptor', 'CreateDecryptor',
    'ToBase64String', 'FromBase64String'];
  for (var i = 0; i < count; i++) {
    var img = A.asmImage(asms.add(i * Process.pointerSize).readPointer());
    if (!img || img.isNull()) continue;
    var an = cstr(A.imgName(img)) || '';
    if (!/^(mscorlib|System|System\.Security|System\.Core|Assembly-CSharp|Elnea)/i.test(an)) continue;
    var cc = A.imgClassCount ? A.imgClassCount(img) : 0;
    for (var c = 0; c < cc; c++) {
      var k = A.imgClass(img, c);
      if (!k || k.isNull()) continue;
      var ns = cstr(A.clsNamespace(k)) || '';
      var kn = cstr(A.clsName(k)) || '';
      if (!/Crypto|Aes|Rijndael|Encrypt|Decrypt|Hash|Md5|Sha/i.test(kn + ' ' + ns)) continue;
      cn++;
      log('CLASS ' + an + ' ' + ns + '.' + kn);
      for (var j = 0; j < mlist.length; j++) {
        var m = A.methFromName(k, Memory.allocUtf8String(mlist[j]), -1);
        if (!m || m.isNull()) continue;
        var fp = (A.methPointer && A.methPointer(m)) || m.readPointer();
        if (!fp || fp.isNull()) continue;
        var va = fp.sub(IL.base);
        log('METHOD ' + ns + '.' + kn + '::' + mlist[j] + ' va=' + fp + ' fileoff=' + va + ' (' + va.toString(16) + ')');
        if (!fp.isNull() && /set_Key|set_IV|CreateEncryptor|CreateDecryptor|ToBase64String/.test(mlist[j])) {
          hookManaged(ns + '.' + kn + '::' + mlist[j], fp);
        }
      }
    }
  }
  log('crypto classes seen=' + cn);
  /* strings that name the game's own request builder, if any */
  var k2 = A.clsFromName;
  ['Elnea.NetworkManager', 'NetworkManager', 'Elnea.ApiManager', 'ApiManager', 'Elnea.SnsApi',
    'SnsApi', 'Elnea.RequestManager', 'RequestManager'].forEach(function (full) {
      var dot = full.lastIndexOf('.');
      var ns2 = dot > 0 ? full.slice(0, dot) : '';
      var nm = dot > 0 ? full.slice(dot + 1) : full;
      for (var q = 0; q < count; q++) {
        var im = A.asmImage(asms.add(q * Process.pointerSize).readPointer());
        if (!im || im.isNull()) continue;
        var kk = k2(im, Memory.allocUtf8String(ns2), Memory.allocUtf8String(nm));
        if (kk && !kk.isNull()) log('FOUND ' + full + ' @image ' + (cstr(A.imgName(im)) || '?'));
      }
    });
}

/* managed string layout: [klass][monitor][length:int32][utf16 chars...] */
function managedBytes(p, IL) {
  try {
    var len = p.add(Process.pointerSize * 2).readS32();
    if (len < 0 || len > 1 << 20) return null;
    var chars = p.add(Process.pointerSize * 3);
    var b = new Uint8Array(chars.readByteArray(len * 2));
    var txt = '';
    for (var i = 0; i + 1 < b.length; i += 2) txt += String.fromCharCode(b[i] | (b[i + 1] << 8));
    return { len: len, text: txt };
  } catch (e) { return null; }
}

function bytesFromArray(p, IL) {
  try {
    var len = p.add(Process.pointerSize * 2).readS32();
    if (len <= 0 || len > 1 << 20) return null;
    var data = p.add(Process.pointerSize * 3);
    return new Uint8Array(data.readByteArray(len));
  } catch (e) { return null; }
}

function hookManaged(tag, fp) {
  try {
    Interceptor.attach(fp, {
      onEnter: function (a) {
        try {
          var il = Process.getModuleByName('libil2cpp.so');
          var p1 = a[1], p2 = a[2];
          var b1 = bytesFromArray(p1, il);
          if (b1) {
            log(tag + ' arg1 byte[] len=' + b1.length + ' hex=' + ab2hex(b1, 64) + ' b64=' + base64(b1));
            flush();
          } else {
            var s1 = managedBytes(p1, il);
            if (s1 && s1.len && s1.len < 100000) note(tag + '-str1', JSON.stringify(s1.text.slice(0, 3000)));
          }
          if (p2 && !p2.isNull()) {
            var s2 = managedBytes(p2, il);
            if (s2 && s2.len && s2.len < 100000) note(tag + '-str2', JSON.stringify(s2.text.slice(0, 3000)));
          }
        } catch (e) { }
      }
    });
    log('attached ' + tag);
  } catch (e) { log('attach fail ' + tag + ': ' + e); }
}


/* ---------------- 4. v3: find the real cipher (game does NOT use .NET crypto) ---------------- */
function fptr(A, m) { var f = (A.methPointer && A.methPointer(m)) || m.readPointer(); return (f && !f.isNull()) ? f : null; }
function findCls(A, asms, count, ns, nm) {
  for (var q = 0; q < count; q++) {
    var im = A.asmImage(asms.add(q * Process.pointerSize).readPointer());
    if (!im || im.isNull()) continue;
    var k = A.clsFromName(im, Memory.allocUtf8String(ns), Memory.allocUtf8String(nm));
    if (k && !k.isNull()) return k;
  }
  return null;
}
function bt(ctx, IL) {
  try {
    return Thread.backtrace(ctx, Backtracer.FUZZY).slice(0, 8).map(function (p) {
      return (p.compare(IL.base) >= 0 && p.compare(IL.base.add(IL.size)) < 0) ? 'il+' + p.sub(IL.base).toString(16) : DebugSymbol.fromAddress(p).toString();
    }).join(' < ');
  } catch (e) { return 'bt?' + e; }
}
var PLAINN = 0;
var CJK = /[\u3040-\u30ff\u4e00-\u9fff]/;
function v3(IL) {
  var A = loadApi();
  var dom = A.domainGet(), n = Memory.alloc(4), asms = A.asmGet(dom, n), count = n.readU32();
  // a) Convert.ToBase64String(byte[]) : static, arg0 = ciphertext. backtrace -> the encrypt function
  var kc = findCls(A, asms, count, 'System', 'Convert');
  if (kc) ['ToBase64String'].forEach(function (mn) {
    var m = A.methFromName(kc, Memory.allocUtf8String(mn), 1); var f = m && !m.isNull() ? fptr(A, m) : null;
    if (!f) { log('v3 no Convert.' + mn); return; }
    Interceptor.attach(f, { onEnter: function (a) {
      var b = bytesFromArray(a[0], IL); if (!b || b.length < 32 || b.length % 16) return;
      log('B64IN len=' + b.length + ' hex=' + ab2hex(b, 32) + ' BT ' + bt(this.context, IL)); flush();
    } });
    log('v3 hooked Convert.' + mn + ' @il+' + f.sub(IL.base).toString(16));
  });
  // b) Encoding.GetBytes(string): plaintext right before encryption
  [['System.Text', 'Encoding'], ['System.Text', 'UTF8Encoding'], ['System.Text', 'ASCIIEncoding'], ['System.Text', 'UnicodeEncoding']].forEach(function (cn) {
    var k = findCls(A, asms, count, cn[0], cn[1]); if (!k) return;
    var m = A.methFromName(k, Memory.allocUtf8String('GetBytes'), 1); var f = m && !m.isNull() ? fptr(A, m) : null;
    if (!f) return;
    try { Interceptor.attach(f, { onEnter: function (a) {
      var s = managedBytes(a[1], IL); if (!s || !s.len || s.len < 8 || s.len > 200000) return;
      if (PLAINN > 400) return;
      var t = s.text; if (!(/[=&]/.test(t) || CJK.test(t) || t.charAt(0) == '{')) return;
      PLAINN++; log('PLAIN ' + cn[1] + ' len=' + s.len + ' cjk=' + CJK.test(t) + ' ' + JSON.stringify(t.slice(0, 4000)) + ' BT ' + bt(this.context, IL)); flush();
    } }); log('v3 hooked ' + cn[1] + '.GetBytes'); } catch (e) { log('v3 attach ' + cn[1] + ': ' + e); }
  });
  // c) list game-side methods whose name looks like a cipher (Assembly-CSharp etc.)
  if (!A.clsMethods) { log('v3 no class_get_methods'); return; }
  var hits = 0;
  for (var i = 0; i < count; i++) {
    var img = A.asmImage(asms.add(i * Process.pointerSize).readPointer());
    var an = cstr(A.imgName(img)) || '';
    if (/^(mscorlib|System|Mono|Unity|UnityEngine|Newtonsoft|DOTween|TextMeshPro)/i.test(an)) continue;
    var cc = A.imgClassCount(img);
    for (var c = 0; c < cc; c++) {
      var k = A.imgClass(img, c); if (!k || k.isNull()) continue;
      var it = Memory.alloc(Process.pointerSize); it.writePointer(ptr(0)); var m;
      while ((m = A.clsMethods(k, it)) && !m.isNull()) {
        var mn = cstr(A.methName(m)) || '';
        if (!/crypt|cipher|aes|rijn|xor|obfus|scramble|encode|decode|acc/i.test(mn)) continue;
        var f = fptr(A, m); if (!f) continue;
        var tag = an + ' ' + (cstr(A.clsNamespace(k)) || '') + '.' + (cstr(A.clsName(k)) || '') + '::' + mn;
        log('GAMEMETHOD ' + tag + ' va=il+' + f.sub(IL.base).toString(16));
        if (++hits <= 60) hookManaged(tag, f);
      }
    }
  }
  log('v3 game methods=' + hits);
}


/* ---------------- 5. v4: game Cipher (static, RJ256) : dump all args + return ---------------- */
function anyVal(p, IL) {
  if (!p || p.isNull()) return 'null';
  try { if (p.compare(ptr(0x100000)) < 0) return 'int:' + p.toInt32(); } catch (e) {}
  var b = bytesFromArray(p, IL);
  var s = managedBytes(p, IL);
  var out = [];
  if (s && s.len > 0 && s.len < 200000 && /^[\x09\x0a\x0d\x20-\x7e\u00a0-\uffff]*$/.test(s.text.slice(0, 64))) out.push('str(' + s.len + ')' + JSON.stringify(s.text.slice(0, 4000)));
  if (b && b.length > 0) out.push('bytes(' + b.length + ') hex=' + ab2hex(b, 96) + ' b64=' + base64(b.length > 3000 ? b.slice(0, 3000) : b));
  return out.length ? out.join(' | ') : ('ptr:' + p);
}
function hookAll(tag, f, IL) {
  Interceptor.attach(f, {
    onEnter: function (a) {
      var parts = [];
      for (var i = 0; i < 4; i++) parts.push('a' + i + '=' + anyVal(a[i], IL));
      log('CALL ' + tag + ' ' + parts.join(' ;; '));
    },
    onLeave: function (r) { log('RET  ' + tag + ' ' + anyVal(r, IL)); flush(); }
  });
  log('v4 hooked ' + tag);
}
function v4(IL) {
  var A = loadApi();
  var dom = A.domainGet(), n = Memory.alloc(4), asms = A.asmGet(dom, n), count = n.readU32();
  [['', 'Cipher', null], ['', 'HttpSystem', /Crypt/i], ['', 'AccountData', /Crypt|Iv/i]].forEach(function (c) {
    var k = findCls(A, asms, count, c[0], c[1]); if (!k) { log('v4 no class ' + c[1]); return; }
    var it = Memory.alloc(Process.pointerSize); it.writePointer(ptr(0)); var m;
    while ((m = A.clsMethods(k, it)) && !m.isNull()) {
      var mn = cstr(A.methName(m)) || '';
      if (c[2] && !c[2].test(mn)) continue;
      if (mn == '.ctor' || mn == '.cctor') continue;
      var f = fptr(A, m); if (!f) continue;
      try { hookAll(c[1] + '::' + mn, f, IL); } catch (e) { log('v4 attach ' + mn + ': ' + e); }
    }
  });
}

/* ---------------- 3. socket fallback ---------------- */
function hookSockets() {
  ['send', 'sendto', 'write'].forEach(function (nm) {
    try {
      var p = Module.getExportByName(null, nm);
      if (!p) return;
      Interceptor.attach(p, {
        onEnter: function (a) {
          try {
            var buf = a[1], len = a[2].toInt32();
            if (len < 16 || len > 1 << 20) return;
            var s = buf.readUtf8String(Math.min(len, 4096));
            if (s && /acc=|\.php|lang=/.test(s)) note('sock-' + nm, JSON.stringify(s.slice(0, 3000)));
          } catch (e) { }
        }
      });
      log('hooked ' + nm);
    } catch (e) { }
  });
}

/* ---------------- boot ---------------- */
function onIl2cpp() {
  var IL = Process.findModuleByName('libil2cpp.so');
  if (!IL) return false;
  log('libil2cpp.so base=' + IL.base + ' size=' + IL.size + ' (file offset of a VA = VA - 0x4000 in the code LOAD)');
  try { hookStrings(IL); } catch (e) { log('strings: ' + e); }
  /* dumpCrypto disabled: game does not use .NET crypto */
  try { hookSockets(); } catch (e) { log('sockets: ' + e); }
  try { v4(IL); } catch (e) { log('v4: ' + e); }
  flush();
  return true;
}

(function boot() {
  log('gadget alive pid=' + Process.id + ' arch=' + Process.arch + ' version=' + Frida.version);
  flush();
  if (!onIl2cpp()) {
    try {
      Interceptor.attach(Module.getExportByName(null, 'android_dlopen_ext'), {
        onEnter: function (a) {
          try {
            var nm = a[0].readCString() || '';
            this.want = nm.indexOf('libil2cpp.so') >= 0 || nm.indexOf('libunity.so') >= 0;
            if (this.want) log('dlopen ' + nm);
          } catch (e) { }
        },
        onLeave: function () {
          if (this.want) { log('dlopen done, IL2CPP visible=' + (Process.findModuleByName('libil2cpp.so') !== null)); onIl2cpp(); }
        }
      });
      log('armed android_dlopen_ext');
    } catch (e) { log('dlopen hook: ' + e); }
  }
  flush();
})();
