package com.fuli.auth.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuli.auth.entity.User;

import java.math.BigDecimal;

public interface UserService extends IService<User> {

    User findByUsername(String username);

    /**
     * 原子扣款，仅当现金充足时扣减
     * @return true-成功，false-现金不足
     */
    boolean deductCash(Long userId, BigDecimal amount);

    /**
     * 原子入账
     */
    void addCash(Long userId, BigDecimal amount);
}

