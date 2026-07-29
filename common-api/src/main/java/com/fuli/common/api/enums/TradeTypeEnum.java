package com.fuli.common.api.enums;

import lombok.Getter;

@Getter
public enum TradeTypeEnum {

    BUY(1, "买入"),
    SELL(2, "卖出");

    private final Integer code;
    private final String name;

    TradeTypeEnum(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    public static TradeTypeEnum of(Integer code) {
        for (TradeTypeEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        return null;
    }
}
