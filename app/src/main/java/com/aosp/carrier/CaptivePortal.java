package com.aosp.carrier;

import android.content.Context;

import com.aosp.carrier.root.RootShell;

/**
 * 去掉「网络受限」的感叹号。
 *
 * 系统的做法：连上网络后，NetworkMonitor 会去访问 captive_portal_* 里的几条地址做连通性检测，
 * 只有拿到「204 + 空 body」才给这个网络打上 NET_CAPABILITY_VALIDATED；没有这个能力位，
 * 状态栏的信号图标就带一个「!」。
 *
 * 关键的一条不在 URL 上，而在 captive_portal_mode：
 *   CAPTIVE_PORTAL_MODE_IGNORE = 0   —— 不做检测，直接按「已验证」处理
 *   CAPTIVE_PORTAL_MODE_PROMPT = 1   —— 检测到门户就提示登录（默认）
 * NetworkMonitor 的源码里就是 {@code mIsCaptivePortalCheckEnabled = mode != IGNORE}，
 * 关掉检测后它直接返回 204，网络立刻算已验证，感叹号自然没了。
 *
 * 所以这里两件事一起做：
 *   1. captive_portal_mode 置 0（关开关时还原成 1）；
 *   2. 四条检测地址换成国内可达的端点，这样即使以后把 mode 调回 1，检测也能正常通过。
 * 改完必须让网络重建一次（每个网络对象在建立时读一次设置），用飞行模式开关触发。
 */
public final class CaptivePortal {

    /** 国内端点：小米的连通性检测服务，返回 204 空 body */
    private static final String CN_HTTP = "http://connect.rom.miui.com/generate_204";
    private static final String CN_HTTPS = "https://connect.rom.miui.com/generate_204";

    /** AOSP 默认值，关闭开关时原样写回 */
    private static final String AOSP_HTTP = "http://connectivitycheck.gstatic.com/generate_204";
    private static final String AOSP_HTTPS = "https://connectivitycheck.gstatic.com/generate_204";
    private static final String AOSP_FALLBACK = "http://www.google.com/gen_204";
    private static final String AOSP_OTHER = "http://play.googleapis.com/generate_204";

    private CaptivePortal() {
    }

    /** 写设置 + 重建网络触发重新检测，返回给界面展示的输出 */
    public static String apply(Context ctx, boolean disableCheck) throws Exception {
        String http = disableCheck ? CN_HTTP : AOSP_HTTP;
        String https = disableCheck ? CN_HTTPS : AOSP_HTTPS;
        // 国内端点没有单独的 fallback，四条统一；还原时按 AOSP 的四个默认值各回各位
        String fallback = disableCheck ? CN_HTTP : AOSP_FALLBACK;
        String other = disableCheck ? CN_HTTP : AOSP_OTHER;

        StringBuilder sb = new StringBuilder();
        // 这一次 root 调用里顺手把「显示」那几个开关也写进系统表：
        // 系统界面只认系统表，把状态写入和图标刷新放在同一个脚本里，
        // 就不会出现「界面关了、系统表还是开」的时间差。
        android.content.SharedPreferences sp = Prefs.local(ctx);
        sb.append("settings put system ").append(Sys.PREFIX).append(Prefs.KEY_NO_BANG)
                .append(" '").append(sp.getBoolean(Prefs.KEY_NO_BANG, false) ? 1 : 0).append("'\n");
        sb.append("settings put system ").append(Sys.PREFIX).append(Prefs.KEY_NET_4G)
                .append(" '").append(sp.getBoolean(Prefs.KEY_NET_4G, true) ? 1 : 0).append("'\n");
        sb.append("settings put system ").append(Sys.PREFIX).append(Prefs.KEY_CARRIER_L10N)
                .append(" '").append(sp.getBoolean(Prefs.KEY_CARRIER_L10N, true) ? 1 : 0).append("'\n");
        sb.append("settings put system ").append(Sys.PREFIX).append(Prefs.KEY_CARRIER_SEP)
                .append(" '").append(sp.getBoolean(Prefs.KEY_CARRIER_SEP, true) ? 1 : 0).append("'\n");
        sb.append("settings put system ").append(Sys.PREFIX).append(Prefs.KEY_CARRIER_SEP_CHAR)
                .append(" '").append(sp.getBoolean(Prefs.KEY_CARRIER_SEP_CHAR, false) ? 1 : 0).append("'\n");
        sb.append("settings put system ").append(Sys.PREFIX).append(Prefs.KEY_SCAN_FAST)
                .append(" '").append(sp.getBoolean(Prefs.KEY_SCAN_FAST, true) ? 1 : 0).append("'\n");
        sb.append("settings put global captive_portal_mode ").append(disableCheck ? 0 : 1).append("\n");
        sb.append("settings put global captive_portal_http_url '").append(http).append("'\n");
        sb.append("settings put global captive_portal_https_url '").append(https).append("'\n");
        sb.append("settings put global captive_portal_fallback_url '").append(fallback).append("'\n");
        sb.append("settings put global captive_portal_other_fallback_urls '").append(other).append("'\n");

        sb.append("echo '--- 当前值 ---'\n");
        sb.append("echo \"mode  : $(settings get global captive_portal_mode)\"\n");
        sb.append("echo \"http  : $(settings get global captive_portal_http_url)\"\n");
        sb.append("echo \"https : $(settings get global captive_portal_https_url)\"\n");

        // 每个 Network 对象在建立时读一次 captive_portal_mode，改完必须重建网络才生效。
        // 优先用 ConnectivityService 的 shell 命令切飞行模式；不支持就退回开关射频。
        // 界面刚改完开关，让系统界面立刻重算一次图标（root 这条链路是确定能到的）
        sb.append("echo '--- 刷新信号图标 ---'\n");
        sb.append("am broadcast -a com.aosp.carrier.REFRESH -p com.android.systemui >/dev/null 2>&1\n");

        // 记住用户原来的移动数据/ WiFi 开关，别因为重建网络把人家的数据给打开了
        sb.append("DATA_WAS=$(settings get global mobile_data)\n");
        sb.append("WIFI_WAS=$(settings get global wifi_on)\n");

        sb.append("echo '--- 重建网络连接，让设置生效 ---'\n");
        sb.append("TOGGLED=0\n");
        sb.append("if cmd connectivity airplane-mode enable >/dev/null 2>&1; then TOGGLED=1; fi\n");
        sb.append("if [ \"$TOGGLED\" = 0 ]; then svc wifi disable >/dev/null 2>&1; svc data disable >/dev/null 2>&1; fi\n");
        sb.append("sleep 3\n");
        sb.append("if [ \"$TOGGLED\" = 1 ]; then cmd connectivity airplane-mode disable >/dev/null 2>&1; fi\n");
        // 无论走哪条路，最后都把射频强制打开，别让用户卡在没网的状态
        sb.append("settings put global airplane_mode_on 0\n");
        sb.append("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null 2>&1\n");
        // 恢复到用户原来的状态：原来关着的就别替他打开（之前就是因为强行 enable，
        // 关掉开关后网络真的通了，反而没有感叹号可显示，看着像「没恢复」）
        sb.append("case \"$WIFI_WAS\" in 0) svc wifi disable >/dev/null 2>&1 ;; *) svc wifi enable >/dev/null 2>&1 ;; esac\n");
        sb.append("case \"$DATA_WAS\" in 0) svc data disable >/dev/null 2>&1 ;; *) svc data enable >/dev/null 2>&1 ;; esac\n");
        sb.append("sleep 2\n");
        sb.append("echo \"airplane: $(settings get global airplane_mode_on)\"\n");
        sb.append("echo \"data  : $(settings get global mobile_data)\"\n");
        sb.append("am broadcast -a com.aosp.carrier.REFRESH -p com.android.systemui >/dev/null 2>&1\n");
        sb.append(disableCheck
                ? "echo '完成。信号图标应该不再带感叹号；如果还带着，屏幕焞掉再亮一次或等半分钟。'\n"
                : "echo '完成。已恢复系统默认的连通性检测（门户登录提示也会回来）。'\n");

        return RootShell.exec(ctx, sb.toString(), 30);
    }
}
