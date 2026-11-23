package com.aosp.carrier.hooks;

import android.telephony.NetworkScanRequest;
import android.telephony.RadioAccessSpecifier;
import android.telephony.TelephonyManager;

import java.lang.reflect.Method;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import com.aosp.carrier.MainHook;
import com.aosp.carrier.Prefs;

/**
 * 手动搜网加速。
 *
 * 设置里「搜索网络运营商」会走 TelephonyManager#requestNetworkScan，框架默认按
 * 「2G + 3G + 4G + 5G 全制式」逐频段扫描，2G/3G 那几段在现代网络上基本是空扫，
 * 白白占掉大半时间。这里在请求真正下发到 phone 进程之前，把扫描制式裁成
 * LTE / NR 两种，参数对象会随 binder 一起传过去，所以裁剪是生效的。
 *
 * 另外两件事一起做，都是为了「列表更快出来、转圈更短」：
 *   1. 把「等全部结果」改成「边扫边回」：设置里那个搜索页面只有在收到
 *      MIN_NUMBER_OF_SCAN_REQUIRED(2) 个结果后才结束转圈，WAIT_FOR_ALL 模式
 *      必须等基带把整个频段扫完才回第一批，体感就是一直转圈；
 *   2. 给扫描时长封顶（默认给 120 秒），避免长时间空转。
 *
 * 只影响「手动搜网」这条链路：日常驻网、自动重选、开机注册都不经过这里，
 * 因此不会出现「裁完搜不到网」的风险。
 */
public final class ScanHook {

    /** android.telephony.AccessNetworkConstants.AccessNetworkType */
    private static final int RAN_EUTRAN = 4;
    private static final int RAN_NGRAN = 5;

    /** 扫描时长上限（秒）。设置默认值一般是 120，这里砍掉一半多。 */
    private static final int MAX_SEARCH_SECONDS = 50;
    /** 边扫边回：每 2 秒交一批结果 */
    private static final int INCREMENTAL_SECONDS = 2;

    private static final String NSH = "com.android.settings.network.telephony.NetworkScanHelper";
    private static int logged;

    private ScanHook() {
    }

    public static void install(LoadPackageParam lp) {
        int hooked = 0;
        for (Method m : TelephonyManager.class.getDeclaredMethods()) {
            if (!"requestNetworkScan".equals(m.getName())) {
                continue;
            }
            Class<?>[] types = m.getParameterTypes();
            if (types.length == 0 || !NetworkScanRequest.class.equals(types[0])) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!Prefs.getBool(Prefs.KEY_SCAN_FAST, true)) return;
                        try {
                            if (param.args.length == 0
                                    || !(param.args[0] instanceof NetworkScanRequest)) {
                                return;
                            }
                            NetworkScanRequest faster = trim((NetworkScanRequest) param.args[0]);
                            if (faster != null) {
                                param.args[0] = faster;
                            }
                        } catch (Throwable ignored) {
                            // 设置进程不能因为这里出错而崩，最坏只是没加速
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                XposedBridge.log(MainHook.TAG + " hook " + m + " failed: " + t);
            }
        }
        hookNetworkScanHelper(lp);
        XposedBridge.log(MainHook.TAG + " hooked requestNetworkScan: " + hooked);
    }

    /**
     * 设置里那个「搜索网络」页面自己的扫描器。两个点：
     *   1. startNetworkScan(type)：把 WAIT_FOR_ALL_RESULTS 改成 INCREMENTAL_RESULTS，
     *      列表才会边扫边出，而不是等整轮扫完才一次性刷出来；
     *   2. createNetworkScanForPreferredAccessNetworks()：给时长封顶、把回包周期调快。
     */
    private static void hookNetworkScanHelper(LoadPackageParam lp) {
        Class<?> cl;
        try {
            cl = XposedHelpers.findClass(NSH, lp.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " scan helper not found: " + t);
            return;
        }

        int n = 0;
        for (Method m : cl.getDeclaredMethods()) {
            if (!"startNetworkScan".equals(m.getName())) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != 1 || pt[0] != int.class) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!Prefs.getBool(Prefs.KEY_SCAN_FAST, true)) return;
                    // 1 = WAIT_FOR_ALL_RESULTS（等整轮扫完才出结果），2 = 边扫边回
                    if (p.args.length == 1 && p.args[0] instanceof Integer
                            && (Integer) p.args[0] == 1) {
                        p.args[0] = 2;
                        logOnce("startNetworkScan: WAIT_FOR_ALL -> INCREMENTAL");
                    }
                }
            });
            n++;
        }

        try {
            XposedHelpers.findAndHookMethod(cl, "createNetworkScanForPreferredAccessNetworks",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (!Prefs.getBool(Prefs.KEY_SCAN_FAST, true)) return;
                            Object r = p.getResult();
                            if (!(r instanceof NetworkScanRequest)) return;
                            try {
                                NetworkScanRequest req = (NetworkScanRequest) r;
                                int max = req.getMaxSearchTime();
                                int inc = req.getIncrementalResultsPeriodicity();
                                int newMax = Math.min(max, MAX_SEARCH_SECONDS);
                                int newInc = inc <= 0 ? INCREMENTAL_SECONDS
                                        : Math.min(inc, INCREMENTAL_SECONDS);
                                if (newMax == max && newInc == inc && req.getIncrementalResults()) {
                                    return;
                                }
                                NetworkScanRequest faster = new NetworkScanRequest(
                                        req.getScanType(), req.getSpecifiers(),
                                        req.getSearchPeriodicity(), newMax, true, newInc,
                                        req.getPlmns());
                                p.setResult(faster);
                                logOnce("scan request: " + max + "s -> " + newMax
                                        + "s, inc " + inc + "s -> " + newInc + "s");
                            } catch (Throwable ignored) {
                                // 设置进程不能因为这里出错而崩，最坏只是没加速
                            }
                        }
                    });
            n++;
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " hook scan request fail: " + t);
        }

        XposedBridge.log(MainHook.TAG + " scan helper hooked points=" + n);
    }

    private static void logOnce(String msg) {
        if (logged++ >= 3) return;
        XposedBridge.log(MainHook.TAG + " " + msg);
    }

    /**
     * @return 裁好的新请求；不需要裁剪时返回 null（调用方保持原参数）
     */
    private static NetworkScanRequest trim(NetworkScanRequest req) throws Exception {
        RadioAccessSpecifier[] specs = req.getSpecifiers();
        if (specs == null || specs.length == 0) {
            // 空集在框架里表示「扫全部制式、全部频段」，保持原样
            return null;
        }
        ArrayList<RadioAccessSpecifier> keep = new ArrayList<>(specs.length);
        for (RadioAccessSpecifier s : specs) {
            int ran = s.getRadioAccessNetwork();
            if (ran == RAN_EUTRAN || ran == RAN_NGRAN) {
                keep.add(s);
            }
        }
        if (keep.isEmpty() || keep.size() == specs.length) {
            return null;
        }
        RadioAccessSpecifier[] trimmed = keep.toArray(new RadioAccessSpecifier[0]);
        return new NetworkScanRequest(req.getScanType(), trimmed,
                req.getSearchPeriodicity(), req.getMaxSearchTime(),
                req.getIncrementalResults(), req.getIncrementalResultsPeriodicity(),
                req.getPlmns());
    }
}
