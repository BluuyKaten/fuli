package com.fuli.auth.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fuli.auth.entity.UserWatchlist;
import com.fuli.auth.mapper.UserWatchlistMapper;
import com.fuli.common.api.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 自选股接口越权（IDOR）回归：userId 必须取自认证上下文，不得信任客户端参数。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class WatchlistControllerTest {

    private static final String USER_ATTR = AuthenticatedUser.class.getName() + ".userId";

    private WatchlistController newController(UserWatchlistMapper mapper, long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(USER_ATTR, userId);
        return new WatchlistController(mapper, request);
    }

    /** 检查 wrapper 是否带了指定 userId 的 user_id 条件（兼容 Long/Integer 装箱差异）。 */
    private boolean usesUserId(QueryWrapper wrapper, long id) {
        // MyBatis-Plus 的参数在生成 SQL 片段时才填充，因此先触发 getSqlSegment()
        String sql = wrapper.getSqlSegment();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        return sql.contains("user_id")
                && params != null
                && params.values().stream().anyMatch(v -> String.valueOf(v).equals(String.valueOf(id)));
    }

    @Test
    void list_usesAuthenticatedUserId() {
        UserWatchlistMapper mapper = mock(UserWatchlistMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        WatchlistController controller = newController(mapper, 42L);

        controller.list();

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        assertTrue(usesUserId(captor.getValue(), 42L),
                "list 应使用认证上下文里的 userId，而非客户端参数");
    }

    @Test
    void add_overridesClientUserId_withAuthenticatedUser() {
        UserWatchlistMapper mapper = mock(UserWatchlistMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(UserWatchlist.class))).thenReturn(1);
        WatchlistController controller = newController(mapper, 42L);

        UserWatchlist input = new UserWatchlist();
        input.setUserId(999L); // 恶意/误传他人 userId
        input.setStockCode("300750");
        input.setStockName("宁德时代");
        controller.add(input);

        ArgumentCaptor<UserWatchlist> captor = ArgumentCaptor.forClass(UserWatchlist.class);
        verify(mapper).insert(captor.capture());
        assertEquals(42L, captor.getValue().getUserId(),
                "add 必须把 userId 强制覆盖为认证用户，不能信任客户端传入值");
    }

    @Test
    void remove_usesAuthenticatedUserId() {
        UserWatchlistMapper mapper = mock(UserWatchlistMapper.class);
        when(mapper.delete(any())).thenReturn(1);
        WatchlistController controller = newController(mapper, 42L);

        controller.remove("300750");

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).delete(captor.capture());
        assertTrue(usesUserId(captor.getValue(), 42L),
                "remove 应使用认证上下文里的 userId，而非客户端参数");
    }

    @Test
    void list_withoutAuth_shouldThrow401() {
        UserWatchlistMapper mapper = mock(UserWatchlistMapper.class);
        MockHttpServletRequest request = new MockHttpServletRequest(); // 无认证上下文
        WatchlistController controller = new WatchlistController(mapper, request);

        assertThrows(ResponseStatusException.class, controller::list);
    }
}
