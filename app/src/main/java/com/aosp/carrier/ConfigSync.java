package com.aosp.carrier;

import android.content.Context;
import android.content.SharedPreferences;

import com.aosp.carrier.root.RootShell;

/**
 * 把模块 App 里的偏好推到 Settings.System，供被注入的进程（SystemUI / 设置 / 拨号）读取。
 * 需要 root；没有 root 就静默失败，效果等同以前（只影响即时生效，不影响已生效的旧值）。
 */
public final class ConfigSync {

    /** 上次成功推送的内容，用来判断“没有变化就不必再请求 root” */
    private static final String KEY_LAST_PUSH = "_last_push";

    private ConfigSync() {
    }

    private static void put(StringBuilder sb, String key, String value) {
        sb.append("settings put system ").append(Sys.PREFIX).append(key)
                .append(" '").append(value).append("'>/dev/null 2>&1\n");
    }

    private static void put(StringBuilder sb, String key, boolean value) {
        sb.append("settings put system ").append(Sys.PREFIX).append(key)
                .append(" '").append(value ? "1" : "0").append("'>/dev/null 2>&1\n");
    }

    public static void push(final Context ctx) {
        new Thread(() -> {
            try {
                SharedPreferences sp = Prefs.local(ctx);
                // 只有被注入进程（SystemUI / 设置）要读的开关才值得写进 Settings.System，
                // 其余（隐藏图标、内部记账键）都是 App 自己用，推过去只是垃圾
                StringBuilder sb = new StringBuilder();
                put(sb, Prefs.KEY_NET_4G, sp.getBoolean(Prefs.KEY_NET_4G, true));
                put(sb, Prefs.KEY_CARRIER_L10N, sp.getBoolean(Prefs.KEY_CARRIER_L10N, true));
                put(sb, Prefs.KEY_CARRIER_SEP, sp.getBoolean(Prefs.KEY_CARRIER_SEP, true));
                put(sb, Prefs.KEY_CARRIER_SEP_CHAR,
                        sp.getBoolean(Prefs.KEY_CARRIER_SEP_CHAR, false));
                put(sb, Prefs.KEY_NO_BANG, sp.getBoolean(Prefs.KEY_NO_BANG, false));
                put(sb, Prefs.KEY_SCAN_FAST, sp.getBoolean(Prefs.KEY_SCAN_FAST, true));
                // 2.4.0 删掉的功能：把以前写进 System 的旧键清掉，别在系统表里留垃圾
                sb.append("for k in sb_speed sb_hd net_bbr net_fastopen net_buf dns_host ntp_host scope_auto; do\n")
                        .append("  settings delete system ").append(Sys.PREFIX)
                        .append("$k >/dev/null 2>&1\ndone\n");
                String payload = sb.toString();
                // 以前这里「和上次一样就跳过」，结果开关关了但系统表还是旧值，
                // 系统界面读到的永远是开——「关掉不恢复」就是这条去重造成的，已经去掉。
                RootShell.exec(ctx, payload, 20);
                sp.edit().putString(KEY_LAST_PUSH, payload).apply();
            } catch (Throwable ignored) {
            }
        }, "pe-l10n-cfg-sync").start();
    }
}
