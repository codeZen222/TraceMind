package org.example.tracemind.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录请求。 */
@Data
public class LoginRequest {

    @NotBlank(message = "邮箱不能为空")
    private String email;

    @NotBlank(message = "密码不能为空")
    private String password;
}
