package com.fuli.auth;

import com.fuli.auth.entity.IdempotentMessage;
import com.fuli.auth.entity.User;
import com.fuli.auth.service.IdempotentMessageService;
import com.fuli.auth.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * internal 接口幂等性测试。
 * 验证同一 msgId 重复调用不会重复扣款/入账。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthInternalServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private IdempotentMessageService idempotentMessageService;

    private static final Long USER_ID = 100L;

    @Test
    void deductCash_idempotent_sameMsgId_shouldOnlyDeductOnce() {
        // 准备用户
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("testuser");
        user.setPassword("pwd");
        user.setCash(new BigDecimal("10000.00"));
        userService.save(user);

        BigDecimal amount = new BigDecimal("1000.00");
        String msgId = "test-msg-001";

        // 第一次调用：扣款成功
        boolean first = idempotentMessageService.executeIdempotent(
                msgId, USER_ID, amount, IdempotentMessage.DIRECTION_DEDUCT,
                () -> userService.deductCash(USER_ID, amount));
        assertTrue(first);

        BigDecimal afterFirst = userService.getById(USER_ID).getCash();
        assertEquals(0, afterFirst.compareTo(new BigDecimal("9000.00")));

        // 第二次调用（相同 msgId）：应被幂等拦截，不再扣款
        boolean second = idempotentMessageService.executeIdempotent(
                msgId, USER_ID, amount, IdempotentMessage.DIRECTION_DEDUCT,
                () -> userService.deductCash(USER_ID, amount));
        assertTrue(second);

        BigDecimal afterSecond = userService.getById(USER_ID).getCash();
        // 余额应仍为 9000，不会变成 8000
        assertEquals(0, afterSecond.compareTo(new BigDecimal("9000.00")),
                "同一 msgId 重复调用不应重复扣款");
    }

    @Test
    void addCash_idempotent_sameMsgId_shouldOnlyAddOnce() {
        User user = new User();
        user.setId(USER_ID + 1);
        user.setUsername("testuser2");
        user.setPassword("pwd");
        user.setCash(new BigDecimal("5000.00"));
        userService.save(user);

        BigDecimal amount = new BigDecimal("500.00");
        String msgId = "test-msg-002";

        idempotentMessageService.executeIdempotent(
                msgId, USER_ID + 1, amount, IdempotentMessage.DIRECTION_ADD,
                () -> userService.addCash(USER_ID + 1, amount));

        BigDecimal afterFirst = userService.getById(USER_ID + 1).getCash();
        assertEquals(0, afterFirst.compareTo(new BigDecimal("5500.00")));

        // 重复调用
        idempotentMessageService.executeIdempotent(
                msgId, USER_ID + 1, amount, IdempotentMessage.DIRECTION_ADD,
                () -> userService.addCash(USER_ID + 1, amount));

        BigDecimal afterSecond = userService.getById(USER_ID + 1).getCash();
        assertEquals(0, afterSecond.compareTo(new BigDecimal("5500.00")),
                "同一 msgId 重复调用不应重复入账");
    }
}
