package com.fuli.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fuli.auth.entity.User;
import com.fuli.auth.mapper.UserMapper;
import com.fuli.auth.service.AuthService;
import com.fuli.common.dto.LoginDTO;
import com.fuli.common.dto.RegisterDTO;
import com.fuli.common.dto.TokenVO;
import com.fuli.common.exception.BizException;
import com.fuli.security.JwtTokenProvider;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String BLACKLIST_PREFIX = "auth:token:blacklist:";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public TokenVO login(LoginDTO dto) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BizException("用户名或密码错误");
        }
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername());
        return new TokenVO(token, "Bearer", jwtTokenProvider.getExpireAt(token));
    }

    @Override
    public void register(RegisterDTO dto) {
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if (exists != null && exists > 0) {
            throw new BizException("用户名已存在");
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);
    }

    @Override
    public void logout(String token) {
        long expireAt = jwtTokenProvider.getExpireAt(token);
        long ttlMillis = Math.max(0L, expireAt - System.currentTimeMillis());
        stringRedisTemplate.opsForValue().set(BLACKLIST_PREFIX + token, "1", Duration.ofMillis(ttlMillis));
    }
}
