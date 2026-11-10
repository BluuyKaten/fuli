package com.fuli.data.okx.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OKX 操作审计，对应 {@code data_db.okx_audit_log}（V11）。
 *
 * <p>与 {@code [OKX-AUDIT]} 结构化日志双写：日志用于当场排障，这张表用于事后追责。
 * 落表失败只 WARN，绝不影响主流程（见 {@code OkxAuditService}）。
 */
@Data
@TableName("okx_audit_log")
public class OkxAuditLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** NULL 表示服务账号（type=SERVICE）。 */
    private Long userId;

    private String action;

    /** orders:read / orders:write / orders:credential。 */
    private String scope;

    /** ALLOWED / DENIED / SUCCESS / FAILED。 */
    private String outcome;

    private String instId;

    private String symbol;

    private String side;

    private BigDecimal sz;

    private BigDecimal px;

    private String ordId;

    private String clOrdId;

    /** 已清洗的精简参数；禁止写入凭证与完整请求体。 */
    private String detail;

    private String traceId;

    private LocalDateTime createTime;
}
