package com.fuli.auth.controller;

import com.fuli.auth.entity.UserWatchlist;
import com.fuli.auth.mapper.UserWatchlistMapper;
import com.fuli.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户自选股
 */
@RestController
@RequestMapping("/auth/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final UserWatchlistMapper userWatchlistMapper;

    /**
     * 获取当前用户自选股
     * GET /watchlist?userId=1
     */
    @GetMapping
    public Result<List<UserWatchlist>> list(@RequestParam Long userId) {
        List<UserWatchlist> list = userWatchlistMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UserWatchlist>()
                        .eq("user_id", userId)
                        .orderByAsc("sort_order")
        );
        return Result.success(list);
    }

    /**
     * 添加自选
     * POST /watchlist {"userId":1,"stockCode":"300750","stockName":"宁德时代"}
     */
    @PostMapping
    public Result<Void> add(@RequestBody UserWatchlist watchlist) {
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
     * DELETE /watchlist/300750?userId=1
     */
    @DeleteMapping("/{stockCode}")
    public Result<Void> remove(@PathVariable String stockCode, @RequestParam Long userId) {
        userWatchlistMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UserWatchlist>()
                        .eq("user_id", userId)
                        .eq("stock_code", stockCode)
        );
        return Result.success();
    }
}
