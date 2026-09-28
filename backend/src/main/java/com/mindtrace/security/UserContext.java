package com.mindtrace.security;

import com.mindtrace.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class UserContext {

    private UserContext() {
    }

    public static Long userId() {
        Long userId = currentUserIdOrNull();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        return userId;
    }

    public static Long currentUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        return principal.id();
    }
}
