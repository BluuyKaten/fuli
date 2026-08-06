package com.fuli.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fuli.auth.entity.User;
import com.fuli.auth.mapper.UserMapper;
import com.fuli.auth.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
public class UserServiceImpl extends com.baomidou.mybatisplus.spring.service.impl.ServiceImpl<UserMapper, User> implements UserService {

    @Override
    public User findByUsername(String username) {
        return getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    @Override
    public boolean deductCash(Long userId, BigDecimal amount) {
        return baseMapper.deductCash(userId, amount) > 0;
    }

    @Override
    public void addCash(Long userId, BigDecimal amount) {
        baseMapper.addCash(userId, amount);
    }
}
