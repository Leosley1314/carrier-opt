package com.aosp.carrier.hooks;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import com.aosp.carrier.MainHook;
import com.aosp.carrier.Prefs;

/**
 * LTE 显示成 4G。参考 MIUI：LTE -> 4G，LTE_CA（载波聚合）-> 4G+，NR 保持 5G。
 *
 * 做法是改源头：TelephonyManager.getNetworkTypeName(int) 是 SystemUI / 设置里
 * 网络类型标签的公共来源，改这一处状态栏、快捷开关面板、锁屏、设置页全部生效。
 */
public final class NetworkLabelHook {

    private static final String TM = "android.telephony.TelephonyManager";
    private NetworkLabelHook() {
    }

    public static void install(LoadPackageParam lp) {
        if (!Prefs.getBool(Prefs.KEY_NET_4G, true)) {
            XposedBridge.log(MainHook.TAG + " net label hook disabled");
            return;
        }

        // 1. 主入口：静态方法 TelephonyManager.getNetworkTypeName(int)
        hookTelephonyManager(lp);

        // 2. 兜底：SystemUI 自己的网络类型计算（不同版本实现位置不同，反射找一遍）
        hookSystemUiFallback(lp);

        // 3. 最稳的一层：状态栏那个「LTE」是 TextView 直接 setText 出来的（PE 13 走
        //    AndroidX pipeline，不经过 getNetworkTypeName），所以从文本入口精确替换。
        hookTextView();

        // 注：不再 hook Resources#getString/getText。那是 SystemUI 最热的调用之一，
        // 每个字符串都进一次 hook 会拖慢界面，且实际并未命中 LTE 文本。
    }

    private static void hookTelephonyManager(LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(TM, lp.classLoader, "getNetworkTypeName",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object r = param.getResult();
                            replace(param);
                        }
                    });
            XposedBridge.log(MainHook.TAG + " hooked TelephonyManager#getNetworkTypeName");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " hook TM failed: " + t);
        }
    }

    /**
     * 有些 ROM 会把网络类型字符串在 SystemUI 里自建一遍（形如 getNetworkTypeName / getNetworkTypeLabel），
     * 返回值是 String 的一并处理，避免主入口不生效时仍然显示 LTE。
     */
    private static void hookSystemUiFallback(LoadPackageParam lp) {
        if (!MainHook.PKG_SYSTEMUI.equals(lp.packageName)) {
            return;
        }
        String[] classes = {
                "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconViewModel",
                "com.android.systemui.statusbar.pipeline.mobile.data.repository.RealMobileConnectionRepository",
                "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractorImpl",
                "com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter",
        };
        for (String name : classes) {
            try {
                Class<?> clazz = XposedHelpers.findClass(name, lp.classLoader);
                for (Method m : clazz.getDeclaredMethods()) {
                    String mn = m.getName().toLowerCase();
                    if (!m.getReturnType().equals(String.class)) {
                        continue;
                    }
                    if (!mn.contains("networktypename") && !mn.contains("networktypelabel")) {
                        continue;
                    }
                    if (m.getParameterTypes().length == 0) {
                        continue;
                    }
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            replace(param);
                        }
                    });
                    XposedBridge.log(MainHook.TAG + " hooked fallback " + name + "#" + m.getName());
                }
            } catch (Throwable ignored) {
                // 类不存在很正常，换机型/换版本就走不到这里
            }
        }
    }

    /**
     * 直接拦 TextView 的文本设置。只做「长度<=6 且整串等于 LTE/LTE_CA/LTE+」的精确匹配，
     * 其余立刻返回，避免 SystemUI 每次刷文本都付出比较代价。
     */
    private static void hookTextView() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 0) {
                    return;
                }
                Object a = param.args[0];
                if (!(a instanceof CharSequence)) {
                    return;
                }
                CharSequence cs = (CharSequence) a;
                if (cs.length() > 6) {
                    return;
                }
                String t = map(cs.toString());
                if (t != null) {
                    param.args[0] = t;
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod("android.widget.TextView", null, "setText",
                    CharSequence.class, hook);
            XposedBridge.log(MainHook.TAG + " hooked TextView#setText");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " hook TextView failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod("android.widget.TextView", null, "setText",
                    CharSequence.class, "android.widget.TextView$BufferType", hook);
            XposedBridge.log(MainHook.TAG + " hooked TextView#setText(CharSequence,BufferType)");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " hook TextView(buffer) failed: " + t);
        }
    }

    private static void replace(XC_MethodHook.MethodHookParam param) {
        Object r = param.getResult();
        if (!(r instanceof String)) {
            return;
        }
        String t = map((String) r);
        if (t != null) {
            param.setResult(t);
        }
    }

    /** 只有精确的 LTE 家族才改，避免误伤 "LTE-A Pro" 之类的复合标签 */
    private static String map(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if ("LTE".equalsIgnoreCase(t)) {
            return "4G";
        }
        if ("LTE_CA".equalsIgnoreCase(t) || "LTE+".equalsIgnoreCase(t)) {
            return "4G+";
        }
        return null;
    }
}
