package com.aosp.carrier;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

/**
 * 跳进程配置通道：走系统数据库的 system 表。
 *
 * 为什么不用 XSharedPreferences：Android 10 之后 App 的 shared_prefs 目录是 700、文件 600，
 * 别的 uid（SystemUI / 设置 / 拨号）根本读不到，LSPosed 的 xposedsharedprefs 在这台机器上
 * 实测也没有把值带过去，「运营商用简称」打开了锁屏还是全称。
 * Settings.System 是 ContentProvider，注入进程读它不需要任何权限，改动还能立刻拿到通知，
 * 是目前最省事又一定生效的做法。写入端由模块 App 用 root 执行 {@code settings put}。
 */
public final class Sys {

    /** 系统表里的键前缀，避免和别的模块撞名 */
    public static final String PREFIX = "pe_l10n_";

    private static ContentResolver cr;
    /** SystemUI 里 currentApplication() 是 LSPosed 造的桩，拿不到 ContentResolver；
     *  只有 SystemUIService#onCreate 给的 Service Context 才是真的，先存下来。 */
    private static Context held;

    private Sys() {
    }

    /** 注入进程拿到真实 Context 后交给这里，之后所有读取都走它 */
    public static void hold(Context ctx) {
        if (ctx == null) return;
        try {
            Context app = ctx.getApplicationContext();
            held = app != null ? app : ctx;
            cr = held.getContentResolver();
        } catch (Throwable ignored) {
        }
    }

    private static ContentResolver resolver() {
        if (cr != null) {
            return cr;
        }
        if (held != null) {
            try {
                cr = held.getContentResolver();
                if (cr != null) return cr;
            } catch (Throwable ignored) {
            }
        }
        try {
            Application app = android.app.AndroidAppHelper.currentApplication();
            if (app != null) {
                cr = app.getContentResolver();
                return cr;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object app = de.robv.android.xposed.XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentApplication");
            if (app instanceof Application) {
                cr = ((Application) app).getContentResolver();
            }
        } catch (Throwable ignored) {
        }
        return cr;
    }

    /** 读不到 / 没写过一律返回 null，让调用方回退到默认值。 */
    public static String get(String key) {
        try {
            ContentResolver c = resolver();
            if (c == null) {
                return null;
            }
            return Settings.System.getString(c, PREFIX + key);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Integer getBool(String key) {
        String v = get(key);
        if (v == null || v.isEmpty()) {
            return null;
        }
        return "1".equals(v) || "true".equalsIgnoreCase(v) ? 1 : 0;
    }
}
