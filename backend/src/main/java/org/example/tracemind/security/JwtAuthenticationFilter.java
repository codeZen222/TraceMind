package org.example.tracemind.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 从 {@code Authorization: Bearer <token>} 解析出 userId，塞进 SecurityContext。
 *
 * <p>token 无效 / 过期时不在这里抛错，只是<b>不设置认证</b>——交给后面的 Security 授权链拦成 401。
 * 在这里抛异常的话，会绕过统一的 401 处理，变成 500。</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            try {
                String userId = jwtService.parseUserId(token);
                // principal 直接放 userId：CurrentUser.id() 只取它，不需要完整的 UserDetails
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                // 无效 token → 不设置认证，后续按未登录处理
            }
        }
        chain.doFilter(request, response);
    }
}
