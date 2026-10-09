package org.example.tracemind.pojo.vo;

import lombok.Data;

/** 用户信息（对外）。⚠️ 绝不含 password_hash。 */
@Data
public class UserVO {

    private String id;

    private String email;

    private String nickname;
}
