package com.fuli.data.okx;

import com.fuli.common.api.Result;
import com.fuli.data.security.OkxAuthz;
import com.fuli.data.security.OkxPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 凭证管理入口（ADR-0010 决策 1）。前端「系统设置 → OKX 账户」用这两条接口。
 *
 * <p><b>只操作自己的账户</b>：userId 一律来自过滤器写入的服务端 attribute，
 * 请求里没有、也不接受任何 userId 参数——这是「多账号隔离」的实现方式，
 * 不是在 SQL 里加个 where 就算隔离。
 *
 * <p>为什么不要求 {@code okx_trade_enabled}：录入 Key 是开通交易的前置动作，
 * 若也要求交易权限就形成死锁。写入敏感度由「必须本人令牌 + 审计」承担。
 *
 * <p>契约（给前端）：
 * <pre>
 * POST /api/data/okx/account
 *   {"label":"模拟盘-主账户","apiKey":"...","secretKey":"...","passphrase":"...","enabled":true}
 *   → {"code":200,"data":{"label":"模拟盘-主账户","apiKeyMasked":"abcd****wxyz","enabled":true,...}}
 * GET  /api/data/okx/account
 *   → [{"label":...,"apiKeyMasked":"abcd****wxyz","enabled":true,"keyVersion":1,...}]
 * </pre>
 * 响应里**永远没有** secretKey / passphrase 字段，前端不要试图拼出来。
 */
@RestController
@RequestMapping("/data/okx/account")
@RequiredArgsConstructor
public class OkxAccountController {

    private final OkxCredentialService credentialService;

    private final OkxAuthz authz;

    /** 录入或更新自己的凭证（按 (userId,label) upsert）。 */
    @PostMapping
    public ResponseEntity<Result<OkxAccountView>> save(HttpServletRequest request,
                                                       @RequestBody OkxAccountRequest body) {
        OkxPrincipal principal;
        try {
            // 审计里只出现 label，绝不出现 key/secret/passphrase
            principal = authz.requireCredential(request, "saveOkxAccount",
                    "label=" + safeLabel(body));
        } catch (ResponseStatusException e) {
            throw e;
        }
        try {
            OkxAccountView view = credentialService.save(principal.userId(), body);
            authz.auditResult("saveOkxAccount", "orders:credential", principal, "SUCCESS",
                    "label=" + view.label());
            return ResponseEntity.ok(Result.success(view));
        } catch (Exception e) {
            authz.auditResult("saveOkxAccount", "orders:credential", principal, "FAILED",
                    "err=" + e.getMessage());
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ResponseEntity.badRequest().body(Result.error(HttpStatus.BAD_REQUEST.value(), msg));
        }
    }

    /** 列出自己的凭证（已掩码）。 */
    @GetMapping
    public Result<List<OkxAccountView>> list(HttpServletRequest request) {
        OkxPrincipal principal = authz.requireCredential(request, "listOkxAccounts", "-");
        return Result.success(credentialService.list(principal.userId()));
    }

    private static String safeLabel(OkxAccountRequest body) {
        if (body == null || body.label() == null || body.label().isBlank()) {
            return "默认";
        }
        String cleaned = body.label().replaceAll("[\\r\\n\\t]", "_");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}
