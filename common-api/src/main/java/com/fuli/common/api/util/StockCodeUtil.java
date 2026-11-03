package com.fuli.common.api.util;

/**
 * 股票代码工具：裸 6 位代码补全交易所后缀。
 *
 * <p>A 股代码段精确判断（按前三位），避免只按首位误判：
 * 北交所 2024 年起启用的 920xxx 段会被"9 开头"误判为沪市，这里单独识别。
 */
public final class StockCodeUtil {

    private StockCodeUtil() {}

    /**
     * 裸 6 位代码补全交易所后缀；非 6 位、已带后缀或无法识别则原样返回。
     *
     * <ul>
     *   <li>北交所：920 / 43 / 83 / 87 / 4 / 8 开头 → .BJ</li>
     *   <li>沪市：60 / 68 / 900(沪B) 开头 → .SH</li>
     *   <li>深市：00 / 30 / 20(深B) 开头 → .SZ</li>
     * </ul>
     */
    public static String withSuffix(String code) {
        if (code == null) {
            return "";
        }
        String c = code.trim();
        if (c.length() != 6 || c.contains(".")) {
            return c;
        }
        if (c.startsWith("920") || c.startsWith("43") || c.startsWith("83") || c.startsWith("87")
                || c.charAt(0) == '4' || c.charAt(0) == '8') {
            return c + ".BJ";
        }
        if (c.startsWith("60") || c.startsWith("68") || c.startsWith("900")) {
            return c + ".SH";
        }
        if (c.startsWith("00") || c.startsWith("30") || c.startsWith("20")) {
            return c + ".SZ";
        }
        return c;
    }
}
