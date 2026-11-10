package com.fuli.data.okx;

import com.fuli.common.api.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间接口：ai-service 把 Agent 建议单落到 data-service（ADR-0010 决策 4）。
 *
 * <p>为什么不让 ai-service 直连 data_db：{@code okx_order_suggestion} 属于加密域，
 * 归属 data-service。多一个写库的服务，就多一处「谁改了这条数据」说不清的地方。
 *
 * <p>为什么不用 service token 而用内部密钥：service token 的 scope 被刻意限制在
 * {@code orders:read}，一旦给它加写 scope，「Agent 只读」这条边界就开始松动了。
 * 内部密钥是服务身份的凭据，路径也已被网关拦截，语义更清楚。
 *
 * <p>路径不在 {@code /data/okx/**} 下，因此不经过 JWT 过滤器；由
 * {@link com.fuli.data.security.OkxInternalKeyFilter} 校验 {@code X-Internal-Key}。
 */
@Slf4j
@RestController
@RequestMapping("/internal/okx")
@RequiredArgsConstructor
public class OkxInternalSuggestionController {

    private final OkxSuggestionService suggestionService;

    @PostMapping("/suggestion")
    public ResponseEntity<Result<OkxSuggestionView>> submit(@RequestBody OkxSuggestionRequest request) {
        try {
            OkxSuggestionView view = suggestionService.submit(request);
            return ResponseEntity.ok(Result.success(view));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[OKX-SUGGEST] 建议单被拒：{}", msg);
            return ResponseEntity.badRequest()
                    .body(Result.error(HttpStatus.BAD_REQUEST.value(), msg));
        }
    }
}
