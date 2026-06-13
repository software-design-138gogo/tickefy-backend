package com.tickefy.auth.modules.auth.mapper;

import com.tickefy.auth.modules.auth.dto.RegisterResponse;
import com.tickefy.auth.modules.auth.dto.UserResponse;
import com.tickefy.auth.modules.auth.dto.UserRolesResponse;
import com.tickefy.auth.modules.auth.entity.UserEntity;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public RegisterResponse toRegisterResponse(UserEntity user) {
        List<String> roles = user.getRoles().stream()
                .map(r -> r.getCode())
                .sorted()
                .collect(Collectors.toList());
        return new RegisterResponse(
                user.getId().toString(),
                user.getEmail(),
                user.getFullName(),
                roles);
    }

    public UserResponse toUserResponse(UserEntity user) {
        List<String> roles = user.getRoles().stream()
                .map(r -> r.getCode())
                .sorted()
                .collect(Collectors.toList());
        return new UserResponse(
                user.getId().toString(),
                user.getEmail(),
                user.getFullName(),
                roles);
    }

    public UserRolesResponse toUserRolesResponse(UserEntity user) {
        List<String> roles = user.getRoles().stream()
                .map(r -> r.getCode())
                .sorted()
                .collect(Collectors.toList());
        return new UserRolesResponse(user.getId().toString(), roles);
    }
}
