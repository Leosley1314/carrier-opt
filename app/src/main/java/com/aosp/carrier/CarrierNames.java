package com.aosp.carrier;

import java.util.Locale;

/**
 * 运营商名称汉化表。统一显示中文全称（中国移动 / 中国联通 / 中国电信 / 中国广电）。
 *
 * 只做「英文 -> 中文」映射，不认识的原样返回 null，调用方保持原值。
 */
public final class CarrierNames {

    public static final String CMCC = "中国移动";
    public static final String UNICOM = "中国联通";
    public static final String TELECOM = "中国电信";
    public static final String BROADNET = "中国广电";

    private CarrierNames() {
    }

    /**
     * @param raw 系统给的运营商名（可能是 China Mobile / CMCC / 46000 / 中文）
     * @return 中文全称；无法识别时返回 null
     */
    public static String localize(String raw) {
        return match(raw);
    }

    private static String match(String raw) {
        if (raw == null) {
            return null;
        }
        String n = normalize(raw);
        if (n.isEmpty()) {
            return null;
        }

        // 中文简称本身就写在 SIM 卡 SPN 里（「联通」「移动」很常见），类原生会原样显示，
        // 所以这里做一次精确补齐：只有整串就是简称时才补「中国」，不碰「联通沃派」这类长名
        if (n.equals("移动")) {
            return CMCC;
        }
        if (n.equals("联通")) {
            return UNICOM;
        }
        if (n.equals("电信")) {
            return TELECOM;
        }
        if (n.equals("广电")) {
            return BROADNET;
        }

        // 中国移动：46000 / 46002 / 46004 / 46007 / 46008
        if (n.contains("CHINAMOBILE") || n.equals("CMCC") || n.contains("中国移动")
                || n.startsWith("46000") || n.startsWith("46002")
                || n.startsWith("46004") || n.startsWith("46007") || n.startsWith("46008")) {
            return CMCC;
        }
        // 中国联通：46001 / 46006 / 46009
        if (n.contains("CHINAUNICOM") || n.contains("UNICOM") || n.equals("CU")
                || n.contains("中国联通")
                || n.startsWith("46001") || n.startsWith("46006") || n.startsWith("46009")) {
            return UNICOM;
        }
        // 中国电信：46003 / 46005 / 46011
        if (n.contains("CHINATELECOM") || n.contains("中国电信")
                || n.startsWith("46003") || n.startsWith("46005") || n.startsWith("46011")) {
            return TELECOM;
        }
        // 中国广电：46015
        if (n.contains("CHINABROADNET") || n.contains("BROADNET") || n.contains("中国广电")
                || n.startsWith("46015")) {
            return BROADNET;
        }
        return null;
    }

    /** 去掉空格、标点、大小写差异，顺便保留中文 */
    private static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || isCjk(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    private static boolean isCjk(char c) {
        return c >= 0x4e00 && c <= 0x9fff;
    }
}
