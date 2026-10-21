package com.shuilize.elneazh.inj;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

/**
 * 「交流与支持」dialog drawn in the game's own popup style (cream panel, brown frame, orange buttons).
 * Opened from the title screen Support → 「交流群与支持」 button (libil2cpp patch makes that button call
 * Application.OpenURL("elneazh://support"), which lands in {@link SupportActivity}) and once on first launch
 * ({@link About}). Every action is wrapped; nothing here may crash the game.
 */
final class Support {
    static final String TAG = "ElneaZH";
    static final String QQ = "790184852";
    static final String QQ_LINK = "https://qun.qq.com/universal-share/share?ac=1&authKey=2oXkeJiLXvUlnYoZp%2FCWwlD%2Fkvb3f69RpT6%2BePF8jahMWgvsuOZr5BW53OFi%2FX9j&busi_data=eyJncm91cENvZGUiOiI3OTAxODQ4NTIiLCJ0b2tlbiI6IkcvckZJRVFKWnZGanZ6YlhZRkNXbHJRM3kwb21kc1hWZzlOd21yYlA1dlI1UnRxWGthdzVqRnBRUFZhRVRlTDgiLCJ1aW4iOiIyNzcwMjEzOTE2In0%3D&data=sWsjEEx2_zevFLauPt59ifUwPK3xkIuZdmdJBr4RNbJzma9BghIQ407l-FwVZUGo53DJDh98NYQO90mOdeJjoA&svctype=4&tempid=h5_group_info";
    /** Original developer support address (the game's own 「Contact us」 web form sends mail here). */
    static final String DEV_MAIL = "elnea_support_en@althi.co.jp";

    // game popup palette
    static final int CREAM = Color.rgb(0xFB, 0xF1, 0xDA);
    static final int CREAM_DARK = Color.rgb(0xF0, 0xDF, 0xBC);
    static final int BROWN = Color.rgb(0x7A, 0x4B, 0x24);
    static final int BROWN_TEXT = Color.rgb(0x55, 0x36, 0x1C);
    static final int ORANGE = Color.rgb(0xF3, 0x93, 0x2A);
    static final int ORANGE_DARK = Color.rgb(0xC8, 0x6A, 0x12);

    static Dialog build(final Activity a) {
        final float d = a.getResources().getDisplayMetrics().density;
        final Dialog dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCanceledOnTouchOutside(true);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(round(CREAM, BROWN, 3 * d, 14 * d));
        int pad = (int) (18 * d);
        card.setPadding(pad, (int) (14 * d), pad, (int) (16 * d));

        TextView title = new TextView(a);
        title.setText("交流与支持");
        title.setTextColor(BROWN);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, (int) (8 * d));
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        // ---------------- 顶行：QQ 群（一行文字 + 群号，点了就复制/打开） ----------------
        TextView qq = new TextView(a);
        qq.setText("汉化交流 QQ 群：" + QQ + "（点这里加入）");
        qq.setTextColor(BROWN_TEXT);
        qq.setTextSize(13);
        qq.setGravity(Gravity.CENTER);
        qq.setSingleLine(true);
        qq.setEllipsize(TextUtils.TruncateAt.END);
        qq.setPadding(0, (int) (2 * d), 0, (int) (2 * d));
        qq.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { joinQQ(a); }
        });
        card.addView(qq, new LinearLayout.LayoutParams(-1, -2));

        // ---------------- 打赏：折叠成一行按钮，点开才显示收款码 ----------------
        Bitmap qr = null;
        try (InputStream in = a.getAssets().open("elneazh/about/qr.png")) { qr = BitmapFactory.decodeStream(in); } catch (Throwable ignored) {}
        if (qr != null) {
            final Bitmap qrBmp = qr;
            final LinearLayout fold = new LinearLayout(a);
            fold.setOrientation(LinearLayout.VERTICAL);
            fold.setGravity(Gravity.CENTER_HORIZONTAL);
            fold.setVisibility(View.GONE);

            TextView cap = small(a, "觉得好玩，可以扫码请汉化作者喝杯奶茶（全凭自愿，不捐一样能玩）。");
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.topMargin = (int) (6 * d);
            fold.addView(cap, cp);

            FrameLayout qrBox = new FrameLayout(a);
            qrBox.setBackground(round(Color.WHITE, BROWN, 2 * d, 8 * d));
            int qp = (int) (6 * d);
            qrBox.setPadding(qp, qp, qp, qp);
            ImageView iv = new ImageView(a);
            iv.setImageBitmap(qrBmp);
            iv.setAdjustViewBounds(true);
            int s = (int) (150 * d);
            qrBox.addView(iv, new FrameLayout.LayoutParams(s, s));
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-2, -2);
            qlp.topMargin = (int) (6 * d);
            fold.addView(qrBox, qlp);
            card.addView(fold, new LinearLayout.LayoutParams(-1, -2));

            final TextView pay = link(a, d, "赞赏支持（扫码 / 点开收款码）");
            pay.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean show = fold.getVisibility() != View.VISIBLE;
                    fold.setVisibility(show ? View.VISIBLE : View.GONE);
                    pay.setText(show ? "收起收款码" : "赞赏支持（扫码 / 点开收款码）");
                }
            });
            card.addView(pay);
        }

        addSep(a, d, card, 10);
        caption(a, d, card, "存档");

        // 主按钮：立即备份（整个弹窗只有这一个橙色）。
        // 「导出分享」不再是独立按钮 —— 玩家分不清「备份」和「导出分享」有什么区别；
        // 备份成功后直接给一个「分享到…」，想发给别人就点它。
        final TextView[] shareBtn = new TextView[1];
        card.addView(button(a, d, "立即备份", ORANGE, ORANGE_DARK, Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Backup.manual(a, new Backup.OnBackupDone() {
                        @Override public void onDone(final String name) {
                            if (name == null) return;
                            if (shareBtn[0] != null) return;   // 已经有分享按钮了，别叠
                            final TextView share = button(a, d, "分享到…（发给朋友或存到网盘）",
                                    CREAM_DARK, BROWN, BROWN, new View.OnClickListener() {
                                        @Override public void onClick(View v2) {
                                            try {
                                                Share.shareExisting(a, name);
                                            } catch (Throwable t) {
                                                Log.w(TAG, "share backup", t);
                                                Toast.makeText(a, "分享出错：" + t, Toast.LENGTH_LONG).show();
                                            }
                                        }
                                    });
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                            lp.topMargin = (int) (10 * d);
                            card.addView(share, lp);
                            shareBtn[0] = share;
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "backup", t);
                    Toast.makeText(a, "备份出错：" + t, Toast.LENGTH_LONG).show();
                }
            }
        }));
        smallLine(a, d, card, "启动时也会自动备份，卸载游戏也不丢。备份成功后可以「分享到…」发给别人。");

        // 剩下的只有「导入存档」（导出分享已并入立即备份）
        LinearLayout.LayoutParams impLp = new LinearLayout.LayoutParams(-1, (int) (46 * d));
        impLp.topMargin = (int) (10 * d);
        TextView imp = button(a, d, "导入存档", CREAM_DARK, BROWN, BROWN, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    // 走已有的 restore 流程：选 zip → 二次确认 → 先自动备份当前存档再覆盖
                    if (a instanceof SupportActivity) {
                        Backup.pick(a);
                    } else {
                        Intent i = new Intent(a, SupportActivity.class);
                        i.putExtra("restore", true);
                        a.startActivity(i);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "import save", t);
                    Toast.makeText(a, "导入出错：" + t, Toast.LENGTH_LONG).show();
                }
            }
        });
        card.addView(imp, impLp);
        // 单机说明写在导入下面：选择「只恢复存档」的原因主要就是单机
        smallLine(a, d, card, "导入时可只恢复存档、保留当前身份；单机模式请选这一项。");

        // 小文字链接：导出名字对照表
        TextView names = link(a, d, "导出名字对照表（NPC 中文名 ↔ 上传用英文）");
        names.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Share.shareNameTable(a);
                } catch (Throwable t) {
                    Log.w(TAG, "export names", t);
                    Toast.makeText(a, "导出名字对照表出错：" + t, Toast.LENGTH_LONG).show();
                }
            }
        });
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = (int) (4 * d);
        card.addView(names, nlp);

        // ---------------- 单机模式 ----------------
        try {
            addOfflineSection(a, d, card);
        } catch (Throwable t) {
            Log.w(TAG, "offline section", t);   // 这一段画不出来也不影响其它按钮
        }

        // ---------------- 原生加速（实验） ----------------
        try {
            addNativeSection(a, d, card);
        } catch (Throwable t) {
            Log.w(TAG, "native section", t);
        }

        // ---------------- 账号 ----------------
        addSep(a, d, card, 12);
        caption(a, d, card, "账号");
        card.addView(button(a, d, "更换游戏身份", CREAM_DARK, BROWN, BROWN, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Identity.confirm(a, null);
                } catch (Throwable t) {
                    Log.w(TAG, "identity", t);
                }
            }
        }));
        smallLine(a, d, card, "一直卡「维护中」时用，不用清除数据。");

        // ---------------- 底部 ----------------
        addSep(a, d, card, 12);
        TextView dev = link(a, d, "联系原作者（英文邮件）");
        dev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mailDev(a); }
        });
        card.addView(dev);
        card.addView(button(a, d, "关闭", BROWN, Color.rgb(0x4E, 0x2F, 0x14), Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) { try { dlg.dismiss(); } catch (Throwable ignored) {} }
        }));

        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(false);
        sv.setVerticalScrollBarEnabled(true);
        sv.addView(card, new FrameLayout.LayoutParams(-1, -2));
        dlg.setContentView(sv);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int sw = a.getResources().getDisplayMetrics().widthPixels;
            int sh = a.getResources().getDisplayMetrics().heightPixels;
            int width = Math.min((int) (sw * 0.9f), (int) (380 * d));
            // 高度留出上下边距，内容超出就滚动（小屏也能把「关闭」滚到）
            w.setLayout(width, Math.min((int) (sh * 0.86f), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return dlg;
    }

    /** 分隔线。 */
    private static void addSep(Activity a, float d, LinearLayout card, int top) {
        View sep = new View(a);
        sep.setBackgroundColor(Color.argb(0x33, 0x7A, 0x4B, 0x24));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, Math.max(1, (int) (1.5f * d)));
        slp.topMargin = (int) (top * d);
        card.addView(sep, slp);
    }

    /** 分组标题（存档 / 单机模式 / 账号）：一行、小号、深棕。 */
    private static void caption(Activity a, float d, LinearLayout card, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(BROWN);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (8 * d);
        card.addView(t, lp);
    }

    /** 按钮下面那行小字：一行、居中、不抢眼。 */
    private static void smallLine(Activity a, float d, LinearLayout card, String text) {
        TextView t = small(a, text);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (4 * d);
        card.addView(t, lp);
    }

    static TextView small(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(BROWN_TEXT);
        t.setTextSize(11);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** 小文字链接：无底色、下划线、点了才动。 */
    static TextView link(final Activity a, final float d, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(Color.rgb(0x2F, 0x5D, 0x8A));
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPaintFlags(t.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        t.setClickable(true);
        t.setFocusable(true);
        t.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        t.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    /**
     * 单机模式：一个开关 + 一行说明。
     *
     * 开关状态存 shared_prefs/elneazh_settings（键 offline）。它**不参与**备份/恢复：
     * 恢复别人的存档不该顺手把「单机开/关」换掉（Backup 写入端已排除这个 prefs 文件）。
     * 点一下即生效（NetBlocker / 原生闸门每个请求都现读 Offline.isOn()，不需要重进游戏）。
     * 详细状态（挂钩了多少入口、挡了几次）只写 logcat（标签 ElneaOffline），不占弹窗版面。
     */
    private static void addOfflineSection(final Activity a, final float d, LinearLayout card) {
        final boolean[] on = {Offline.isOn(a)};

        addSep(a, d, card, 12);
        caption(a, d, card, "单机模式");

        final TextView toggle = button(a, d, toggleLabel(on[0]),
                on[0] ? ORANGE : CREAM_DARK, on[0] ? ORANGE_DARK : BROWN, on[0] ? Color.WHITE : BROWN, null);
        final TextView state = smallLine1(a, offlineText(on[0]));
        card.addView(toggle);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = (int) (4 * d);
        card.addView(state, stp);

        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    boolean next = !on[0];
                    if (!Offline.set(a, next)) {
                        Toast.makeText(a, "设置没存上，单机模式未改变", Toast.LENGTH_LONG).show();
                        return;
                    }
                    on[0] = next;
                    // 先把新状态推给原生闸门再改界面：hook 那边是现读的，推完立刻生效。
                    // 顺便把上一轮的拦截计数清零，真机看日志时能分清是这一轮挡的。
                    try {
                        OfflineGate.push(next);
                        OfflineGate.resetStats();
                        // 对账：prefs 与 native 现读值必须一致。不一致 = 请求还会出去，
                        // 这里立刻再补一次 install（.so 可能刚刚才装好）。
                        if (!OfflineGate.stateMatches(a)) {
                            Log.w(TAG, "offline state mismatch after push; reinstalling gate");
                            OfflineGate.ensureInstalled(a);
                            OfflineGate.push(next);
                        }
                        Log.i("ElneaOffline", "toggle -> " + next + " match=" + OfflineGate.stateMatches(a)
                                + " " + OfflineGate.describe());
                    } catch (Throwable t) {
                        Log.w(TAG, "offline gate push", t);
                    }
                    toggle.setText(toggleLabel(next));
                    toggle.setTextColor(next ? Color.WHITE : BROWN);
                    toggle.setBackground(round(next ? ORANGE : CREAM_DARK, next ? ORANGE_DARK : BROWN, 2.5f * d, 10 * d));
                    state.setText(offlineText(next));
                    Toast.makeText(a, next ? "单机模式已开启：不连服务器，请求不发出去"
                                    : "单机模式已关闭：恢复正常联网",
                            Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Log.w(TAG, "offline toggle", t);
                    Toast.makeText(a, "切换单机模式出错：" + t, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private static String toggleLabel(boolean on) {
        return on ? "单机模式：已开启（点击关闭）" : "单机模式：已关闭（点击开启）";
    }

    /**
     * 原生加速（实验）：默认开。关掉 = 永不加载 libelneagate.so（拼音闸门与单机 native 闸门全跳过，
     * Java 层兜底照旧）。给「这台机器上 native 就是黑屏/卡死」的用户一个硬开关。
     *
     * <p>为什么要给这个开关：真机事故（单机开/关都黑屏、几十秒后进程退出、无 tombstone、
     * 闸门日志一条都没有）说明 native 这条链在个别 ROM 上能把主线程拖死。
     * {@link NativeGuard} 会自动停用（连续 2 轮启动没活下来），但自动判断可能误伤，
     * 用户要能自己关、自己能开回来。
     */
    private static void addNativeSection(final Activity a, final float d, LinearLayout card) {
        final boolean[] on = {NativeGuard.boostEnabled(a)};

        addSep(a, d, card, 12);
        caption(a, d, card, "原生加速（实验）");

        final TextView toggle = button(a, d, nativeLabel(on[0]),
                on[0] ? ORANGE : CREAM_DARK, on[0] ? ORANGE_DARK : BROWN, on[0] ? Color.WHITE : BROWN, null);
        final TextView state = smallLine1(a, nativeText(a, on[0]));
        card.addView(toggle);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = (int) (4 * d);
        card.addView(state, stp);

        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    boolean next = !on[0];
                    if (!NativeGuard.setBoost(a, next)) {
                        Toast.makeText(a, "设置没存上，原生加速未改变", Toast.LENGTH_LONG).show();
                        return;
                    }
                    on[0] = next;
                    toggle.setText(nativeLabel(next));
                    toggle.setTextColor(next ? Color.WHITE : BROWN);
                    toggle.setBackground(round(next ? ORANGE : CREAM_DARK, next ? ORANGE_DARK : BROWN, 2.5f * d, 10 * d));
                    state.setText(nativeText(a, next));
                    Toast.makeText(a, next ? "已开启原生加速：重开游戏后生效"
                                    : "已关闭原生加速：重开游戏后完全不加载 native",
                            Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Log.w(TAG, "native boost toggle", t);
                    Toast.makeText(a, "切换原生加速出错：" + t, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private static String nativeLabel(boolean on) {
        return on ? "原生加速：已开启（点击关闭）" : "原生加速：已关闭（点击开启）";
    }

    /** 开关下面那一行：现状 + 为什么可能要关。 */
    private static String nativeText(Activity a, boolean want) {
        String now;
        try {
            now = NativeLoad.ready() ? "本轮已加载" : "本轮未加载";
        } catch (Throwable t) {
            now = "状态未知";
        }
        if (!want) {
            return "关：完全不加载 native（拼音闸门与单机原生闸门跳过），Java 层拦截照旧。";
        }
        if (!NativeGuard.allowed()) {
            return "已开但本轮被崩溃保护跳过（" + now + "），重开游戏再试。";
        }
        return "开：拼音闸门 + 单机原生闸门（" + now + "）。黑屏/卡死时请关掉它。";
    }

    /** 开关下面那一行：必须一行说清「开了会怎样、要不要重启」。 */
    private static String offlineText(boolean on) {
        if (!on) {
            return "开：不连服务器，商店/邮箱/好友/PVP 入口隐藏，资源照常下载。";
        }
        String tip = "";
        try { tip = NetBlocker.downloadTip(); } catch (Throwable ignored) {}
        if (tip != null && !tip.isEmpty()) return "已开：不连服务器，资源照常下载（少数需关单机）。";
        return "已开：不连服务器，商店/邮箱/好友/PVP 已隐藏，需重开游戏彻底生效。";
    }

    /** 单行小字（超长省略号），用于开关说明。 */
    private static TextView smallLine1(Activity a, String text) {
        TextView t = small(a, text);
        t.setGravity(Gravity.CENTER);
        return t;
    }


    static TextView button(Context c, float d, String text, int fill, int stroke, int textColor, View.OnClickListener l) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_pressed}, round(stroke, stroke, 2.5f * d, 10 * d));
        st.addState(new int[0], round(fill, stroke, 2.5f * d, 10 * d));
        b.setBackground(st);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (46 * d));
        lp.topMargin = (int) (10 * d);
        b.setLayoutParams(lp);
        return b;
    }

    static GradientDrawable round(int fill, int stroke, float strokeW, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(Math.max(1, (int) strokeW), stroke);
        g.setCornerRadius(radius);
        return g;
    }

    static void restore(Activity a) {
        try {
            if (a instanceof SupportActivity) { Backup.pick(a); return; }
            Intent i = new Intent(a, SupportActivity.class);
            i.putExtra("restore", true);
            a.startActivity(i);
        } catch (Throwable t) { Log.w(TAG, "restore", t); }
    }

    static void joinQQ(Activity a) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(QQ_LINK));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            copy(a, "QQ群", QQ, null);
        } catch (Throwable t) {
            Log.w(TAG, "join qq", t);
            copy(a, "QQ群", QQ, "没能打开加群页面，已复制群号 " + QQ + "，请在 QQ 里搜索加群");
        }
    }

    static void mailDev(Activity a) {
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + DEV_MAIL));
            i.putExtra(Intent.EXTRA_SUBJECT, "Elnea Kingdom inquiry");
            i.putExtra(Intent.EXTRA_TEXT, "------- Please describe the content of your inquiry (in English) -------\n\n\n");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            copy(a, "邮箱", DEV_MAIL, "没有邮件 App，已复制原作者邮箱 " + DEV_MAIL);
        } catch (Throwable t) {
            Log.w(TAG, "mail dev", t);
            copy(a, "邮箱", DEV_MAIL, "已复制原作者邮箱 " + DEV_MAIL);
        }
    }

    static void copy(Context c, String label, String text, String toast) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
        } catch (Throwable ignored) {}
        if (toast != null) try { Toast.makeText(c, toast, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }
}
