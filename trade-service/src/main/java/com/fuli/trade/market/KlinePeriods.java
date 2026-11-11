package com.fuli.trade.market;

import com.fuli.common.api.util.StockCodeUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K 线周期元数据（服务端下发，前端不再硬编码）。
 *
 * <p>为什么要挪到服务端：周期不是纯前端概念——「有哪些周期」取决于数据源能提供什么。
 * 前端硬编码列表时，后端加一个周期要改两处；后端某个市场不支持的周期，前端也无从知道，
 * 只能让用户点了看空白图。
 *
 * <p>当前东财对 A 股与美股都支持 1/5/15/30/60 分钟 + 日/周/月（实测，见 ADR-0013），
 * 所以两个市场下发的列表一样。仍然保留按市场分支：一旦某个市场缺分钟线，
 * 只改这一个类，前端自动跟着变。
 *
 * <p>加密标的走 {@code /market/bars}（ADR-0007 的统一契约），不在这条链路上，
 * 因此返回空列表而不是假装支持。
 */
public final class KlinePeriods {

    /**
     * 一条周期选项。
     *
     * <p>{@code value} 与前端既有取值保持一致（{@code 1}/.../{@code 1D}/{@code 1W}/{@code 1M}）；
     * {@code kind} 告诉前端该调哪个端点（{@code minute}/{@code day}/{@code week}/{@code month}）——
     * 前端原本靠 {@code ['1','5','15','60'].includes(period)} 猜，加一个周期就要改一处判断，
     * 猜漏了就是「点了没反应」。
     */
    public record Period(String value, String label, String kind) {}

    private static final List<Period> FULL = List.of(
            new Period("1", "1分", "minute"),
            new Period("5", "5分", "minute"),
            new Period("15", "15分", "minute"),
            new Period("30", "30分", "minute"),
            new Period("60", "60分", "minute"),
            new Period("1D", "日线", "day"),
            new Period("1W", "周线", "week"),
            new Period("1M", "月线", "month")
    );

    /** 数据时间口径：东财对 A 股与美股都返回北京时间（见 EastMoneyDataSource 类注释）。 */
    private static final String TIMEZONE = "Asia/Shanghai";

    private KlinePeriods() {}

    /** 按市场取可用周期；不支持的市场返回空列表。 */
    public static List<Period> of(String market) {
        return switch (market) {
            case "SH", "SZ", "BJ", "US" -> FULL;
            default -> List.of();
        };
    }

    /**
     * 组装下发结构。
     *
     * <p>带上 {@code timezone} 是为了让前端能如实标注时间轴口径——
     * 美股分钟线显示的是北京时间（22:30 开盘），不加标注用户会以为数据错了。
     */
    public static Map<String, Object> describe(String symbol) {
        String market = StockCodeUtil.marketOf(symbol);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("market", market);
        body.put("defaultPeriod", "1D");
        body.put("timezone", TIMEZONE);
        body.put("periods", of(market));
        return body;
    }
}
