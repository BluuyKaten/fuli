package com.fuli.auth.service;

import com.fuli.common.dto.LoginDTO;
import com.fuli.common.dto.RegisterDTO;
import com.fuli.common.dto.TokenVO;

public interface AuthService {
    TokenVO login(LoginDTO dto);

    void register(RegisterDTO dto);

    void logout(String token);
}
