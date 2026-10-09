package org.example.tracemind.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.tracemind.common.ApiResponse;
import org.example.tracemind.pojo.dto.LoginRequest;
import org.example.tracemind.pojo.dto.RegisterRequest;
import org.example.tracemind.pojo.vo.AuthVO;
import org.example.tracemind.pojo.vo.UserVO;
import org.example.tracemind.security.CurrentUser;
import org.example.tracemind.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ApiResponse<AuthVO> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthVO> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    /** 当前登录用户。前端刷新页面时用它把 token 换回用户信息。 */
    @GetMapping("/me")
    public ApiResponse<UserVO> me() {
        return ApiResponse.ok(authService.me(CurrentUser.id()));
    }
}
