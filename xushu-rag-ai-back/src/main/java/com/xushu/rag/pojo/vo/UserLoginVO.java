package com.xushu.rag.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Title: UserLoginVo
 * @Author Xushu
 * @Package com.Xushu.rag.pojo.vo
 * @Date 2025/2/14 21:47
 * @description: 用户登录VO
 */

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginVO {

    private Integer id;

    private String userName;

    private String name;

    private String token;
}
