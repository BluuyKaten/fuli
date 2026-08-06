package com.fuli.auth.controller;

import com.fuli.auth.entity.IdempotentMessage;
import com.fuli.auth.entity.User;
import com.fuli.auth.service.IdempotentMessageService;
import com.fuli.auth.service.UserService;
import com.fuli.auth.util.JwtUtil;
import com.fuli.common.api.Result;
import com.fuli.common.api.dto.LoginDTO;
import com.fuli.common.api.vo.LoginVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
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

    public AuthController(UserService userService, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                          HttpServletRequest request, IdempotentMessageService idempotentMessageService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.request = request;
        this.idempotentMessageService = idempotentMessageService;
    }

    private Long getCurrentUserId() {
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader != null && !userIdHeader.isEmpty()) {
            return Long.parseLong(userIdHeader);
        }
        return null;
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO loginDTO) {
        User user = userService.findByUsername(loginDTO.getUsername());
        if (user == null) {
            return Result.error("用户名或密码错误");
        }

        if (user.getStatus() == 0) {
            return Result.error("账号已被禁用");
        }

        if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            return Result.error("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());

        LoginVO loginVO = new LoginVO();
        loginVO.setToken(token);
        loginVO.setTokenType("Bearer");
        loginVO.setExpiresIn(86400L);
        loginVO.setUsername(user.getUsername());
        loginVO.setNickname(user.getNickname());
        loginVO.setCash(user.getCash());

        return Result.success("登录成功", loginVO);
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
        try {
            boolean success = idempotentMessageService.executeIdempotent(
                    msgId, userId, amount, IdempotentMessage.DIRECTION_DEDUCT, () -> {
                        User user = userService.getById(userId);
                        if (user == null) {
                            throw new RuntimeException("用户不存在");
                        }
                        if (user.getCash().compareTo(amount) < 0) {
                            throw new RuntimeException("现金不足");
                        }
                        // 原子更新,避免并发覆盖
                        boolean updated = userService.deductCash(userId, amount);
                        if (!updated) {
                            throw new RuntimeException("扣款失败(现金不足或并发冲突)");
                        }
                    });
            return Result.success("扣款成功", success);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/internal/addCash")
    public Result<Boolean> addCash(@RequestParam Long userId,
                                    @RequestParam BigDecimal amount,
                                    @RequestParam(value = "msgId", required = false) String msgId) {
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

    @GetMapping("/internal/userInitialCash")
    public Result<BigDecimal> getUserInitialCash(@RequestParam Long userId) {
        return Result.success(new BigDecimal("200000.00"));
    }

    @PutMapping("/internal/resetCash")
    public Result<Boolean> resetCash(@RequestParam Long userId, @RequestParam BigDecimal newCash) {
        User user = userService.getById(userId);
        if (user == null) {
            return Result.error("用户不存在");
        }
        user.setCash(newCash);
        userService.updateById(user);
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
