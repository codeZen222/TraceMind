package org.example.tracemind.security;

import org.example.tracemind.common.BizException;
import org.example.tracemind.common.ResultCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 取当前登录用户。
 *
 * <p>JWT 过滤器把 userId 塞进 SecurityContext 的 principal，
 * 业务代码一律通过这里取，不直接摸 SecurityContext ——
 * 取当前用户的逻辑收口在一个地方，将来换鉴权实现只改这里。</p>
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** 当前登录用户 id。理论到不了"未登录"分支（Security 已拦），兜底抛 401。 */
    public static String id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "未登录");
        }
        return String.valueOf(auth.getPrincipal());
    }
}
