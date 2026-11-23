package com.aosp.carrier;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 注入自检。
 *
 * 「模块没进 SystemUI」和「进了但 hook 没命中」在界面上长得一模一样，只能让注入进程
 * 自己报个信。做法：模块在 SystemUI 里注册一个应答广播，设置页发探测、收到回复才算
 * 已注入，回复里带上各 hook 的情况。整条链路不需要额外权限（不用写 Settings）。
 */
public final class Inject {

    public static final String ACTION_PING = "com.aosp.carrier.PING";
    public static final String ACTION_PONG = "com.aosp.carrier.PONG";
    public static final String ACTION_REFRESH = "com.aosp.carrier.REFRESH";
    public static final String EXTRA_DETAIL = "detail";

    private static String detail = "";
    private static boolean registered;

    private Inject() {
    }

    /** 在注入进程里调用：登记应答器 */
    public static void installResponder(ClassLoader cl, String pkg, final String what) {
        detail = pkg + ":" + what;
        // SystemUI 里不能直接用 currentApplication()：那是 LSPosed 造的桩 Application，
        // 上面注册广播会 NPE（ActivityThread 的 Instrumentation 为空）。
        // 等 SystemUI 的服务起来，用 Service 自己的 Context 注册，才是真环境。
        if ("com.android.systemui".equals(pkg)) {
            try {
                XposedHelpers.findAndHookMethod("com.android.systemui.SystemUIService", cl, "onCreate",
                        new de.robv.android.xposed.XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (param.thisObject instanceof Context) {
                                    Context c = (Context) param.thisObject;
                                    Sys.hold(c);
                                    register(c);
                                }
                            }
                        });
                XposedBridge.log(MainHook.TAG + " responder: wait for SystemUIService#onCreate");
                return;
            } catch (Throwable t) {
                XposedBridge.log(MainHook.TAG + " responder systemui hook fail: " + t);
            }
        }
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
            if (app instanceof Context) {
                register((Context) app);
                return;
            }
            XposedBridge.log(MainHook.TAG + " responder: wait for Application#onCreate");
            XposedHelpers.findAndHookMethod("android.app.Application", cl, "onCreate",
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Context) {
                                Context c = (Context) param.thisObject;
                                Sys.hold(c);
                                register(c);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " responder fail: " + t);
        }
    }

    /** 从模块界面发出去：让注入进程刷新一次图标（不需要 root，也不重启界面） */
    public static void requestRefresh(Context ctx) {
        try {
            Intent i = new Intent(ACTION_REFRESH);
            i.setPackage("com.android.systemui");
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " request refresh fail: " + t);
        }
    }

    private static synchronized void register(final Context ctx) {
        if (registered) return;
        registered = true;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent == null) return;
                if (ACTION_REFRESH.equals(intent.getAction())) {
                    // 开关刚被改过，让各卡的控制器立刻重算一遍图标
                    com.aosp.carrier.hooks.NetworkIconHook.refresh();
                    return;
                }
                if (!ACTION_PING.equals(intent.getAction())) return;
                Intent pong = new Intent(ACTION_PONG);
                pong.setPackage("com.aosp.carrier");
                pong.putExtra(EXTRA_DETAIL, detail);
                c.sendBroadcast(pong);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_PING);
        filter.addAction(ACTION_REFRESH);
        // Android 13 起动态注册必须带 RECEIVER_EXPORTED / NOT_EXPORTED，
        // 不带会抛 SecurityException，注册静默失败（之前自检一直「未生效」就是这个）。
        // SystemUI 是 targetSdk 33 的进程，这里必须显式给 EXPORTED。
        try {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            XposedBridge.log(MainHook.TAG + " responder ready (exported)");
            return;
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + " responder 4-arg fail: " + t);
        }
        try {
            ctx.registerReceiver(receiver, filter);
            XposedBridge.log(MainHook.TAG + " responder ready (2-arg)");
        } catch (Throwable t) {
            registered = false;
            XposedBridge.log(MainHook.TAG + " responder register fail: " + t);
        }
    }
}
