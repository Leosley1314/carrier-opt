package com.aosp.carrier;

import android.content.Context;
import android.content.SharedPreferences;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * 配置读取。模块 App 内用普通 SharedPreferences 写入；
 * 被注入的进程（SystemUI / 设置）用 XSharedPreferences 读取。
 * 依赖清单里的 xposedsharedprefs 元数据，由 LSPosed 负责跳进程可读。
 */
public final class Prefs {

    public static final String FILE = "pe_l10n";

    public static final String KEY_NET_4G = "net_4g";                 // LTE -> 4G
    public static final String KEY_CARRIER_L10N = "carrier_l10n";     // 运营商名称汉化
    public static final String KEY_HIDE_ICON = "hide_icon";           // 隐藏启动器图标（改从 LSPosed 管理器进入）
    public static final String KEY_NO_BANG = "no_bang";               // 连通性检测换国内端点，去掉感叹号
    public static final String KEY_SCAN_FAST = "scan_fast";          // 手动搜网加速

    // ---- 锁屏 / 状态栏 ----
    public static final String KEY_CARRIER_SEP = "carrier_sep";       // 锁屏双卡分隔符用垒线
    public static final String KEY_CARRIER_SEP_CHAR = "carrier_sep_char"; // 垒线用字符 |（关=用绘制的细线）

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
    /** 镜像文件的检查间隔：1 秒。开关刚改完，下一次文本刷新就能读到。 */
    private static final long RELOAD_TTL_MS = 1000L;

    private Prefs() {
    }

    /**
     * 取镜像里的 SharedPreferences。
     *
     * 以前这里是「进程内只读一次」，所以 App 里改完开关、不重启界面进程就永远读到旧值，
     * 表现就是「运营商用简称」打开了但锁屏还是全称。改成按 TTL 重新 reload，
     * 秒级跟上改动，同时避免每个状态回调都去碰一次文件。
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
        // 唯一可信来源是系统表：App 里改开关时由 root 在同一次脚本里写进去，
        // 系统界面读它不需要任何权限。以前优先读 LSPosed 的偏好镜像，
        // 但那个镜像和 App 内的值会脱节（界面已经是关、镜像还是开），
        // 表现就是「关掉开关感叹号不回来」。镜像现在只当退路。
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
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
