package com.fuli.data.okx;

import com.fuli.common.api.Result;
import com.fuli.data.security.OkxAuthz;
import com.fuli.data.security.OkxPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 查询自己的 Agent 建议单（只读）。
 *
 * <p>路径在 {@code /data/okx/**} 下，因此走 JWT 过滤器；userId 取服务端 attribute，
 * 不接受 {@code ?userId=}——否则谁能看到谁的建议就取决于参数了。
 */
@RestController
@RequestMapping("/data/okx/suggestions")
@RequiredArgsConstructor
public class OkxSuggestionController {

    private final OkxSuggestionService suggestionService;

    private final OkxAuthz authz;

    @GetMapping
    public Result<List<OkxSuggestionView>> recent(HttpServletRequest request,
                                                  @RequestParam(value = "limit", required = false) Integer limit) {
        OkxPrincipal principal = authz.requireRead(request, "listSuggestions");
        if (principal.isService()) {
            // 服务账号读不到任何人的建议单；给它空列表而不是 403，避免 Agent 编排因一次查询失败中断
            return Result.success(List.of());
        }
        return Result.success(suggestionService.recent(principal.userId(), limit == null ? 20 : limit));
    }
}
