package com.aosp.carrier;

import java.util.concurrent.Callable;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import com.aosp.carrier.hooks.CarrierNameHook;
import com.aosp.carrier.hooks.CarrierTextHook;
import com.aosp.carrier.hooks.NetworkIconHook;
import com.aosp.carrier.hooks.NetworkLabelHook;
import com.aosp.carrier.hooks.ScanHook;

/**
 * 入口。按目标进程挂不同的 hook：
 *  - SystemUI ：网络类型标签（LTE->4G）、运营商名称（状态栏 + 锁屏）、锁屏双卡竖线
 *  - 设置     ：运营商名称、网络类型文字
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "CARRIER-OPT";

    public static final String PKG_SYSTEMUI = "com.android.systemui";
    public static final String PKG_SETTINGS = "com.android.settings";

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        String pkg = lp.packageName;

        if (PKG_SYSTEMUI.equals(pkg)) {
            XposedBridge.log(TAG + " hook systemui");
            StringBuilder ok = new StringBuilder();
            ok.append(run("4g", () -> {
                NetworkLabelHook.install(lp);
                return "";
            }));
            ok.append(run("name", () -> {
                CarrierNameHook.install(lp);
                return "";
            }));
            ok.append(run("sep", () -> {
                CarrierTextHook.install(lp);
                return "";
            }));
            ok.append(run("icon", () -> NetworkIconHook.install(lp)));
            Inject.installResponder(lp.classLoader, pkg, ok.toString());
            return;
        }
        if (PKG_SETTINGS.equals(pkg)) {
            XposedBridge.log(TAG + " hook settings");
            StringBuilder ok = new StringBuilder();
            ok.append(run("4g", () -> {
                NetworkLabelHook.install(lp);
                return "";
            }));
            ok.append(run("name", () -> {
                CarrierNameHook.install(lp);
                return "";
            }));
            ok.append(run("scan", () -> {
                ScanHook.install(lp);
                return "";
            }));
            Inject.installResponder(lp.classLoader, pkg, ok.toString());
            return;
        }
    }

    /** 装一个 hook，成功返回 "tag" 或 "tag=状态"，抛异常返回 "tag!"，异常只记日志不外抛，别拖垮整个进程 */
    private static String run(String tag, Callable<String> c) {
        try {
            String s = c.call();
            return tag + (s == null || s.isEmpty() ? "" : "=" + s) + ",";
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook " + tag + " fail: " + t);
            return tag + "!,";
        }
    }
}
