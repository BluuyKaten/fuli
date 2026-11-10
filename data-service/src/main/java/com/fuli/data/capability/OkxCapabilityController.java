package com.fuli.data.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuli.common.api.Result;
import com.fuli.data.okx.OkxAccountIdentity;
import com.fuli.data.okx.OkxJson;
import com.fuli.data.security.OkxAuthz;
import com.fuli.data.security.OkxPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P6：七模块能力的统一 HTTP 入口。
 *
 * <p>写能力必须过 {@code OkxAuthz.requireWrite}（JWT + 白名单），读能力过 requireRead；
 * 401/403 在业务 try 之外处理，不能被吞成 400。所有调用留 {@code [OKX-AUDIT]}。
 */
@RestController
@RequestMapping("/data/okx/capability")
@RequiredArgsConstructor
public class OkxCapabilityController {

    private final OkxCapabilityService capabilityService;

    private final OkxAccountIdentity accountIdentity;

    private final OkxAuthz authz;

    @GetMapping("/modules")
    public Result<Map<String, Object>> modules(HttpServletRequest request) {
        authz.requireRead(request, "capabilityModules");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modules", OkxModuleCatalog.MODULES);
        out.put("count", OkxModuleCatalog.ALL.size());
        out.put("capabilities", OkxModuleCatalog.ALL.stream()
                .map(c -> Map.of("name", c.name(), "module", c.module(),
                        "method", c.method(), "path", c.path(), "write", c.write()))
                .toList());
        return Result.success(out);
    }

    @PostMapping("/{name}")
    public ResponseEntity<Result<Object>> execute(HttpServletRequest request,
                                                    @PathVariable String name,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        OkxModuleCatalog.Capability cap;
        OkxPrincipal principal;
        try {
            cap = OkxModuleCatalog.require(name);
            principal = cap.write()
                    ? authz.requireWrite(request, "capability." + name, "instId=" + get(body, "instId"))
                    : authz.requireRead(request, "capability." + name);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }

        try {
            // 服务账号没有自己的账户：签名类能力要显式代理一个用户，否则这里会给出可操作的 400
            Long accountUserId = (cap.write() || cap.signed())
                    ? accountIdentity.requireAccountUserId(principal) : null;
            JsonNode data = capabilityService.execute(name, queryOf(body), body, accountUserId);
            authz.auditResult("capability." + name, cap.write() ? OkxPrincipal.SCOPE_WRITE : OkxPrincipal.SCOPE_READ,
                    principal, "SUCCESS", "module=" + cap.module());
            return ResponseEntity.ok(Result.success(OkxJson.toPlain(data)));
        } catch (Exception e) {
            authz.auditResult("capability." + name, cap.write() ? OkxPrincipal.SCOPE_WRITE : OkxPrincipal.SCOPE_READ,
                    principal, "FAILED", e.getMessage());
            return ResponseEntity.badRequest().body(Result.error(HttpStatus.BAD_REQUEST.value(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static Map<String, String> queryOf(Map<String, Object> body) {
        Map<String, String> q = new LinkedHashMap<>();
        if (body != null) {
            body.forEach((k, v) -> {
                if (v != null) {
                    q.put(k, v.toString());
                }
            });
        }
        return q;
    }

    private static String get(Map<String, Object> body, String key) {
        return body == null || body.get(key) == null ? "" : body.get(key).toString();
    }
}
