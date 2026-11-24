package com.aosp.carrier.hooks;

import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import com.aosp.carrier.MainHook;
import com.aosp.carrier.Sys;

/**
 * 把开关直接嵌进「设置 - 系统 - 状态栏」页，模块自己没有界面、没有桌面图标。
 *
 * 开关只负责写配置：设置进程本身就是系统身份，写 Settings.System / Settings.Global
 * 不需要 root。改完由页面底部的「重启系统界面」统一触发一次重启，状态栏、锁屏、
 * 运营商名再按新值重绘，避免每拨一个开关就闪一次。
 */
public final class SettingsPanelHook {

    /** 「设置 - 系统 - 状态栏」对应的 Fragment（PixelExperience 13 实测） */
    private static final String PAGE = "com.android.settings.custom.statusbar.StatusBarSettings";
    private static final String CATEGORY_KEY = "carrier_opt_category";
    private static final int ORDER_BASE = 10000;

    private SettingsPanelHook() {
    }

    public static String install(LoadPackageParam lp) {
        Class<?> base = XposedHelpers.findClassIfExists(
                "androidx.preference.PreferenceFragmentCompat", lp.classLoader);
        if (base == null) {
            return "no-base";
        }
        // onCreatePreferences 是抽象方法，hook 不了；页面把开关建完之后才走到
        // onViewCreated，在这里补最稳。
        XposedHelpers.findAndHookMethod(base, "onViewCreated",
                android.view.View.class, Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        inject(param.thisObject, lp.classLoader);
                    }
                });
        return "ok";
    }

    // ---------------- 注入页面 ----------------

    private static void inject(Object fragment, ClassLoader cl) {
        try {
            if (fragment == null || !PAGE.equals(fragment.getClass().getName())) {
                return;
            }
            Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
            if (screen == null) {
                return;
            }
            if (XposedHelpers.callMethod(screen, "findPreference", CATEGORY_KEY) != null) {
                return;
            }
            Context ctx = null;
            try {
                ctx = (Context) XposedHelpers.callMethod(fragment, "getContext");
            } catch (Throwable ignored) {
            }
            if (ctx == null) {
                ctx = (Context) XposedHelpers.callMethod(fragment, "getActivity");
            }
            if (ctx == null) {
                return;
            }

            Object category = newPreference(cl, ctx, "androidx.preference.PreferenceCategory");
            if (category == null) {
                return;
            }
            XposedHelpers.callMethod(category, "setKey", CATEGORY_KEY);
            XposedHelpers.callMethod(category, "setTitle", "运营商信息优化");
            XposedHelpers.callMethod(category, "setOrder", ORDER_BASE);
            XposedHelpers.callMethod(screen, "addPreference", category);

            switchRow(cl, ctx, category, 0, "显示 4G", "LTE 显示成 4G，载波聚合显示 4G+",
                    "net_4g", true, false);
            switchRow(cl, ctx, category, 1, "运营商中文化", "换成中国移动、联通、电信、广电",
                    "carrier_l10n", true, false);
            switchRow(cl, ctx, category, 2, "锁屏双卡简化", "锁屏上两个运营商名之间用细竖线",
                    "carrier_sep", true, false);
            switchRow(cl, ctx, category, 3, "修改分隔符", "分隔符改用字符 |",
                    "carrier_sep_char", false, false);
            switchRow(cl, ctx, category, 4, "数据网络图标优化", "信号图标不再显示感叹号；公共 WiFi 登录页也不再自动提示",
                    "no_bang", false, true);
            switchRow(cl, ctx, category, 5, "搜网加速", "手动搜网边扫边出结果",
                    "scan_fast", true, false);

            actionRow(cl, ctx, category);
            XposedBridge.log(MainHook.TAG + " settings panel injected");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " settings panel fail: " + t);
        }
    }

    /** 底部「重启系统界面」：开关只写配置，点这一行才统一重启一次 */
    private static void actionRow(ClassLoader cl, Context ctx, Object parent) {
        try {
            Object p = newPreference(cl, ctx, "androidx.preference.Preference");
            if (p == null) {
                return;
            }
            XposedHelpers.callMethod(p, "setKey", "carrier_restart");
            XposedHelpers.callMethod(p, "setTitle", "重启系统界面");
            XposedHelpers.callMethod(p, "setSummary", "应用上面的开关");
            XposedHelpers.callMethod(p, "setOrder", ORDER_BASE + 20);
            Class<?> iface = XposedHelpers.findClass(
                    "androidx.preference.Preference$OnPreferenceClickListener", cl);
            Object listener = Proxy.newProxyInstance(cl, new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("onPreferenceClick".equals(name)) {
                                toast(ctx, "系统界面已重启");
                                restartSystemUI(ctx);
                                return Boolean.TRUE;
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(name)) {
                                return proxy == (args == null ? null : args[0]);
                            }
                            if ("toString".equals(name)) {
                                return "carrier-restart";
                            }
                            return null;
                        }
                    });
            XposedHelpers.callMethod(p, "setOnPreferenceClickListener", listener);
            XposedHelpers.callMethod(parent, "addPreference", p);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " restart row fail: " + t);
        }
    }

    private static Object newPreference(ClassLoader cl, Context ctx, String cls) {
        try {
            Class<?> c = XposedHelpers.findClass(cls, cl);
            return XposedHelpers.newInstance(c, ctx);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void switchRow(ClassLoader cl, Context ctx, Object parent, int idx,
                                  String title, String summary, String key,
                                  boolean def, boolean captive) {
        try {
            Object sw = newPreference(cl, ctx, "androidx.preference.SwitchPreferenceCompat");
            if (sw == null) {
                sw = newPreference(cl, ctx, "androidx.preference.SwitchPreference");
            }
            if (sw == null) {
                return;
            }
            String prefKey = "carrier_" + key;
            XposedHelpers.callMethod(sw, "setKey", prefKey);
            XposedHelpers.callMethod(sw, "setTitle", title);
            XposedHelpers.callMethod(sw, "setSummary", summary);
            XposedHelpers.callMethod(sw, "setOrder", ORDER_BASE + 1 + idx);
            XposedHelpers.callMethod(sw, "setChecked", readBool(key, def));
            XposedHelpers.callMethod(sw, "setPersistent", false);

            Class<?> iface = XposedHelpers.findClass(
                    "androidx.preference.Preference$OnPreferenceChangeListener", cl);
            Object listener = Proxy.newProxyInstance(cl, new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("onPreferenceChange".equals(name)) {
                                boolean value = args != null && args.length > 1
                                        && Boolean.TRUE.equals(args[1]);
                                onToggle(ctx, key, value, captive);
                                return Boolean.TRUE;
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(name)) {
                                return proxy == (args == null ? null : args[0]);
                            }
                            if ("toString".equals(name)) {
                                return "carrier-toggle-" + key;
                            }
                            return null;
                        }
                    });
            XposedHelpers.callMethod(sw, "setOnPreferenceChangeListener", listener);
            XposedHelpers.callMethod(parent, "addPreference", sw);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " switch " + key + " fail: " + t);
        }
    }

    // ---------------- 写入 + 生效 ----------------

    private static boolean readBool(String key, boolean def) {
        Integer v = Sys.getBool(key);
        return v == null ? def : v == 1;
    }

    private static void onToggle(Context ctx, String key, boolean value, boolean captive) {
        try {
            Settings.System.putString(ctx.getContentResolver(),
                    Sys.PREFIX + key, value ? "1" : "0");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " write " + key + " fail: " + t);
        }
        if (captive) {
            applyCaptive(ctx, value);
        }
    }

    /** 去感叹号：关掉连通性检测，并把检测端点换成国内可达的 */
    private static void applyCaptive(Context ctx, boolean disable) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            Settings.Global.putInt(cr, "captive_portal_mode", disable ? 0 : 1);
            String http = disable ? "http://connect.rom.miui.com/generate_204"
                    : "http://connectivitycheck.gstatic.com/generate_204";
            String https = disable ? "https://connect.rom.miui.com/generate_204"
                    : "https://connectivitycheck.gstatic.com/generate_204";
            String fallback = disable ? "http://connect.rom.miui.com/generate_204"
                    : "http://www.google.com/gen_204";
            String other = disable ? "http://connect.rom.miui.com/generate_204"
                    : "http://play.googleapis.com/generate_204";
            Settings.Global.putString(cr, "captive_portal_http_url", http);
            Settings.Global.putString(cr, "captive_portal_https_url", https);
            Settings.Global.putString(cr, "captive_portal_fallback_url", fallback);
            Settings.Global.putString(cr, "captive_portal_other_fallback_urls", other);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " captive fail: " + t);
        }
    }

    private static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static void restartSystemUI(Context ctx) {
        // 设置进程杀不掉 SystemUI（不同 uid，也没有 root），改为给 SystemUI 里的注入代码
        // 发一条广播，让它自己退出，系统会把它重新拉起来，新值随之生效。
        try {
            android.content.Intent i = new android.content.Intent(
                    com.aosp.carrier.Inject.ACTION_RESTART);
            i.setPackage("com.android.systemui");
            ctx.sendBroadcast(i);
            XposedBridge.log(MainHook.TAG + " systemui restart broadcast sent");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " restart broadcast fail: " + t);
        }
    }
}
