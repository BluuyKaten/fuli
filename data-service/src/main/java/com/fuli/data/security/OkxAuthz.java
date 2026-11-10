package com.fuli.data.security;

import com.fuli.data.config.OkxAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

/**
 * OKX 读写 scope 分级 + 审计（ADR-0009 决策 8 / ADR-0010 决策 2、3）。
 *
 * <table>
 *   <caption>三道门</caption>
 *   <tr><th>scope</th><th>要求</th></tr>
 *   <tr><td>{@code orders:read}</td><td>有效令牌；服务账号需带 {@code orders:read}</td></tr>
 *   <tr><td>{@code orders:write}</td><td>用户令牌 + {@code sys_user.okx_trade_enabled=1}；
 *       服务账号一律 403</td></tr>
 *   <tr><td>{@code orders:credential}</td><td>用户令牌即可——先配 Key 才谈得上开权限，
 *       若也要求交易权限就成了死锁</td></tr>
 * </table>
 *
 * <p>所有判定（放行与拒绝）都写审计，且审计失败不影响判定结果。
 */
@Component
@RequiredArgsConstructor
public class OkxAuthz {

    private final OkxTradePermissionClient permission;

    private final OkxAuditService audit;

    private final OkxAuthProperties properties;

    /** 读 scope：用户恒可；服务账号必须有 {@code orders:read}。 */
    public OkxPrincipal requireRead(HttpServletRequest request, String action) {
        OkxPrincipal principal = OkxPrincipal.require(request);
        if (!principal.canRead()) {
            deny(action, OkxPrincipal.SCOPE_READ, principal, "缺少 orders:read scope", null);
        }
        audit.record(OkxAuditService.Event.of(action, OkxPrincipal.SCOPE_READ, "ALLOWED",
                principal.auditUserId(), "principal=" + principal.display()));
        return principal;
    }

    /** 写 scope：用户令牌 + 交易权限开关；服务账号永远拒绝。 */
    public OkxPrincipal requireWrite(HttpServletRequest request, String action, String detail) {
        OkxPrincipal principal = OkxPrincipal.require(request);
        if (principal.isService()) {
            // Agent 只读是协议级约束：即使拿到 service token 也调不动写端点
            deny(action, OkxPrincipal.SCOPE_WRITE, principal, "服务账号不允许执行写操作", detail);
        }
        if (!permission.isTradeEnabled(principal.userId())) {
            deny(action, OkxPrincipal.SCOPE_WRITE, principal,
                    "当前用户未被授予 OKX 交易权限（sys_user.okx_trade_enabled=0）", detail);
        }
        audit.record(OkxAuditService.Event.of(action, OkxPrincipal.SCOPE_WRITE, "ALLOWED",
                principal.auditUserId(), detail));
        return principal;
    }

    /**
     * 凭证 scope：只要求本人身份（读自己的账户列表、写入/更新自己的 Key 都走这里）。
     *
     * <p>刻意**不**要求 {@code okx_trade_enabled}：用户需要先录入 Key，管理员才可能给他开权限。
     * 这里也拒绝服务账号——Agent 永远不该碰别人的 API Key。
     */
    public OkxPrincipal requireCredential(HttpServletRequest request, String action, String detail) {
        OkxPrincipal principal = OkxPrincipal.require(request);
        if (principal.isService()) {
            deny(action, "orders:credential", principal, "服务账号不允许访问凭证接口", detail);
        }
        audit.record(OkxAuditService.Event.of(action, "orders:credential", "ALLOWED",
                principal.auditUserId(), detail));
        return principal;
    }

    /** 业务执行结果审计（成功/失败）。detail 只放精简业务参数，绝不放凭证或完整请求体。 */
    public void auditResult(String action, OkxPrincipal principal, String outcome, String detail) {
        auditResult(action, OkxPrincipal.SCOPE_WRITE, principal, outcome, detail);
    }

    /**
     * 带 scope 的结果审计。
     *
     * <p>为什么不能一律记 {@code orders:write}：凭证录入的 scope 是 {@code orders:credential}，
     * 记成 write 会让「谁改过 Key」这类查询混进交易审计里，事后按 scope 统计就失真了。
     */
    public void auditResult(String action, String scope, OkxPrincipal principal,
                            String outcome, String detail) {
        audit.record(OkxAuditService.Event.of(action, scope, outcome,
                principal == null ? null : principal.auditUserId(), detail));
    }

    /**
     * 带交易字段的结果审计：让 {@code okx_audit_log} 的 inst_id / side / sz / px / ord_id
     * 真正有值，事后能按标的与订单号直接检索，而不是只能靠 detail 里的一串文本正则。
     */
    public void auditTradeResult(String action, OkxPrincipal principal, String outcome,
                                 String instId, String symbol, String side,
                                 BigDecimal sz, BigDecimal px, String ordId, String clOrdId,
                                 String detail) {
        audit.record(new OkxAuditService.Event(action, OkxPrincipal.SCOPE_WRITE, outcome,
                principal == null ? null : principal.auditUserId(),
                instId, symbol, side, sz, px, ordId, clOrdId, detail));
    }

    /** 未认证/无权限的审计（读路径失败时用，没有 principal 也要留痕）。 */
    public void auditReadFailure(String action, String outcome, String detail) {
        audit.record(OkxAuditService.Event.of(action, OkxPrincipal.SCOPE_READ, outcome, null, detail));
    }

    private void deny(String action, String scope, OkxPrincipal principal, String reason,
                      String detail) {
        String suffix = detail == null || detail.isBlank() || "-".equals(detail) ? "" : " detail=" + detail;
        audit.record(OkxAuditService.Event.of(action, scope, "DENIED", principal.auditUserId(),
                reason + " principal=" + principal.display()
                        + " audience=" + properties.getServiceTokenAudience() + suffix));
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }
}
