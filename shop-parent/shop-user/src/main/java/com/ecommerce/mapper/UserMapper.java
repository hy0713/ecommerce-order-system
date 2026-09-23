package com.ecommerce.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.common.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户表 Mapper
 */
public interface UserMapper extends BaseMapper<User> {
    @Select("SELECT id FROM `user` WHERE id = #{userId} FOR UPDATE")
    Long lockById(@Param("userId") Long userId);
}
