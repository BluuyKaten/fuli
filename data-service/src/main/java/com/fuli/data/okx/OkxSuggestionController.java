package com.fuli.data.okx;

import com.fuli.common.api.Result;
import com.fuli.data.security.OkxAuthz;
import com.fuli.data.security.OkxPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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

    private final OkxSuggestionExecutionService executionService;

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

    /**
     * 人工确认一条建议单并转真实模拟盘下单（阶段 3.3）。
     *
     * <p>写操作：要求用户级交易权限（{@code requireWrite}），服务账号硬 403。
     * 交易所明确拒单、凭证或风控失败会回到 {@code new}；网络结果不确定时保留 {@code executing}，等待核实。
     */
    @PostMapping("/{id}/execute")
    public Result<Map<String, Object>> execute(HttpServletRequest request, @PathVariable("id") long id) {
        try {
            OkxPrincipal principal = authz.requireWrite(request, "executeSuggestion", "suggestionId=" + id);
            return Result.success(executionService.execute(principal.userId(), id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 人工否决一条建议单（不产生任何订单，只把状态推进到 {@code rejected}）。
     *
     * <p>同样要求写权限：它改变的是「这条建议的处置结果」，与下单属于同一类操作。
     * 没有这个入口时，不打算执行的建议会永远挂在 {@code new}，「待确认」清不掉。
     */
    @PostMapping("/{id}/reject")
    public Result<Map<String, Object>> reject(HttpServletRequest request, @PathVariable("id") long id) {
        try {
            OkxPrincipal principal = authz.requireWrite(request, "rejectSuggestion", "suggestionId=" + id);
            return Result.success(executionService.reject(principal.userId(), id));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }
}
