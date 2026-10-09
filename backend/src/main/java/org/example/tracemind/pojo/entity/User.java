package org.example.tracemind.pojo.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户表 users。
 * <p>多租户的"租户"实体。登录后的每一个请求都会解析出当前 userId，
 * 所有业务查询强制按它过滤（{@code owner_id = currentUser}），
 * 这就是行级多租户的隔离边界。</p>
 *
 * <p>⚠️ 密码只存 bcrypt 哈希（{@code password_hash}），绝不明文、绝不回传给前端。</p>
 */
@Data
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    /** 登录邮箱，全库唯一 */
    private String email;

    /** bcrypt 哈希，形如 $2a$10$... */
    private String passwordHash;

    /** 昵称，展示用 */
    private String nickname;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
