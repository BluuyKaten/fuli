package com.fuli.auth.controller;

import com.fuli.auth.entity.IdempotentMessage;
import com.fuli.auth.entity.User;
import com.fuli.auth.service.IdempotentMessageService;
import com.fuli.auth.service.UserService;
import com.fuli.auth.util.JwtUtil;
import com.fuli.common.api.Result;
import com.fuli.common.api.config.InternalKeyProperties;
import com.fuli.common.api.dto.LoginDTO;
import com.fuli.common.api.exception.BizCode;
import com.fuli.common.api.exception.BusinessException;
import com.fuli.common.api.security.TokenBlacklistService;
import com.fuli.common.api.vo.LoginVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final HttpServletRequest request;
    private final IdempotentMessageService idempotentMessageService;
    private final InternalKeyProperties internalKeyProperties;
    private final ObjectProvider<TokenBlacklistService> tokenBlacklistServiceProvider;

    public AuthController(UserService userService, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                          HttpServletRequest request, IdempotentMessageService idempotentMessageService,
                          InternalKeyProperties internalKeyProperties,
                          ObjectProvider<TokenBlacklistService> tokenBlacklistServiceProvider) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.request = request;
        this.idempotentMessageService = idempotentMessageService;
        this.internalKeyProperties = internalKeyProperties;
        this.tokenBlacklistServiceProvider = tokenBlacklistServiceProvider;
    }

    private Long getCurrentUserId() {
        return com.fuli.common.api.security.AuthenticatedUser.requireId(request);
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        User user = userService.findByUsername(loginDTO.getUsername());
        if (user == null) {
            return Result.error("用户名或密码错误");
        }

        if (Integer.valueOf(0).equals(user.getStatus())) {
            return Result.error("账号已被禁用");
        }

        if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            return Result.error("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());

        LoginVO loginVO = new LoginVO();
        loginVO.setUserId(user.getId());
        loginVO.setToken(token);
        loginVO.setTokenType("Bearer");
        loginVO.setExpiresIn(jwtUtil.getExpirationSeconds());
        loginVO.setUsername(user.getUsername());
        loginVO.setNickname(user.getNickname());
        loginVO.setCash(user.getCash());

        return Result.success("登录成功", loginVO);
    }

    @PostMapping("/logout")
    public Result<Boolean> logout() {
        String token = extractBearerToken(request);
        TokenBlacklistService blacklist = tokenBlacklistServiceProvider.getIfAvailable();
        if (token != null && blacklist != null) {
            try {
                io.jsonwebtoken.Claims claims = jwtUtil.parseToken(token);
                String jti = claims.getId();
                java.util.Date exp = claims.getExpiration();
                if (jti != null && exp != null) {
                    long ttlSeconds = Math.max(1, (exp.getTime() - System.currentTimeMillis()) / 1000);
                    blacklist.blacklist(jti, java.time.Duration.ofSeconds(ttlSeconds));
                    log.info("用户登出，令牌已加入黑名单: userId={}", getCurrentUserId());
                }
            } catch (Exception e) {
                log.warn("登出解析令牌失败: {}", e.getMessage());
            }
        }
        return Result.success("已退出登录", true);
    }

    private String extractBearerToken(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody LoginDTO registerDTO) {
        String username = registerDTO.getUsername();

        if (userService.findByUsername(username) != null) {
            return Result.error("用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(registerDTO.getPassword()));
        user.setNickname(username);
        user.setCash(new BigDecimal("200000.00"));
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());

        userService.save(user);

        return Result.success("注册成功", user.getId());
    }

    @GetMapping("/profile")
    public Result<User> profile() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.unauthorized("未登录");
        }
        User user = userService.getById(userId);
        if (user != null) {
            user.setPassword(null);
        }
        return Result.success(user);
    }

    @PutMapping("/profile")
    public Result<Boolean> updateProfile(@RequestBody User updateInfo) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.unauthorized("未登录");
        }
        User user = userService.getById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }
        if (updateInfo.getNickname() != null) {
            user.setNickname(updateInfo.getNickname());
        }
        if (updateInfo.getEmail() != null) {
            user.setEmail(updateInfo.getEmail());
        }
        if (updateInfo.getPhone() != null) {
            user.setPhone(updateInfo.getPhone());
        }
        userService.updateById(user);
        return Result.success("更新成功", true);
    }

    @PutMapping("/internal/deductCash")
    public Result<Boolean> deductCash(@RequestParam Long userId,
                                      @RequestParam BigDecimal amount,
                                      @RequestParam(value = "msgId", required = false) String msgId) {
        if (amount == null || amount.signum() <= 0) {
            return Result.error(BizCode.PARAM_ERROR, "扣款金额必须大于 0");
        }
        try {
            boolean success = idempotentMessageService.executeIdempotent(
                    msgId, userId, amount, IdempotentMessage.DIRECTION_DEDUCT, () -> {
                        User user = userService.getById(userId);
                        if (user == null) {
                            throw new BusinessException(BizCode.PARAM_ERROR, "用户不存在");
                        }
                        // 条件更新（cash >= amount）是唯一的资金判定依据：
                        // 查询再扣减会在并发下同时通过校验，导致余额被扣成负数。
                        if (!userService.deductCash(userId, amount)) {
                            throw new BusinessException(BizCode.CASH_INSUFFICIENT,
                                    "可用资金不足，当前余额 ¥" + user.getCash().setScale(2, java.math.RoundingMode.HALF_UP));
                        }
                    });
            return Result.success("扣款成功", success);
        } catch (BusinessException e) {
            // 资金不足等业务失败需要把错误码透出，调用方据此区分"该拒单"和"该重试"
            return Result.error(e.getCode(), e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/internal/addCash")
    public Result<Boolean> addCash(@RequestParam Long userId,
                                    @RequestParam BigDecimal amount,
                                    @RequestParam(value = "msgId", required = false) String msgId) {
        if (amount == null || amount.signum() <= 0) {
            return Result.error(BizCode.PARAM_ERROR, "入账金额必须大于 0");
        }
        try {
            boolean success = idempotentMessageService.executeIdempotent(
                    msgId, userId, amount, IdempotentMessage.DIRECTION_ADD, () -> {
                        User user = userService.getById(userId);
                        if (user == null) {
                            throw new RuntimeException("用户不存在");
                        }
                        userService.addCash(userId, amount);
                    });
            return Result.success("入账成功", success);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/internal/userCash")
    public Result<BigDecimal> getUserCash(@RequestParam Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }
        return Result.success(user.getCash());
    }

    /**
     * OKX 交易权限（ADR-0010 决策 2）：data-service 每次写操作前的唯一权威来源。
     *
     * <p>为什么放在 auth-service：{@code sys_user} 归它所有。跨库直连会让两个服务的
     * 部署绑死，也让「谁拥有这份数据」变得含混。
     *
     * <p>返回 {@code false} 而不是报错，是刻意的：用户不存在时也应该得到「没有权限」这一结论，
     * 由调用方统一按 fail-closed 处理。
     */
    @GetMapping("/internal/okxTradeEnabled")
    public Result<Boolean> getOkxTradeEnabled(@RequestParam Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            return Result.error(BizCode.PARAM_ERROR, "用户不存在");
        }
        // 只有明确为 1 才算开启；null / 0 / 其它值一律视为关闭
        return Result.success(Integer.valueOf(1).equals(user.getOkxTradeEnabled()));
    }

    @GetMapping("/internal/userInitialCash")
    public Result<BigDecimal> getUserInitialCash(@RequestParam Long userId) {
        return Result.success(new BigDecimal("200000.00"));
    }

    @PutMapping("/internal/resetCash")
    public Result<Boolean> resetCash(@RequestParam Long userId, @RequestParam BigDecimal newCash) {
        // 高敏感操作：直接改写余额，仅允许在开发/测试环境使用
        if (!internalKeyProperties.isAllowDangerousOperations()) {
            log.warn("【安全】resetCash 已被配置禁止（fuli.allow-dangerous-operations=false），拒绝执行: userId={}", userId);
            return Result.error("该操作已禁用，仅允许在开发/测试环境使用");
        }
        if (newCash == null || newCash.signum() < 0) {
            return Result.error(BizCode.PARAM_ERROR, "余额不能为负数");
        }
        User user = userService.getById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }
        BigDecimal oldCash = user.getCash();
        user.setCash(newCash);
        userService.updateById(user);
        // 审计日志：记录现金重置操作（高风险操作）
        log.warn("【审计】重置用户现金: userId={}, oldCash={}, newCash={}, operator=internal-api",
                userId, oldCash, newCash);
        return Result.success("重置成功", true);
    }

    @PutMapping("/password")
    public Result<Boolean> changePassword(@RequestBody Map<String, String> params) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.unauthorized("未登录");
        }
        String oldPassword = params.get("oldPassword");
        String newPassword = params.get("newPassword");
        if (oldPassword == null || newPassword == null || newPassword.length() < 6) {
            return Result.error("参数错误：新密码至少6位");
        }
        User user = userService.getById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            return Result.error("原密码错误");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userService.updateById(user);
        return Result.success("修改成功", true);
    }
}
