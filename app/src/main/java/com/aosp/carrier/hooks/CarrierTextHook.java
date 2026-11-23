package com.aosp.carrier.hooks;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import com.aosp.carrier.MainHook;
import com.aosp.carrier.Prefs;

/**
 * 锁屏运营商文本的分隔符：类原生是两个运营商名中间一条横线 "中国电信 — 中国联通"，
 * 按小米的做法改成垒线 "中国电信 | 中国联通"。
 *
 * 这段文本不是读 string 资源，而是运行时拼好的，所以在 TextView#setText 之前改参数。
 * 只在文本里同时出现两个运营商名时才动，避免误伤通知标题里的横线。
 */
public final class CarrierTextHook {

    /**
     * 全称 + 简称 + 英文两套。英文这套是给「关掉运营商汉化」的情况兜底的：
     * 汉化关掉时锁屏是 "China Mobile — China Unicom"，只认中文名的话垒线不会生效。
     * 英文只收完整名称和 CMCC 这类不会撞车的缩写，两个字母的 CU/CT 不收，
     * 免得把 "ACTION"、"CT" 之类正文误判成运营商。
     */
    private static final String[] OPS = {
            "中国电信", "中国移动", "中国联通", "中国广电", "中国铁通", "中国卫通", "中国星网",
            "电信", "移动", "联通", "广电",
            "China Telecom", "China Mobile", "China Unicom", "China Broadnet",
            "CMCC", "CHINA MOBILE", "CHINA TELECOM", "CHINA UNICOM",
    };

    /** 开关缓存：TextView#setText 是 SystemUI 最热的调用之一，不能每次都去读配置 */
    private static long lastCheck;
    private static boolean on = true;
    private static boolean charMode;
    private static final long CHECK_TTL_MS = 3000L;

    private static void reload() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCheck < CHECK_TTL_MS) {
            return;
        }
        lastCheck = now;
        try {
            on = Prefs.getBool(Prefs.KEY_CARRIER_SEP, true);
            charMode = Prefs.getBool(Prefs.KEY_CARRIER_SEP_CHAR, false);
        } catch (Throwable t) {
            on = true;
            charMode = false;
        }
    }

    private static boolean enabled() {
        reload();
        return on;
    }

    private static boolean useChar() {
        reload();
        return charMode;
    }

    /** 运营商名后面允许被替换的"纯符号/空格"段，长度上限 */
    private static final int MAX_MID = 6;

    /**
     * 运营商名后面那一段：纯符号（破折号、点、斜杠）夹着一个可选的网络标记。
     * 例："— "、"— — "、" 4G • "、" | "。
     */
    private static final java.util.regex.Pattern MID = java.util.regex.Pattern.compile(
            "[^\\p{L}\\p{N}]{0,4}([2-5]G|LTE\\+?|NR|HD)?[^\\p{L}\\p{N}]{0,4}");

    /** 常见状态词，命中即替换（比通用规则更早命中，省一次判断） */
    private static final String[] TAILS = {
            // 长的放在前面：known() 用 startsWith 判断，"只能拨打紧急呼救电话" 必须能被整体命中
            "只能拨打紧急呼救电话", "只能拨打紧急电话", "仅能拨打紧急呼救电话",
            "仅可拨打紧急电话", "仅限紧急呼叫", "只能使用紧急呼叫",
            "没有服务", "无服务", "没有信号", "无信号", "无网络",
            "正在搜索网络", "正在查找网络", "正在搜索", "正在搜寻",
            "无SIM卡", "无 SIM 卡", "无卡", "飞行模式", "已关闭",
            "VoLTE", "HD", "5G", "4G", "3G", "2G",
            "No service", "Emergency calls only", "Searching", "No SIM",
    };

    private CarrierTextHook() {
    }

    public static void install(LoadPackageParam lp) {
        boolean any = false;
        any |= hook("setText", CharSequence.class);
        any |= hook("setText", CharSequence.class, TextView.BufferType.class);
        any |= hook("setText", CharSequence.class, TextView.BufferType.class,
                boolean.class, int.class);
        if (any) {
            XposedBridge.log(MainHook.TAG + " hooked TextView#setText for carrier separator");
        } else {
            XposedBridge.log(MainHook.TAG + " hook TextView#setText failed");
        }
    }

    private static boolean hook(String method, Class<?>... types) {
        try {
            java.lang.reflect.Method target = TextView.class.getDeclaredMethod(method, types);
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // SystemUI 的每一次 setText 都会走到这里，任何异常外泄都可能拖姓
                    // 整个界面进程（表现出来就是状态栏消失、锁屏重来、甚至反复重启），
                    // 所以整段逻辑一律吞掉异常，最坏情况只是这一条文本没被替换。
                    try {
                        if (!enabled()) {
                            return;
                        }
                        if (param.args.length < 1 || !(param.args[0] instanceof CharSequence)) {
                            return;
                        }
                        CharSequence cs = (CharSequence) param.args[0];
                        String s = cs.toString();
                        if (s == null || s.length() > 40) {
                            return;
                        }
                        int ops = 0;
                        for (String op : OPS) {
                            if (s.contains(op) && ++ops >= 2) {
                                break;
                            }
                        }
                        // 两个名字（双卡）随便改；只有 1 个名字时只认很短的串，
                        // 也就是锁屏 "电信 — 没有服务" 这种，避免动到通知正文。
                        // 另外「没有服务 — 只能拨打紧急呼救电话 — Club」这种开头就是状态词的
                        // 长文本（两张卡都没服务时 SystemUI 会拼成这样）也要放行。
                        boolean headState = ops == 0 && known(s, 0);
                        if (ops < 2 && !headState && (ops == 0 || s.length() > 12)) {
                            return;
                        }
                        TextView tv = param.thisObject instanceof TextView
                                ? (TextView) param.thisObject : null;
                        CharSequence t = fix(s, tv, useChar());
                        if (t != null) {
                            param.args[0] = t;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把「运营商A <任意符号> 运营商B」中间的符号段整体换成一个垒线。
     * 不依赖具体分隔符字符（横线有 em/en/全角/连字符/多个叠加等很多变体），
     * 只要两个运营商名之间是纯符号或空格，就一律统一。
     *
     * 返回 null 表示不需要改；返回 String 是纯字符垒线（兼容模式），
     * 返回 Spannable 是把 '|' 换成 {@link BarSpan} 画出来的细垒线（默认）。
     */
    private static CharSequence fix(String s, TextView tv, boolean asChar) {
        // 带冒号/逗号的基本是通知摘要（"中国移动：您的余额…"），不是锁屏运营商行，直接放过
        if (s.indexOf('：') >= 0 || s.indexOf(':') >= 0 || s.indexOf('，') >= 0
                || s.indexOf(',') >= 0 || s.indexOf('。') >= 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        int len = s.length();
        boolean changed = false;
        while (pos < len) {
            int bestStart = -1;
            int bestEnd = -1;
            // 1) 先认 pos 处的状态词（「没有服务」「只能拨打紧急电话」…）。
            //    必须在扫运营商名之前判：否则「中国电信 — 只能拨打紧急呼救电话 — 中国联通」
            //    会先跳到后面的运营商名，把中间那道横线漏掉。
            for (String t : TAILS) {
                if (t.length() >= 3 && s.startsWith(t, pos)) {
                    bestStart = pos;
                    bestEnd = pos + t.length();
                    break;
                }
            }
            // 2) 找最近的运营商名
            if (bestStart < 0) {
                for (String op : OPS) {
                    int i = s.indexOf(op, pos);
                    if (i >= 0 && (bestStart < 0 || i < bestStart)) {
                        bestStart = i;
                        bestEnd = i + op.length();
                    }
                }
            }
            if (bestStart < 0) {
                sb.append(s, pos, len);
                break;
            }
            sb.append(s, pos, bestEnd);

            // 运营商名后面这一段：符号 + 可选的网络标记（4G/5G/LTE/HD）+ 符号
            java.util.regex.Matcher m = MID.matcher(s);
            m.region(bestEnd, Math.min(len, bestEnd + 16));
            int j = bestEnd;
            String net = "";
            if (m.lookingAt()) {
                String mid = m.group();
                boolean hasSymbol = false;
                for (int k = 0; k < mid.length(); k++) {
                    char c = mid.charAt(k);
                    if (!Character.isWhitespace(c) && !Character.isLetterOrDigit(c)) {
                        hasSymbol = true;
                        break;
                    }
                }
                if (hasSymbol) {
                    j = m.end();
                    net = m.group(1) == null ? "" : m.group(1);
                }
            }
            if (j > bestEnd) {
                boolean after = j < len && (known(s, j) || stateLike(s, j));
                if (after) {
                    sb.append(net.isEmpty() ? "|" : " " + net + "|");
                    changed = true;
                    pos = j;
                } else if (j >= len && !net.isEmpty()) {
                    // 只有「中国电信 — 5G」这种尾巴，横线去掉、保留网络标记
                    sb.append(" ").append(net);
                    changed = true;
                    pos = j;
                } else {
                    pos = bestEnd;
                }
            } else {
                pos = bestEnd;
            }
        }
        if (!changed) {
            return null;
        }
        String out = sb.toString();
        if (asChar || tv == null) {
            // 兼容模式：保留两侧空格，纯字符 | 即可
            return out.replace("|", " | ");
        }
        return withBars(out, tv);
    }

    /** 把每个 '|' 换成一条画出来的细垒线：1dp 宽、左右各 4dp 留白，颜色跟文字色走 */
    private static CharSequence withBars(String out, TextView tv) {
        SpannableString span = new SpannableString(out);
        int width = Math.max(1, (int) (1f * tv.getResources().getDisplayMetrics().density));
        int pad = Math.max(2, (int) (4f * tv.getResources().getDisplayMetrics().density));
        for (int i = 0; i < out.length(); i++) {
            if (out.charAt(i) == '|') {
                span.setSpan(new BarSpan(width, pad), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return span;
    }

    /**
     * 用 ReplacementSpan 画垒线。替代字符的好处：粗细与上下留白固定（不随字体变），
     * 高度按字体度量取 60%，颜色直接用 TextView 传进来的 paint，浅色/深色主题都能自适应。
     */
    private static final class BarSpan extends ReplacementSpan {

        private final int width;
        private final int pad;

        BarSpan(int width, int pad) {
            this.width = width;
            this.pad = pad;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            return pad * 2 + width;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            try {
                Paint.FontMetricsInt fm = paint.getFontMetricsInt();
                float ascent = y + fm.ascent;
                float descent = y + fm.descent;
                float h = (descent - ascent) * 0.60f;
                float cy = (ascent + descent) / 2f;

                Paint bar = new Paint();
                bar.setColor(paint.getColor());
                bar.setStyle(Paint.Style.FILL);
                bar.setAntiAlias(true);
                float cx = x + pad;
                canvas.drawRect(cx, cy - h / 2f, cx + width, cy + h / 2f, bar);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 兜底：破折号后面是一小段没有标点的短语，就当作状态词
     * （"— 没有服务" 这类系统文案每个版本叫法不一样，枚举不全）。
     * 通知类文本（"中国移动：您的余额…"）带冒号且更长，不会命中。
     */
    private static boolean stateLike(String s, int from) {
        String rest = s.substring(from).trim();
        if (rest.isEmpty() || rest.length() > 10) {
            return false;
        }
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '：' || c == ':' || c == '，' || c == ',' || c == '。' || c == '.'
                    || c == '\n' || c == '\r' || c == '（' || c == '(') {
                return false;
            }
        }
        return true;
    }

    /** 后面紧跟另一个运营商名或已知状态词，才认定前面那段是分隔符 */
    private static boolean known(String s, int from) {
        for (String op : OPS) {
            if (s.startsWith(op, from)) {
                return true;
            }
        }
        for (String t : TAILS) {
            if (s.startsWith(t, from)) {
                return true;
            }
        }
        return false;
    }
}
