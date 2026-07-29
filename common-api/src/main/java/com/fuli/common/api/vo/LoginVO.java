package com.fuli.common.api.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class LoginVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String token;
    private String tokenType;
    private Long expiresIn;
    private String username;
    private String nickname;
    private BigDecimal cash;
}
