package org.example.tracemind.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * JWT 签发与校验。HS256 对称签名。
 *
 * <p>为什么选 JWT 而不是服务端 session：诊断、WS、导入都是无状态长连接/异步链路，
 * 每个请求独立带 token 最省事；服务端不存登录态，重启不丢会话，横向扩展也不用共享 session。</p>
 *
 * <p>⚠️ secret 必须从配置注入（生产走环境变量），绝不能写死在代码里。</p>
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.expire-seconds:604800}") long expireSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofSeconds(expireSeconds);
    }

    /** 签发 token。subject = userId。 */
    public String issue(String userId, String email) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttl.toMillis()))
                .signWith(key)
                .compact();
    }

    /** 解析 token，返回 userId。无效 / 过期 / 签名不对会抛异常，由调用方兜住。 */
    public String parseUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }
}
