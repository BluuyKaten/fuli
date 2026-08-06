package com.fuli.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 原子扣款：仅当 cash >= amount 时才扣减。
     * @return 受影响行数：1-成功，0-现金不足
     */
    @Update("UPDATE sys_user SET cash = cash - #{amount}, update_time = NOW() " +
            "WHERE id = #{userId} AND cash >= #{amount}")
    int deductCash(@Param("userId") Long userId, @Param("amount") java.math.BigDecimal amount);

    /**
     * 原子入账
     * @return 受影响行数：1-成功
     */
    @Update("UPDATE sys_user SET cash = cash + #{amount}, update_time = NOW() " +
            "WHERE id = #{userId}")
    int addCash(@Param("userId") Long userId, @Param("amount") java.math.BigDecimal amount);
}

