package com.aosp.carrier;

import android.content.Context;
import android.content.SharedPreferences;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * 配置读取。模块没有界面了，所有开关都由「设置 - 系统 - 状态栏」页写进系统表，
 * 注入进程直接读 Settings.System 就拿到最新值；XSharedPreferences 只当退路。
 */
public final class Prefs {

    public static final String FILE = "pe_l10n";

    public static final String KEY_NET_4G = "net_4g";                 // LTE -> 4G
    public static final String KEY_CARRIER_L10N = "carrier_l10n";     // 运营商名称汉化
    public static final String KEY_NO_BANG = "no_bang";               // 连通性检测换国内端点，去掉感叹号
    public static final String KEY_SCAN_FAST = "scan_fast";          // 手动搜网加速

    // ---- 锁屏 / 状态栏 ----
    public static final String KEY_CARRIER_SEP = "carrier_sep";       // 锁屏双卡分隔符用竖线
    public static final String KEY_CARRIER_SEP_CHAR = "carrier_sep_char"; // 竖线用字符 |（关=用绘制的细线）

    private static XSharedPreferences remote;
    /** 每个键只打一次「这次值是从哪儿读的」，排查开关不跟手用（上限 8 条） */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> SEEN =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static int seenCount;

    private static void trace(String key, String src, Object val) {
        if (SEEN.putIfAbsent(key, Boolean.TRUE) != null) return;
        if (++seenCount > 8) return;
        XposedBridge.log(MainHook.TAG + " prefs " + key + " = " + val + " (from " + src + ")");
    }
    private static long lastReload;
    /** 镜像文件的检查间隔：1 秒。 */
    private static final long RELOAD_TTL_MS = 1000L;

    private Prefs() {
    }

    /**
     * 取镜像里的 SharedPreferences。
     *
     * 以前这里是「进程内只读一次」，所以改完开关、不重启界面进程就永远读到旧值，
     * 表现就是「运营商用简称」打开了但锁屏还是全称。改成按 TTL 重新 reload。
     */
    private static synchronized XSharedPreferences remote() {
        try {
            if (remote == null) {
                remote = new XSharedPreferences("com.aosp.carrier", FILE);
                lastReload = 0;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastReload >= RELOAD_TTL_MS) {
                lastReload = now;
                remote.reload();
            }
            return remote;
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " prefs open fail: " + t);
            return null;
        }
    }

    public static boolean getBool(String key, boolean def) {
        // 唯一可信来源是系统表：开关页直接写它，系统界面读它不需要任何权限。
        // 镜像和 App 内的值会脱节（界面已经是关、镜像还是开），只当退路。
        Integer sys = Sys.getBool(key);
        if (sys != null) {
            trace(key, "settings", sys);
            return sys == 1;
        }
        try {
            XSharedPreferences r = remote();
            if (r != null && r.contains(key)) {
                boolean v = r.getBoolean(key, def);
                trace(key, "mirror", v);
                return v;
            }
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " prefs read fail: " + t);
        }
        trace(key, "default", def);
        return def;
    }

    public static String getString(String key, String def) {
        try {
            XSharedPreferences r = remote();
            if (r != null && r.contains(key)) {
                String v = r.getString(key, def);
                if (v != null) {
                    return v;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " prefs read fail: " + t);
        }
        String sys = Sys.get(key);
        return sys != null ? sys : def;
    }

    // ---- 供模块 App 自身使用 ----

    public static SharedPreferences local(Context ctx) {
        return SharedPreferences.class.cast(
                ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }
}
