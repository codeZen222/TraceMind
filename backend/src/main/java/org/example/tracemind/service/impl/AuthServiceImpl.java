package org.example.tracemind.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.tracemind.common.BizException;
import org.example.tracemind.common.IdGenerator;
import org.example.tracemind.mapper.UserMapper;
import org.example.tracemind.pojo.dto.LoginRequest;
import org.example.tracemind.pojo.dto.RegisterRequest;
import org.example.tracemind.pojo.entity.User;
import org.example.tracemind.pojo.vo.AuthVO;
import org.example.tracemind.pojo.vo.UserVO;
import org.example.tracemind.security.JwtService;
import org.example.tracemind.service.AuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证实现。
 *
 * <p>两个安全细节：</p>
 * <ul>
 *   <li>邮箱做 normalize（trim + 小写）：{@code A@x.com} 和 {@code a@x.com} 必须是同一个账号，
 *       否则"注册了 a@x.com，登录时输 A@x.com 又建一个新号"。</li>
 *   <li>登录失败<b>不区分</b>"邮箱不存在"和"密码错误"，统一回"邮箱或密码错误"——
 *       区分的话等于告诉攻击者哪些邮箱注册过（账号枚举）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Override
    public AuthVO register(RegisterRequest request) {
        String email = normalize(request.getEmail());
        if (userMapper.selectByEmail(email) != null) {
            throw BizException.conflict("该邮箱已注册");
        }
        User user = new User();
        user.setId(IdGenerator.user());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname().trim());
        userMapper.insert(user);
        return buildAuth(user);
    }

    @Override
    public AuthVO login(LoginRequest request) {
        String email = normalize(request.getEmail());
        User user = userMapper.selectByEmail(email);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw BizException.badRequest("邮箱或密码错误");
        }
        return buildAuth(user);
    }

    @Override
    public UserVO me(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        return toVO(user);
    }

    private AuthVO buildAuth(User user) {
        AuthVO vo = new AuthVO();
        vo.setToken(jwtService.issue(user.getId(), user.getEmail()));
        vo.setUser(toVO(user));
        return vo;
    }

    private UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setEmail(user.getEmail());
        vo.setNickname(user.getNickname());
        return vo;
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
