package com.fuli.data.market;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * JDK 原生 HttpClient 封装的 GET，支持可选代理（国内访问 Binance Vision / Gate 需要）。
 *
 * <p>代理格式兼容 {@code 127.0.0.1:7897} 与 {@code http://127.0.0.1:7897} 两种写法。
 */
@Slf4j
@Component
public class CryptoHttpClient implements HttpGet {

    private final HttpClient client;

    public CryptoHttpClient(CryptoMarketProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (properties.getProxy() != null && !properties.getProxy().isBlank()) {
            builder.proxy(ProxySelector.of(parseProxy(properties.getProxy())));
        }
        this.client = builder.build();
    }

    @Override
    public byte[] get(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "*/*")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断: " + url, e);
        }
    }

    static InetSocketAddress parseProxy(String proxy) {
        String s = proxy.trim();
        if (s.startsWith("http://")) {
            s = s.substring("http://".length());
        } else if (s.startsWith("https://")) {
            s = s.substring("https://".length());
        }
        int colon = s.lastIndexOf(':');
        if (colon <= 0 || colon == s.length() - 1) {
            throw new IllegalArgumentException("非法代理地址（应为 host:port）: " + proxy);
        }
        String host = s.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(s.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法代理端口: " + proxy, e);
        }
        return new InetSocketAddress(host, port);
    }
}
