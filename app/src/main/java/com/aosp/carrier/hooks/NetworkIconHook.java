package com.aosp.carrier.hooks;

import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import com.aosp.carrier.MainHook;
import com.aosp.carrier.Prefs;

/**
 * 去掉信号图标上的「!」。
 *
 * 按 Android 13 的真实源码（SystemUI 的 statusbar/connectivity 包）：
 *
 *   MobileSignalController#getCurrentIconId()
 *       boolean noInternet = mCurrentState.inetCondition == 0;
 *       boolean cutOut = dataDisabled || noInternet;
 *       return SignalDrawable.getState(level, getNumLevels(), cutOut);
 *
 *   SignalDrawable.getState(int level, int numLevels, boolean cutOut)
 *       return ((cutOut ? STATE_CUT : 0) << STATE_SHIFT) | ...
 *
 * 也就是说「!」不是单纯的"没有互联网"，而是 cutOut 这一位：数据被关掉、
 * 卡不是默认数据卡、当前网络没通过验证、甚至无服务（getEmptyState 也是 cutOut=true），
 * 都会画出它。按住 inetCondition 只解决其中一种，所以这里两层都处理：
 *
 *   1. SignalDrawable#getState(level, numLevels, cutOut) —— 直接把 cutOut 改成 false，
 *      这是真正决定画不画「!」的地方，所有路径一起覆盖；
 *   2. MobileSignalController —— getCurrentIconId / getQsCurrentIconId / notifyListeners
 *      之前把 inetCondition 设成 1（内容描述、"无互联网"文案跟着一起正常），
 *      updateConnectivity 之后按一遍，防止状态被后续覆盖。
 *
 * 注意 Android 13 把这两个类从 statusbar.policy 搬到了 statusbar.connectivity，
 * 旧包名只留作兜底，不存在时会自己跳过。
 *
 * 开关每 2 秒最多读一次，别让状态栏每次刷新都去读系统设置。
 * install() 返回形如 "on:4" 的状态串，由 MainHook 写进自检信息。
 */
public final class NetworkIconHook {

    private static final String SIGNAL_DRAWABLE = "com.android.settingslib.graph.SignalDrawable";

    private static final String[] MOBILE_CTRLS = {
            "com.android.systemui.statusbar.connectivity.MobileSignalController",
            "com.android.systemui.statusbar.policy.MobileSignalController",
    };

    /** SignalDrawable 的打包状态位：STATE_CUT = 2，STATE_SHIFT = 16 */
    private static final int STATE_MASK = 0xff << 16;
    private static final int CUT_BIT = 2 << 16;

    private static final AtomicInteger LOGGED = new AtomicInteger();
    private static long lastCheck;
    private static boolean enabled;
    /** 已经见过的 MobileSignalController 实例，开关切换后拿它们强制重算一次图标 */
    private static final List<Object> CTRLS = new ArrayList<>();

    private NetworkIconHook() {
    }

    private static void remember(Object c) {
        if (c == null) return;
        synchronized (CTRLS) {
            for (Object o : CTRLS) {
                if (o == c) return;
            }
            if (CTRLS.size() < 16) CTRLS.add(c);
        }
    }

    /**
     * 开关切换后调一次：让每张卡的控制器重新算图标并推给界面。
     * 不刷新的话，图标 id 是上一次算出来的缓存值，关掉开关后感叹号要等到
     * 信号/网络状态下次变化才回来——看上去就是「关了没反应」。
     */
    public static void refresh() {
        // 关键：先把开关值缓存清掉再算。系统表刚被 root 改过，
        // 这里如果还沿用 2 秒缓存，会拿旧值重算一遍，等于没刷新。
        lastCheck = 0;
        enabled = Prefs.getBool(Prefs.KEY_NO_BANG, false);
        XposedBridge.log(MainHook.TAG + " refresh begin, no_bang=" + enabled);
        Object[] list;
        synchronized (CTRLS) {
            list = CTRLS.toArray();
        }
        for (Object c : list) {
            try {
                Method m = null;
                Class<?> k = c.getClass();
                while (k != null && m == null) {
                    try {
                        m = k.getDeclaredMethod("notifyListeners");
                    } catch (NoSuchMethodException ignored) {
                        k = k.getSuperclass();
                    }
                }
                if (m == null) continue;
                m.setAccessible(true);
                m.invoke(c);
            } catch (Throwable t) {
                XposedBridge.log(MainHook.TAG + " refresh fail: " + t);
            }
        }
        XposedBridge.log(MainHook.TAG + " refresh icons, controllers=" + list.length);
    }

    private static boolean on() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCheck >= 2000L) {
            lastCheck = now;
            enabled = Prefs.getBool(Prefs.KEY_NO_BANG, false);
        }
        return enabled;
    }

    private static void force(Object controller, String where) {
        try {
            Object state = XposedHelpers.getObjectField(controller, "mCurrentState");
            XposedHelpers.setIntField(state, "inetCondition", 1);
            if (LOGGED.get() < 5) {
                LOGGED.incrementAndGet();
                XposedBridge.log(MainHook.TAG + " force inetCondition=1 @" + where);
            }
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " force inetCondition fail @" + where + ": " + t);
        }
    }

    /** @return 形如 "on:4" / "off:4" 的状态串，数字是挂上的点数 */
    public static String install(LoadPackageParam lp) {
        int points = hookSignalDrawable(lp);
        for (String name : MOBILE_CTRLS) {
            try {
                Class<?> cl = XposedHelpers.findClass(name, lp.classLoader);
                XposedBridge.log(MainHook.TAG + " icon hook class: " + cl.getName());
                points += hookIconId(cl, "getCurrentIconId", "iconId");
                points += hookIconId(cl, "getQsCurrentIconId", "qsIcon");
                points += hookMethods(cl, "notifyListeners", "notify", true);
                points += hookMethods(cl, "updateConnectivity", "conn", false);
            } catch (Throwable t) {
                XposedBridge.log(MainHook.TAG + " icon hook skip " + name + ": " + t);
            }
        }
        XposedBridge.log(MainHook.TAG + " icon hook done, points=" + points);
        // 自检里只报「挂上了没有」。启动早期读不到系统设置，开关值这时候不准，
        // 报了反而误导（曾经显示 icon=off 但其实一直在生效）。
        return points > 0 ? "" : "off";
    }

    /** 真正决定画不画「!」的地方：把 cutOut 位清掉 */
    private static int hookSignalDrawable(LoadPackageParam lp) {
        try {
            Class<?> cl = XposedHelpers.findClass(SIGNAL_DRAWABLE, lp.classLoader);
            XposedHelpers.findAndHookMethod(cl, "getState",
                    int.class, int.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (on() && param.args.length > 2) {
                                param.args[2] = false;
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " icon hook SignalDrawable fail: " + t);
            return 0;
        }
    }

    /**
     * 图标 id 就是 SignalDrawable 的打包状态：
     *   (state << 16) | (numLevels << 8) | level，STATE_CUT = 2。
     * 直接在返回值上把 cut 这一位抹掉，不依赖能不能 hook 到 SignalDrawable 本身
     * （系统里有不止一份这个类，静态方法 hook 不一定命中画图标的那一份）。
     */
    private static int hookIconId(Class<?> cl, String name, String where) {
        int n = 0;
        for (Method m : cl.getDeclaredMethods()) {
            if (!m.getName().equals(name) || m.getReturnType() != int.class) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    remember(p.thisObject);
                    if (on()) force(p.thisObject, where);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!on()) return;
                    Object r = p.getResult();
                    if (!(r instanceof Integer)) return;
                    int v = (Integer) r;
                    if ((v & STATE_MASK) == CUT_BIT) {
                        int fixed = v & ~CUT_BIT;
                        p.setResult(fixed);
                        if (LOGGED.get() < 12) {
                            LOGGED.incrementAndGet();
                            XposedBridge.log(MainHook.TAG + " strip cut @" + where + " " + v + " -> " + fixed);
                        }
                    }
                }
            });
            n++;
        }
        if (n == 0) {
            XposedBridge.log(MainHook.TAG + " icon hook: no " + name + " in " + cl.getSimpleName());
        }
        return n;
    }

    private static int hookMethods(Class<?> cl, String name, String where, boolean before) {
        int n = 0;
        for (Method m : cl.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    remember(p.thisObject);
                    if (before && on()) force(p.thisObject, where);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!before && on()) force(p.thisObject, where);
                }
            });
            n++;
        }
        if (n == 0) {
            XposedBridge.log(MainHook.TAG + " icon hook: no " + name + " in " + cl.getSimpleName());
        }
        return n;
    }
}
