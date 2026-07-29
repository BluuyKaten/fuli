package com.fuli.auth.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuli.auth.entity.User;

public interface UserService extends IService<User> {

    User findByUsername(String username);
}
