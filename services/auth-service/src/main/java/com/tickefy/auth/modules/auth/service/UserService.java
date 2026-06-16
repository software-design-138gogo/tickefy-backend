package com.tickefy.auth.modules.auth.service;

import com.tickefy.auth.common.exception.ApiException;
import com.tickefy.auth.common.exception.ErrorCode;
import com.tickefy.auth.modules.auth.dto.UserResponse;
import com.tickefy.auth.modules.auth.dto.UserRolesResponse;
import com.tickefy.auth.modules.auth.entity.RoleEntity;
import com.tickefy.auth.modules.auth.entity.UserEntity;
import com.tickefy.auth.modules.auth.mapper.UserMapper;
import com.tickefy.auth.modules.auth.repository.RoleRepository;
import com.tickefy.auth.modules.auth.repository.UserRepository;
import com.tickefy.auth.modules.auth.security.RoleName;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserMapper userMapper;

    public UserService(UserRepository userRepository, RoleRepository roleRepository, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userMapper = userMapper;
    }

    public UserResponse getMe(UUID userId) {
        UserEntity user = findUserById(userId);
        return userMapper.toUserResponse(user);
    }

    @Transactional
    public UserRolesResponse assignRole(UUID userId, String role) {
        validateRoleName(role);
        UserEntity user = findUserById(userId);

        boolean alreadyHas = user.getRoles().stream()
                .anyMatch(r -> r.getCode().equals(role));
        if (alreadyHas) {
            log.debug("User {} already has role {} — no-op", userId, role);
            return userMapper.toUserRolesResponse(user);
        }

        RoleEntity roleEntity = roleRepository.findByCode(role)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INVALID_ROLE,
                        "Role not found in DB: " + role,
                        HttpStatus.BAD_REQUEST));

        try {
            user.getRoles().add(roleEntity);
            userRepository.save(user);
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("Race condition on user_roles PK for userId={} role={} — treating as no-op", userId, role, e);
            // Reload fresh state
            user = findUserById(userId);
        }

        return userMapper.toUserRolesResponse(user);
    }

    @Transactional
    public UserRolesResponse revokeRole(UUID userId, String role) {
        validateRoleName(role);
        UserEntity user = findUserById(userId);

        boolean hasRole = user.getRoles().stream()
                .anyMatch(r -> r.getCode().equals(role));

        if (!hasRole) {
            log.debug("User {} does not have role {} — no-op", userId, role);
            return userMapper.toUserRolesResponse(user);
        }

        if (RoleName.ADMIN.name().equals(role)) {
            long adminCount = userRepository.countByRoleCode("ADMIN");
            if (adminCount <= 1) {
                throw new ApiException(
                        ErrorCode.LAST_ADMIN,
                        "Cannot remove the last ADMIN",
                        HttpStatus.CONFLICT);
            }
        }

        user.getRoles().removeIf(r -> r.getCode().equals(role));
        userRepository.save(user);

        return userMapper.toUserRolesResponse(user);
    }

    public UserRolesResponse getUserRoles(UUID userId) {
        UserEntity user = findUserById(userId);
        return userMapper.toUserRolesResponse(user);
    }

    public Page<UserResponse> listUsers(Pageable pageable) {
        // Tránh bẫy EAGER+Pageable: phân trang id scalar trước, rồi load entities
        Page<UUID> idPage = userRepository.findUserIds(pageable);
        List<UUID> ids = idPage.getContent();

        List<UserResponse> content;
        if (ids.isEmpty()) {
            content = List.of();
        } else {
            List<UserEntity> users = userRepository.findAllByIdIn(ids);
            // Giữ thứ tự theo id page (ORDER BY createdAt)
            content = ids.stream()
                    .map(id -> users.stream()
                            .filter(u -> u.getId().equals(id))
                            .findFirst()
                            .orElse(null))
                    .filter(u -> u != null)
                    .map(userMapper::toUserResponse)
                    .toList();
        }

        return new PageImpl<>(content, pageable, idPage.getTotalElements());
    }

    private UserEntity findUserById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.USER_NOT_FOUND,
                        "User not found: " + userId,
                        HttpStatus.NOT_FOUND));
    }

    private void validateRoleName(String role) {
        try {
            RoleName.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    ErrorCode.INVALID_ROLE,
                    "Invalid role: " + role,
                    HttpStatus.BAD_REQUEST);
        }
    }
}
