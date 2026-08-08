package com.fuli.auth.security;

import com.fuli.auth.entity.User;
import com.fuli.auth.service.UserService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;

/**
 * 生产环境默认管理员密码启动检查。
 *
 * <p>若数据库中存在用户名 {@code admin} 且密码哈希与已知的默认 {@code admin123} 哈希一致，
 * 且当前未激活 local profile（视为生产/预发环境），则直接启动失败，强制要求修改默认密码。
 *
 * <p>本地开发（local profile）不触发此检查。
 */
@Slf4j
@Configuration
public class DefaultAdminPasswordChecker {

    /**
     * 默认密码 admin123 的 BCrypt 哈希（与 sql/auth_db.sql 初始数据一致）。
     * 用于启动时比对，命中即视为未改密。
     */
    private static final String DEFAULT_ADMIN_PASSWORD_HASH =
            "$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVEFDa";

    private static final String DEFAULT_ADMIN_USERNAME = "admin";

    private final UserService userService;
    private final Environment environment;

    public DefaultAdminPasswordChecker(UserService userService, Environment environment) {
        this.userService = userService;
        this.environment = environment;
    }

    @PostConstruct
    public void check() {
        // 本地开发不检查，方便快速启动
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        if (activeProfiles.contains("local")) {
            return;
        }

        try {
            User admin = userService.findByUsername(DEFAULT_ADMIN_USERNAME);
            if (admin != null && admin.getPassword() != null
                    && admin.getPassword().equals(DEFAULT_ADMIN_PASSWORD_HASH)) {
                String msg = "安全启动失败：检测到默认管理员账号 admin 仍使用初始密码 admin123。"
                        + "生产/预发环境必须修改默认密码后方可启动。";
                log.error(msg);
                throw new IllegalStateException(msg);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // 查询异常（如数据库未初始化）不阻断启动，交由后续流程暴露
            log.warn("默认管理员密码检查异常，跳过: {}", e.getMessage());
        }
    }
}
