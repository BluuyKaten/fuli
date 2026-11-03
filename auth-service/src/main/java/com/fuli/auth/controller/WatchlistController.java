package com.fuli.auth.controller;

import com.fuli.auth.entity.UserWatchlist;
import com.fuli.auth.mapper.UserWatchlistMapper;
import com.fuli.common.api.Result;
import com.fuli.common.api.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户自选股
 *
 * <p>userId 一律取自认证上下文（BearerUserFilter 写入的 request attribute），
 * 不信任客户端请求参数，防止水平越权（IDOR）。
 */
@RestController
@RequestMapping("/auth/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final UserWatchlistMapper userWatchlistMapper;
    private final HttpServletRequest request;

    private Long currentUserId() {
        return AuthenticatedUser.requireId(request);
    }

    /**
     * 获取当前用户自选股
     * GET /auth/watchlist
     */
    @GetMapping
    public Result<List<UserWatchlist>> list() {
        Long userId = currentUserId();
        List<UserWatchlist> list = userWatchlistMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UserWatchlist>()
                        .eq("user_id", userId)
                        .orderByAsc("sort_order")
        );
        return Result.success(list);
    }

    /**
     * 添加自选
     * POST /auth/watchlist {"stockCode":"300750","stockName":"宁德时代"}
     */
    @PostMapping
    public Result<Void> add(@RequestBody UserWatchlist watchlist) {
        // 归属固定为当前登录用户，忽略客户端传入的 userId
        watchlist.setUserId(currentUserId());
        if (watchlist.getSortOrder() == null) {
            watchlist.setSortOrder(0);
        }
        // 检查是否已存在
        UserWatchlist exist = userWatchlistMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UserWatchlist>()
                        .eq("user_id", watchlist.getUserId())
                        .eq("stock_code", watchlist.getStockCode())
        );
        if (exist != null) {
            return Result.error("已在自选列表中");
        }
        userWatchlistMapper.insert(watchlist);
        return Result.success();
    }

    /**
     * 删除自选
     * DELETE /auth/watchlist/300750
     */
    @DeleteMapping("/{stockCode}")
    public Result<Void> remove(@PathVariable String stockCode) {
        Long userId = currentUserId();
        userWatchlistMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UserWatchlist>()
                        .eq("user_id", userId)
                        .eq("stock_code", stockCode)
        );
        return Result.success();
    }
}
