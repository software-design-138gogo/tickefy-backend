package com.tickefy.auth.modules.auth.controller;

import com.tickefy.auth.common.constants.HeaderConstants;
import com.tickefy.auth.common.response.ApiResponse;
import com.tickefy.auth.modules.auth.dto.AssignRoleRequest;
import com.tickefy.auth.modules.auth.dto.UserResponse;
import com.tickefy.auth.modules.auth.dto.UserRolesResponse;
import com.tickefy.auth.modules.auth.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> getMe(Authentication authentication, HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        UUID userId = UUID.fromString((String) authentication.getPrincipal());
        UserResponse response = userService.getMe(userId);
        return ApiResponse.success(response, requestId);
    }

    @PostMapping("/users/{userId}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserRolesResponse> assignRole(
            @PathVariable UUID userId,
            @Valid @RequestBody AssignRoleRequest request,
            HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        UserRolesResponse response = userService.assignRole(userId, request.role());
        return ApiResponse.success(response, requestId);
    }

    @DeleteMapping("/users/{userId}/roles/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserRolesResponse> revokeRole(
            @PathVariable UUID userId,
            @PathVariable String role,
            HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        UserRolesResponse response = userService.revokeRole(userId, role);
        return ApiResponse.success(response, requestId);
    }

    @GetMapping("/users/{userId}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserRolesResponse> getUserRoles(
            @PathVariable UUID userId,
            HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        UserRolesResponse response = userService.getUserRoles(userId);
        return ApiResponse.success(response, requestId);
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Page<UserResponse>> listUsers(
            @PageableDefault(size = 20) Pageable pageable,
            HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        Page<UserResponse> response = userService.listUsers(pageable);
        return ApiResponse.success(response, requestId);
    }

    private String getRequestId(HttpServletRequest request) {
        String fromAttr = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        if (fromAttr != null) {
            return fromAttr;
        }
        return MDC.get(HeaderConstants.REQUEST_ID);
    }
}
