package com.aosp.carrier;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.Callable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.aosp.carrier.root.RootShell;

/**
 * 模块设置页。纯原生控件手写，不引 AndroidX。
 * 视觉上跟 PixelExperience 的设置页对齐：圆角卡片分组、行内开关、主色调蓝、深色模式自适应。
 */
public class SettingsActivity extends Activity {

    /** 只重启界面进程：改完显示类开关用它，不必重启整机 */
    private static final String RESTART_SYSTEMUI =
            "PID=$(pidof com.android.systemui)\n"
            + "if [ -n \"$PID\" ]; then kill -9 $PID; sleep 1; echo \"系统界面已重启（旧进程 $PID）\"\n"
            + "else echo '系统界面没有在运行'\nfi\n";

    private SharedPreferences sp;

    private Switch sw4g;
    private LinearLayout padRoot;
    private Switch swCarrier;

    /** 注入自检：设置页发探测广播，SystemUI 里模块的应答器回一条才算「已注入」 */
    private TextView injectView;
    private BroadcastReceiver pongReceiver;
    private boolean gotPong;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        sp = Prefs.local(this);
        reconcile();
        edgeToEdge();
        View content = buildUi();
        setContentView(content);
        applyBars();
        applyInsets(content, padRoot);
        applyIconVisibility(sp.getBoolean(Prefs.KEY_HIDE_ICON, false));
    }

    /**
     * 打开页面时以系统表为准，把开关显示回写成本地偏好。
     *
     * 系统表才是注入进程真正读的那份值（App 用 root 写进去）。如果只在本地写 sp，
     * 一旦某次 root 写失败，界面显示的和系统实际生效的就会分叉——用户看到「开关是关的、
     * 感叹号却没了」，然后点一下又没反应。这里对齐一次，界面永远显示真实状态。
     */
    private void reconcile() {
        String[] keys = {
                Prefs.KEY_NET_4G, Prefs.KEY_CARRIER_L10N, Prefs.KEY_CARRIER_SEP,
                Prefs.KEY_CARRIER_SEP_CHAR, Prefs.KEY_NO_BANG, Prefs.KEY_SCAN_FAST,
        };
        try {
            SharedPreferences.Editor e = sp.edit();
            for (String k : keys) {
                String v = android.provider.Settings.System.getString(
                        getContentResolver(), Sys.PREFIX + k);
                if (v == null) continue;
                e.putBoolean(k, "1".equals(v) || "true".equalsIgnoreCase(v));
            }
            e.commit();
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 配色 ----------------

    private boolean night() {
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private int cBg() {
        return night() ? 0xFF000000 : 0xFFF4F5F7;
    }

    private int cCard() {
        return night() ? 0xFF1B1B1F : 0xFFFFFFFF;
    }

    private int cText() {
        return night() ? 0xFFEDEDF0 : 0xFF111114;
    }

    private int cSub() {
        return night() ? 0x8CFFFFFF : 0x73000000;
    }

    private int cLine() {
        return night() ? 0x22FFFFFF : 0x14000000;
    }

    private int cAccent() {
        return 0xFF1A73E8;
    }

    // ---------------- 组装界面 ----------------

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg());
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        padRoot = root;
        int pad = dp(18);
        root.setPadding(pad, statusBarHeight() + dp(14), pad, dp(36));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        header(root);

        LinearLayout box = card(root, null);
        sw4g = row(box, "显示 4G", "状态栏、锁屏、设置里都显示 4G，载波聚合显示 4G+。",
                Prefs.KEY_NET_4G, true, null);
        swCarrier = row(box, "运营商中文化", "换成中国移动、联通、电信、广电。",
                Prefs.KEY_CARRIER_L10N, true, null);
        row(box, "锁屏双卡简化", "双卡时两个运营商名中间改用细垒线。",
                Prefs.KEY_CARRIER_SEP, true, null);
        row(box, "修改分隔符", "改用字符 |，方便对比效果。",
                Prefs.KEY_CARRIER_SEP_CHAR, false, null);
        row(box, "数据网络图标优化", "信号图标不再显示感叹号。缺点：公共 WiFi 登录页不再自动提示。",
                Prefs.KEY_NO_BANG, false,
                (checked) -> runRootQuiet(checked ? "已开启：不再显示感叹号" : "已关闭：感叹号会恢复",
                        () -> CaptivePortal.apply(this, checked)));
        row(box, "搜网加速", "手动搜网时边扫边出结果，只影响手动搜索。",
                Prefs.KEY_SCAN_FAST, true, null);
        row(box, "隐藏启动器图标", "仍然可以从 LSPosed 管理器打开。",
                Prefs.KEY_HIDE_ICON, false, (checked) -> {
                    applyIconVisibility(checked);
                    if (checked) {
                        toast("图标已隐藏，可从 LSPosed 管理器打开");
                    }
                });

        LinearLayout actions = card(root, null);
        actionRow(actions, "重启系统界面", () ->
                runRootQuiet("系统界面已重启", () -> RootShell.exec(this, RESTART_SYSTEMUI, 15)));

        disclaimer(root);

        return scroll;
    }

    // ---------------- 页面组件 ----------------

    private void header(LinearLayout root) {
        TextView title = new TextView(this);
        title.setText("运营商信息优化");
        title.setTextSize(27);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(cText());
        title.setPadding(0, 0, 0, dp(20));
        root.addView(title);
    }

    /** 一张圆角卡片，带一个小标题 */
    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        holder.setLayoutParams(lp);

        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextSize(12);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setTextColor(cSub());
            t.setLetterSpacing(0.06f);
            t.setPadding(dp(6), 0, 0, dp(8));
            holder.addView(t);
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard());
        bg.setCornerRadius(dp(20));
        bg.setStroke(1, cLine());
        box.setBackground(bg);
        box.setClipToOutline(true);
        box.setElevation(dp(1));
        holder.addView(box);

        parent.addView(holder);
        return box;
    }

    /** 卡片里的一行：左侧标题+说明，右侧开关 */
    private Switch row(LinearLayout card, String title, String desc,
                       final String key, boolean def, final OnToggle extra) {
        boolean first = card.getChildCount() == 0;
        if (!first) {
            View line = new View(this);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            llp.leftMargin = dp(18);
            llp.rightMargin = dp(18);
            line.setBackgroundColor(cLine());
            card.addView(line, llp);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(12), dp(14));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(tlp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(cText());
        texts.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(12);
        d.setTextColor(cSub());
        d.setPadding(0, dp(3), dp(8), 0);
        d.setLineSpacing(dp(2), 1f);
        texts.addView(d);
        row.addView(texts);

        Switch sw = new Switch(this);
        sw.setText("");
        if (Build.VERSION.SDK_INT >= 30) {
            sw.setShowText(false);
        }
        sw.setChecked(sp.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, checked) -> {
            sp.edit().putBoolean(key, checked).apply();
            ConfigSync.push(this);
            if (extra != null) {
                extra.onToggle(checked);
            }
        });
        row.addView(sw);

        card.addView(row);
        return sw;
    }

    /** 卡片里只有文字的一行：文字居中、整行可点，没有副标题也没有箭头 */
    private void actionRow(LinearLayout card, String title, Runnable onClick) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(18);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(cAccent());
        t.setGravity(android.view.Gravity.CENTER);
        int v = dp(17);
        t.setPadding(dp(18), v, dp(18), v);
        t.setClickable(true);
        t.setFocusable(true);
        try {
            t.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x1F1A73E8), null, null));
        } catch (Throwable ignored) {
        }
        t.setOnClickListener(v2 -> onClick.run());
        card.addView(t);
    }

    /** 页面最底下的免责声明，小字，不抢主内容也不进卡片 */
    private void disclaimer(LinearLayout root) {
        TextView t = new TextView(this);
        t.setText("免责声明：本工具仅在\n"
                + "PixelExperience_Plus_lmi-13.0-20230927-1336-OFFICIAL\n"
                + "测试通过.其他版本不保证可用.");
        t.setTextSize(11);
        t.setTextColor(cSub());
        t.setLineSpacing(dp(2), 1f);
        int p = dp(10);
        t.setPadding(p, dp(20), p, 0);
        root.addView(t);
    }

    interface OnToggle {
        void onToggle(boolean checked);
    }

    private Button filledButton(LinearLayout parent, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cAccent());
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.leftMargin = dp(14);
        lp.rightMargin = dp(14);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(6);
        parent.addView(b, lp);
        return b;
    }

    private Button textButton(LinearLayout parent, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(cAccent());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        lp.bottomMargin = dp(4);
        parent.addView(b, lp);
        return b;
    }

    private void runRoot(String title, final Callable<String> task) {
        // 不在点击时就弹「需要 root」提示，只有真的失败才由异常文案说明
        new Thread(() -> {
            try {
                String out = task.call();
                runOnUiThread(() -> showOutput(title, out));
            } catch (Throwable t) {
                final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                runOnUiThread(() -> toast(msg));
            }
        }).start();
    }

    /** 成功只提示一行，不弹模态框；失败才弹窗说明原因 */
    private void runRootQuiet(final String done, final Callable<String> task) {
        new Thread(() -> {
            try {
                String out = task.call();
                runOnUiThread(() -> toast(done));
                XLog(out);
            } catch (Throwable t) {
                final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                runOnUiThread(() -> showOutput("网络检测失败", msg));
            }
        }).start();
    }

    private static void XLog(String s) {
        if (s == null || s.isEmpty()) return;
        android.util.Log.d("CARRIER-OPT", s);
    }

    private void showOutput(String title, String out) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(TextUtils.isEmpty(out) ? "已完成，没有输出。" : out)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 桌面图标的显示/隐藏：开关 Manifest 里的 .Launcher alias */
    private void applyIconVisibility(boolean hidden) {
        try {
            ComponentName cn = new ComponentName(getPackageName(), getPackageName() + ".Launcher");
            getPackageManager().setComponentEnabledSetting(cn,
                    hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            toast("切换桌面图标失败：" + t);
        }
    }

    /**
     * 让内容画到状态栏/导航栏下面，这样两栏的底色跟页面一致，
     * 而不是系统默认的黑色条。
     */
    private void edgeToEdge() {
        Window w = getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) {
            w.setNavigationBarDividerColor(Color.TRANSPARENT);
        }
        w.getDecorView().setBackgroundColor(cBg());
    }

    /** 浅色页面用深色图标，深色页面用浅色图标；否则白字压白底会看不见 */
    private void applyBars() {
        boolean darkIcons = !night();
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(darkIcons ? mask : 0, mask);
            }
        } else {
            int flags = darkIcons ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
            if (Build.VERSION.SDK_INT >= 26 && darkIcons) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    /** 系统栏高度从 insets 取，取不到再退回 dimen，保证任何 ROM 上都不被遮挡 */
    private void applyInsets(View content, final LinearLayout root) {
        final int pad = dp(18);
        content.setOnApplyWindowInsetsListener((v, ins) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets st = ins.getInsets(WindowInsets.Type.statusBars());
                Insets nv = ins.getInsets(WindowInsets.Type.navigationBars());
                top = st.top;
                bottom = nv.bottom;
            } else {
                top = ins.getSystemWindowInsetTop();
                bottom = ins.getSystemWindowInsetBottom();
            }
            if (top <= 0) {
                top = statusBarHeight();
            }
            root.setPadding(pad, top + dp(14), pad, bottom + dp(20));
            return ins;
        });
        content.requestApplyInsets();
    }

    /** 状态栏高度，取系统的 dimen；取不到就按 24dp 兜底 */
    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            return getResources().getDimensionPixelSize(id);
        }
        return dp(24);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
