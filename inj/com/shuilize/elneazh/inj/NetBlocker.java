package com.shuilize.elneazh.inj;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketImpl;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * 单机模式的网络闸门：单机开启时，游戏服务器的请求一个字节都不发出去。
 *
 * 做法（全部是进程内可见、可回退的公开 API，不用 root/frida）：
 *  1. {@link URL#setURLStreamHandlerFactory} 给 https/http 注册我们自己的 handler，
 *     命中游戏服务器（见 {@link Offline#isGameServer}）时不建立任何连接，直接由
 *     {@link LocalConnection} 产出一份本机生成的离线回包。
 *  2. 兜底：{@link ProxySelector#setDefault} 对漏网的写请求返回一个必然连不上的代理。
 *  3. 纯函数 {@link #allow} 说明最终裁决，SelfCheck 与 tools/verify_offline.py 都验它。
 *
 * 两种可能的技术风险都按「宁可不拦，也不能让游戏起不来」处理：
 *  - 工厂只能设一次（第二次 setURLStreamHandlerFactory 抛 Error）：装不上就只留 ProxySelector 兜底。
 *  - Unity 若自建 curl 连接绕过 Java URL 栈，本闸门拦不到 —— 这时日志里
 *    「offline: intercepted none」会说话，真机验证清单里有对应项。
 */
final class NetBlocker {
    private static final String TAG = "ElneaZH-NET";
    private static boolean factoryInstalled;
    private static boolean proxyInstalled;
    private static Context APP;
    private static ProxySelector prevProxy;
    private static final AtomicInteger INTERCEPTED = new AtomicInteger();
    private static final AtomicInteger ASSET_HITS = new AtomicInteger();
    private static final Set<String> SEEN = Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final ConcurrentHashMap<String, Integer> HITS = new ConcurrentHashMap<>();

    private NetBlocker() {}

    static synchronized void install(Context ctx) {
        if (ctx != null) APP = ctx.getApplicationContext();
        if (!factoryInstalled) {
            try {
                URL.setURLStreamHandlerFactory(new Factory());
                factoryInstalled = true;
                Log.i(TAG, "offline gate: stream handler factory installed");
            } catch (Throwable t) {
                Log.w(TAG, "offline gate: factory unavailable, proxy fallback only", t);
            }
        }
        if (!proxyInstalled) {
            try {
                prevProxy = ProxySelector.getDefault();
                ProxySelector.setDefault(new Gate(prevProxy));
                proxyInstalled = true;
            } catch (Throwable t) {
                Log.w(TAG, "offline gate: proxy selector failed", t);
            }
        }
        Log.i(TAG, "offline gate: factory=" + factoryInstalled + " proxy=" + proxyInstalled);
    }

    static boolean installed() { return factoryInstalled || proxyInstalled; }

    static int intercepted() { return INTERCEPTED.get(); }

    /** 当前进程内被接住的接口计数（按名字）。 */
    static String hits() {
        if (HITS.isEmpty()) return "none";
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : HITS.entrySet()) {
            if (b.length() > 0) b.append(", ");
            b.append(e.getKey()).append('x').append(e.getValue());
        }
        return b.toString();
    }

    /** 裁决：单机开着 + 游戏服务器 → 不发出（本地回）。纯函数，离线可测。 */
    static boolean allow(String url, boolean offlineOn) {
        return !Offline.intercept(url, offlineOn);
    }

    static boolean allow(Context ctx, String url) {
        try {
            if (!Offline.isOn(ctx)) return true;
            boolean ok = allow(url, true);
            if (!ok) note(url);
            return ok;
        } catch (Throwable t) {
            return true;   // 判断出任何问题都放行：宁可漏拦，不能把游戏弄坏
        }
    }

    private static void note(String url) {
        INTERCEPTED.incrementAndGet();
        String php = Offline.describe(url);
        if (Offline.isAssetDownload(url)) {
            ASSET_HITS.incrementAndGet();
            if (SEEN.add("asset:" + php)) {
                Log.i(TAG, "offline: intercepted ASSET download " + php
                        + " -> local reply, nothing sent (先关单机模式再下载)");
            }
        }
        HITS.merge(php, 1, Integer::sum);
        if (SEEN.add(php)) {
            Log.i(TAG, "offline: intercepted " + php + (Offline.isWriteRequest(url) ? " (upload)"
                    : Offline.isReadRequest(url) ? " (read)" : " (sns04)")
                    + " -> local reply, nothing sent");
        }
    }

    /**
     * 单机期间被拦下的「资源/扩展包下载」次数。
     * 界面拿它在单机开着时提示「已下载的照常可用，新下载要先关单机」——
     * 只有真发生了下载拦截才提示，避免每局都弹。
     */
    static int assetHits() { return ASSET_HITS.get(); }

    // ---------------- handler 工厂 ----------------

    private static final class Factory implements URLStreamHandlerFactory {
        private final URLStreamHandler http = new LocalHandler("http", 80);
        private final URLStreamHandler https = new LocalHandler("https", 443);

        @Override public URLStreamHandler createURLStreamHandler(String protocol) {
            String p = protocol == null ? "" : protocol.toLowerCase(java.util.Locale.US);
            if ("http".equals(p)) return http;
            if ("https".equals(p)) return https;
            return null;   // 其它协议交回默认实现
        }
    }

    /**
     * https 时若判定放行，必须回到系统默认 handler：{@code new URL(url)} 在内部会再走一次
     * 我们自己的工厂，若不隔离就会无限递归。这里用「带保镖标记的线程」直接构造。
     */
    private static final class LocalHandler extends URLStreamHandler {
        private static final ThreadLocal<Boolean> IN_DEFAULT = new ThreadLocal<Boolean>();
        private final String proto;
        private final int port;

        LocalHandler(String proto, int port) { this.proto = proto; this.port = port; }

        @Override protected int getDefaultPort() { return port; }

        @Override protected URLConnection openConnection(URL u) throws IOException {
            return open(u, null);
        }

        @Override protected URLConnection openConnection(URL u, java.net.Proxy p) throws IOException {
            return open(u, p);
        }

        private URLConnection open(URL u, java.net.Proxy p) throws IOException {
            boolean intercept = false;
            try {
                intercept = !allow(APP, u.toString());
            } catch (Throwable ignored) {
            }
            if (intercept) {
                Offline.Reply r = Offline.offlineReply(u.toString());
                return new LocalConnection(u, r);
            }
            return real(u, p);
        }

        private URLConnection real(URL u, java.net.Proxy p) throws IOException {
            if (Boolean.TRUE.equals(IN_DEFAULT.get()) || "http".equals(proto)) {
                // http 不需要隔离（默认 http handler 不会回调工厂到 https），但统一走同一段更稳。
                return realDirect(u, p);
            }
            IN_DEFAULT.set(Boolean.TRUE);
            try {
                return realDirect(u, p);
            } finally {
                IN_DEFAULT.remove();
            }
        }

        private URLConnection realDirect(URL u, java.net.Proxy p) throws IOException {
            try {
                URL copy = new URL(proto, u.getHost(), u.getPort(), u.getFile());
                if (p != null) return copy.openConnection(p);
                return copy.openConnection();
            } catch (IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new IOException("offline gate passthrough failed: " + t, t);
            }
        }
    }

    /** 本机回包连接：不碰 socket，getInputStream 直接给离线回包字节。 */
    static final class LocalConnection extends URLConnection {
        private final Offline.Reply reply;

        LocalConnection(URL u, Offline.Reply r) {
            super(u);
            this.reply = r;
        }

        @Override public void connect() throws IOException { connected = true; }

        @Override public InputStream getInputStream() throws IOException {
            connect();
            return new ByteArrayInputStream(reply.bytes());
        }

        @Override public java.io.OutputStream getOutputStream() throws IOException {
            // 上传方向的写：吞掉，等于「发出去了但对方什么也没收到」，一个字节都不出手机。
            return new java.io.OutputStream() {
                @Override public void write(int b) {}
                @Override public void write(byte[] b, int off, int len) {}
            };
        }

        @Override public String getContentType() { return reply.type; }

        @Override public int getContentLength() { return reply.bytes().length; }

        @Override public long getContentLengthLong() { return reply.bytes().length; }

        @Override public String getHeaderField(String name) {
            if (name == null) return null;
            String n = name.toLowerCase(java.util.Locale.US);
            if ("content-type".equals(n)) return reply.type;
            if ("content-length".equals(n)) return String.valueOf(reply.bytes().length);
            if ("connection".equals(n)) return "close";
            return null;
        }
    }

    // ---------------- 兜底：ProxySelector ----------------

    private static final class Gate extends ProxySelector {
        private final ProxySelector base;

        Gate(ProxySelector base) { this.base = base; }

        @Override public List<java.net.Proxy> select(URI uri) {
            boolean ok;
            try {
                ok = uri == null || allow(APP, uri.toString());
            } catch (Throwable t) {
                ok = true;
            }
            if (!ok) {
                // 127.0.0.1:1 上没有服务，连接立刻被拒；这不是本机回包，只是拒绝发送。
                return Collections.singletonList(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                        new InetSocketAddress("127.0.0.1", 1)));
            }
            try {
                if (base != null) return base.select(uri);
            } catch (Throwable ignored) {
            }
            return Collections.singletonList(java.net.Proxy.NO_PROXY);
        }

        @Override public void connectFailed(URI uri, SocketAddress sa, IOException e) {
            try {
                if (base != null) base.connectFailed(uri, sa, e);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 供 SelfCheck 打印。 */
    static String describe() {
        return "factory=" + factoryInstalled + " proxy=" + proxyInstalled
                + " intercepted=" + INTERCEPTED.get() + " assetBlocked=" + ASSET_HITS.get();
    }

    /** 单机开着时，界面要给的那句下载提示（没线索时返回空串）。 */
    static String downloadTip() {
        if (ASSET_HITS.get() <= 0) return "";
        return Offline.downloadHint();
    }
}
