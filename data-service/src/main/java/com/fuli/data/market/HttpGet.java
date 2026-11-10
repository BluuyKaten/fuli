package com.fuli.data.market;

import java.io.IOException;

/**
 * HTTP GET 抽象（返回字节）。数据源用它取数据，测试用假实现注入。
 */
@FunctionalInterface
public interface HttpGet {

    byte[] get(String url) throws IOException;
}
