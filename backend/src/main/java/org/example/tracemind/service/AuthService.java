package org.example.tracemind.service;

import org.example.tracemind.pojo.dto.LoginRequest;
import org.example.tracemind.pojo.dto.RegisterRequest;
import org.example.tracemind.pojo.vo.AuthVO;
import org.example.tracemind.pojo.vo.UserVO;

/** 认证服务：注册 / 登录 / 当前用户。 */
public interface AuthService {

    AuthVO register(RegisterRequest request);

    AuthVO login(LoginRequest request);

    UserVO me(String userId);
}
