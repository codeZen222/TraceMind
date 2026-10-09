package org.example.tracemind.pojo.vo;

import lombok.Data;

/** 登录 / 注册成功后返回：JWT + 用户信息。 */
@Data
public class AuthVO {

    /** JWT 令牌，前端存起来，之后每个请求带 {@code Authorization: Bearer <token>} */
    private String token;

    private UserVO user;
}
