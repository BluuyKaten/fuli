package com.fuli.auth.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class User implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    private String nickname;

    private String email;

    private String phone;

    private String avatar;

    private BigDecimal cash;

    private Integer status;

    /**
     * OKX 交易权限（ADR-0010 决策 2）：0-禁止（默认） 1-允许。
     *
     * <p>这是用户级开关，取代了原来的 {@code OKX_WRITE_USER_IDS} 环境变量白名单。
     * 它只是「允许写」的必要条件之一：还需要 {@code okx.trade-enabled=true} 这个全局总闸，
     * 以及本人在 data_db.okx_account 里录了凭证。
     */
    private Integer okxTradeEnabled;

    private LocalDateTime lastLoginTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
