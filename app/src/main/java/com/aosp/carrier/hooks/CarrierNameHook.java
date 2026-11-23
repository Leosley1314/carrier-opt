package com.aosp.carrier.hooks;

import android.telephony.ServiceState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import com.aosp.carrier.CarrierNames;
import com.aosp.carrier.MainHook;
import com.aosp.carrier.Prefs;

/**
 * 运营商名称汉化：状态栏、锁屏、设置里的 "China Mobile" 变成 "中国移动"。
 *
 * 只做一件事——把名字翻成中文（简称可配）。不替换名字来源、不跳卡取名字，
 * 保证每次调用都是纯字符串处理，跑在 SystemUI 的热路径上也不会拖慢或抛异常。
 */
public final class CarrierNameHook {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private CarrierNameHook() {
    }

    public static void install(LoadPackageParam lp) {
        hookGetter(lp, "getOperatorAlphaLong");
        hookGetter(lp, "getOperatorAlphaShort");

        hookTelephony(lp, "getNetworkOperatorName");
        hookTelephony(lp, "getSimOperatorName");
        hookTelephonyWithSubId(lp, "getNetworkOperatorName");
        hookTelephonyWithSubId(lp, "getSimOperatorName");

        hookSubscriptionInfo("getCarrierName");
        hookSubscriptionInfo("getDisplayName");
    }

    private static void hookGetter(LoadPackageParam lp, String method) {
        try {
            XposedHelpers.findAndHookMethod(ServiceState.class, method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object r = param.getResult();
                        if (!(r instanceof String)) {
                            return;
                        }
                        String localized = translate((String) r);
                        if (localized != null) {
                            param.setResult(localized);
                        }
                    } catch (Throwable t) {
                        logSkip("ServiceState#" + method, t);
                    }
                }
            });
            XposedBridge.log(MainHook.TAG + " hooked ServiceState#" + method);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " hook ServiceState#" + method + " failed: " + t);
        }
    }

    private static void hookTelephony(LoadPackageParam lp, String method) {
        try {
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager",
                    lp.classLoader, method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object r = param.getResult();
                                if (!(r instanceof String)) {
                                    return;
                                }
                                String localized = translate((String) r);
                                if (localized != null) {
                                    param.setResult(localized);
                                }
                            } catch (Throwable t) {
                                logSkip("TM#" + method, t);
                            }
                        }
                    });
            XposedBridge.log(MainHook.TAG + " hooked TelephonyManager#" + method);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " hook TM#" + method + " failed: " + t);
        }
    }

    private static void hookTelephonyWithSubId(LoadPackageParam lp, String method) {
        try {
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager",
                    lp.classLoader, method, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object r = param.getResult();
                                if (!(r instanceof String)) {
                                    return;
                                }
                                String localized = translate((String) r);
                                if (localized != null) {
                                    param.setResult(localized);
                                }
                            } catch (Throwable t) {
                                logSkip("TM#" + method + "(int)", t);
                            }
                        }
                    });
            XposedBridge.log(MainHook.TAG + " hooked TM#" + method + "(int)");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " hook TM#" + method + "(int) failed: " + t);
        }
    }

    private static void hookSubscriptionInfo(String method) {
        try {
            XposedHelpers.findAndHookMethod("android.telephony.SubscriptionInfo",
                    (ClassLoader) null, method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object r = param.getResult();
                                if (r == null) {
                                    return;
                                }
                                String localized = translate(r.toString());
                                if (localized != null) {
                                    param.setResult(localized);
                                }
                            } catch (Throwable t) {
                                logSkip("SubscriptionInfo#" + method, t);
                            }
                        }
                    });
            XposedBridge.log(MainHook.TAG + " hooked SubscriptionInfo#" + method);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " hook SubscriptionInfo#" + method + " failed: " + t);
        }
    }

    private static void logSkip(String where, Throwable t) {
        try {
            XposedBridge.log(MainHook.TAG + " skip " + where + ": " + t);
        } catch (Throwable ignored) {
        }
    }

    /** 带缓存，避免状态更新时反复做字符串处理 */
    static String translate(String raw) {
        try {
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            if (!Prefs.getBool(Prefs.KEY_CARRIER_L10N, true)) {
                return null;
            }
            String key = raw;
            String cached = CACHE.get(key);
            if (cached != null) {
                return cached.isEmpty() ? null : cached;
            }
            String out = CarrierNames.localize(raw);
            CACHE.put(key, out == null ? "" : out);
            return out;
        } catch (Throwable t) {
            logSkip("translate", t);
            return null;
        }
    }
}
