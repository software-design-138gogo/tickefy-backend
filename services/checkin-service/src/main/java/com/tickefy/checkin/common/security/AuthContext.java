package com.tickefy.checkin.common.security;

import com.tickefy.checkin.common.exception.ApiException;
import com.tickefy.checkin.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AuthContext {

    public String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Authentication required", HttpStatus.UNAUTHORIZED);
        }
        return authentication.getName();
    }
}
