package com.xushu.rag.mapper;

import com.github.pagehelper.Page;
import com.xushu.rag.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xushu.rag.pojo.dto.UserPageQueryDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
* @author Xushu
* @description 针对表【user】的数据库操作Mapper
* @createDate 2025-02-14 21:05:04
* @Entity com.Xushu.rag.entity.User
*/
@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("select * from tb_user where user_name = #{userName}")
    User getByUsername(@Param("userName") String userName);

    Page<User> pageQuery(UserPageQueryDTO userPageQueryDTO);

    void updateUser(User user);
}




