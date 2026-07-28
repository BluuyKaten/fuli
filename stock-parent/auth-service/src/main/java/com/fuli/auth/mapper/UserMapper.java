package com.fuli.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
