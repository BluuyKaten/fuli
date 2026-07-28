package com.fuli.auth.controller;

import com.fuli.auth.service.AuthService;
import com.fuli.common.dto.LoginDTO;
import com.fuli.common.dto.RegisterDTO;
import com.fuli.common.dto.TokenVO;
import com.fuli.common.model.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<Void> register(@RequestBody @Valid RegisterDTO dto) {
        authService.register(dto);
        return Result.ok("注册成功", null);
    }

    @PostMapping("/login")
    public Result<TokenVO> login(@RequestBody @Valid LoginDTO dto) {
        return Result.ok(authService.login(dto));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            authService.logout(authHeader.substring(7));
        }
        return Result.ok("退出成功", null);
    }
}
