package com.fuli.data.tushare;

import com.fuli.common.api.exception.BusinessException;
import com.fuli.data.config.TushareConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TushareClient {

    private final TushareConfig tushareConfig;
    private final RestTemplate restTemplate;

    public TushareClient(TushareConfig tushareConfig) {
        this.tushareConfig = tushareConfig;

        // 配置请求工厂(超时设置)
        ClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        ((SimpleClientHttpRequestFactory) factory).setConnectTimeout(tushareConfig.getTimeout());
        ((SimpleClientHttpRequestFactory) factory).setReadTimeout(tushareConfig.getTimeout());

        this.restTemplate = createRestTemplate(factory);
    }

    /**
     * 创建 RestTemplate 并配置消息转换器
     * <p>
     * 解决 Spring Boot 4.x 中 "converter is null" 的问题:
     * 显式添加 MappingJackson2HttpMessageConverter 确保 JSON 转换正常。
     * </p>
     */
    private RestTemplate createRestTemplate(ClientHttpRequestFactory factory) {
        RestTemplate restTemplate = new RestTemplate(factory);

        // 确保消息转换器列表包含 Jackson 转换器
        List<HttpMessageConverter<?>> converters = restTemplate.getMessageConverters();
        boolean hasJacksonConverter = converters.stream()
                .anyMatch(c -> c instanceof MappingJackson2HttpMessageConverter);

        if (!hasJacksonConverter) {
            // 在列表开头添加 Jackson 转换器,确保 JSON 转换优先级
            converters.add(0, new MappingJackson2HttpMessageConverter());
            restTemplate.setMessageConverters(converters);
        }

        return restTemplate;
    }

    public TushareResponse call(String apiName, Map<String, Object> params, String fields) {
        // 检查 Token 是否配置
        if (!tushareConfig.isTokenConfigured()) {
            throw new BusinessException("Tushare Token 未配置，请在环境变量或 application.yml 中设置 tushare.token");
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("api_name", apiName);
        requestBody.put("token", tushareConfig.getToken());
        if (params != null && !params.isEmpty()) {
            requestBody.put("params", params);
        }
        if (fields != null && !fields.isEmpty()) {
            requestBody.put("fields", fields);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        log.info("调用Tushare接口: {}, 参数: {}, URL: {}", apiName, params, tushareConfig.getApiUrl());

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    tushareConfig.getApiUrl(), request, Map.class);
            log.info("Tushare HTTP状态码: {}", response.getStatusCode());

            Map<String, Object> body = response.getBody();
            log.info("Tushare响应体: {}", body);

            TushareResponse tushareResponse = new TushareResponse();
            tushareResponse.setCode(String.valueOf(body.get("code")));
            tushareResponse.setMsg((String) body.get("msg"));

            Object dataObj = body.get("data");
            if (dataObj instanceof Map) {
                Map<String, Object> dataMap = (Map<String, Object>) dataObj;
                TushareResponse.TushareData tushareData = new TushareResponse.TushareData();
                tushareData.setFields((List<String>) dataMap.get("fields"));
                tushareData.setItems((List<List<Object>>) dataMap.get("items"));
                tushareResponse.setData(tushareData);
                log.info("Tushare返回字段: {}, 数据条数: {}", tushareData.getFields(), tushareData.getItems() != null ? tushareData.getItems().size() : 0);
            } else {
                log.warn("Tushare响应中data字段为null或不是Map类型: {}", dataObj);
            }

            if (!tushareResponse.isSuccess()) {
                log.error("Tushare接口返回错误: code={}, msg={}", tushareResponse.getCode(), tushareResponse.getMsg());
            }

            return tushareResponse;
        } catch (HttpClientErrorException e) {
            log.error("Tushare HTTP客户端错误: {}, 响应体: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Tushare HTTP错误: " + e.getStatusCode(), e);
        } catch (HttpServerErrorException e) {
            log.error("Tushare HTTP服务端错误: {}, 响应体: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Tushare HTTP错误: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            log.error("Tushare网络请求失败: {}", e.getMessage(), e);
            throw new RuntimeException("Tushare网络请求失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("调用Tushare接口失败: {}", apiName, e);
            throw new RuntimeException("调用Tushare接口失败: " + e.getMessage(), e);
        }
    }
}
