package com.fuli.data.tushare;

import lombok.Data;
import java.util.List;

@Data
public class TushareResponse {

    private String code;

    private String msg;

    private TushareData data;

    @Data
    public static class TushareData {
        private List<String> fields;
        private List<List<Object>> items;
    }

    public boolean isSuccess() {
        return "0".equals(code);
    }
}
