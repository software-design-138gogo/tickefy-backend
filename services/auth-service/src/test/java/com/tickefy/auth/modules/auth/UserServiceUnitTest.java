package com.tickefy.auth.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.auth.common.exception.ApiException;
import com.tickefy.auth.common.exception.ErrorCode;
import com.tickefy.auth.modules.auth.entity.RoleEntity;
import com.tickefy.auth.modules.auth.entity.UserEntity;
import com.tickefy.auth.modules.auth.mapper.UserMapper;
import com.tickefy.auth.modules.auth.repository.RoleRepository;
import com.tickefy.auth.modules.auth.repository.UserRepository;
import com.tickefy.auth.modules.auth.service.UserService;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Pure unit tests for UserService — NO Spring context, NO Docker, NO DB.
 * All dependencies mocked; service constructed via new.
 *
 * AC coverage:
 *   - assignRole invalid name → 400 INVALID_ROLE
 *   - assignRole user missing → 404 USER_NOT_FOUND
 *   - assignRole duplicate (user already has role) → no-op, save NOT called
 *   - assignRole new role → save called once, role added to user
 *   - revokeRole ADMIN when count==1 → 409 LAST_ADMIN
 *   - revokeRole ADMIN when count>1 → save called, no exception
 *   - revokeRole invalid name → 400 INVALID_ROLE
 *   - getMe user missing → 404 USER_NOT_FOUND
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class UserServiceUnitTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserMapper userMapper;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, roleRepository, userMapper);
    }

    // -----------------------------------------------------------------------
    // Helper builders
    // -----------------------------------------------------------------------

    private UserEntity userWithRoles(UUID id, String... roleCodes) {
        Set<RoleEntity> roles = new HashSet<>();
        short i = 1;
        for (String code : roleCodes) {
            roles.add(RoleEntity.builder().id(i++).code(code).build());
        }
        return UserEntity.builder()
                .id(id)
                .email("user@test.com")
                .passwordHash("$2a$10$hash")
                .fullName("Test User")
                .enabled(true)
                .build();
        // Must add roles after build because @Builder.Default initialises an empty HashSet
        // which builder overwrites. We set it manually.
    }

    private UserEntity userWithRolesSet(UUID id, Set<RoleEntity> roles) {
        UserEntity u = UserEntity.builder()
                .id(id)
                .email("user@test.com")
                .passwordHash("$2a$10$hash")
                .fullName("Test User")
                .enabled(true)
                .build();
        u.getRoles().addAll(roles);
        return u;
    }

    // -----------------------------------------------------------------------
    // assignRole — invalid role name (not in RoleName enum)
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("assignRole invalid role 'KING': throws ApiException 400 INVALID_ROLE (not 500)")
    void assignRole_invalidRoleName_throwsInvalidRole400() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> userService.assignRole(userId, "KING"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.INVALID_ROLE);
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        // Validate gate fires BEFORE any repository call
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // assignRole — valid role name but user not found
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("assignRole valid role but user absent: throws ApiException 404 USER_NOT_FOUND")
    void assignRole_userNotFound_throwsUserNotFound404() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.assignRole(userId, "ORGANIZER"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });

        verify(userRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // assignRole — user already has the role → no-op, save NOT called
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("assignRole duplicate: user already has AUDIENCE → no-op, save never called")
    void assignRole_duplicate_noSaveCalled() {
        UUID userId = UUID.randomUUID();
        RoleEntity audienceRole = RoleEntity.builder().id((short) 1).code("AUDIENCE").build();
        UserEntity user = userWithRolesSet(userId, new HashSet<>(Set.of(audienceRole)));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        // userMapper.toUserRolesResponse must not NPE — return a simple stub result
        when(userMapper.toUserRolesResponse(user)).thenReturn(
                new com.tickefy.auth.modules.auth.dto.UserRolesResponse(
                        userId.toString(), java.util.List.of("AUDIENCE")));

        userService.assignRole(userId, "AUDIENCE");

        // save must NOT be called — it's a no-op path
        verify(userRepository, never()).save(any());
        verify(roleRepository, never()).findByCode(any());
    }

    // -----------------------------------------------------------------------
    // assignRole — user does not have role → role added, save called once
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("assignRole new role: ORGANIZER added to user → save called once")
    void assignRole_newRole_saveCalledOnce() {
        UUID userId = UUID.randomUUID();
        UserEntity user = userWithRolesSet(userId, new HashSet<>());

        RoleEntity organizerRole = RoleEntity.builder().id((short) 2).code("ORGANIZER").build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepository.findByCode("ORGANIZER")).thenReturn(Optional.of(organizerRole));
        when(userMapper.toUserRolesResponse(user)).thenReturn(
                new com.tickefy.auth.modules.auth.dto.UserRolesResponse(
                        userId.toString(), java.util.List.of("ORGANIZER")));

        userService.assignRole(userId, "ORGANIZER");

        verify(userRepository, times(1)).save(user);
        // Role must have been added to the set
        assertThat(user.getRoles()).extracting("code").contains("ORGANIZER");
    }

    // -----------------------------------------------------------------------
    // revokeRole — invalid role name
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("revokeRole invalid role 'NINJA': throws ApiException 400 INVALID_ROLE")
    void revokeRole_invalidRoleName_throwsInvalidRole400() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> userService.revokeRole(userId, "NINJA"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.INVALID_ROLE);
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // revokeRole — user not found
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("revokeRole user not found: throws ApiException 404 USER_NOT_FOUND")
    void revokeRole_userNotFound_throwsUserNotFound404() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.revokeRole(userId, "ORGANIZER"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });

        verify(userRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // revokeRole ADMIN — count == 1 → 409 LAST_ADMIN
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("revokeRole ADMIN when count==1 (last admin): throws ApiException 409 LAST_ADMIN")
    void revokeRole_lastAdmin_throwsLastAdmin409() {
        UUID userId = UUID.randomUUID();
        RoleEntity adminRole = RoleEntity.builder().id((short) 4).code("ADMIN").build();
        UserEntity user = userWithRolesSet(userId, new HashSet<>(Set.of(adminRole)));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.countByRoleCode("ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> userService.revokeRole(userId, "ADMIN"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.LAST_ADMIN);
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(userRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // revokeRole ADMIN — count > 1 → allowed, save called
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("revokeRole ADMIN when count>1 (not last admin): save called, no exception")
    void revokeRole_adminWhenMultipleAdmins_saveCalledNoException() {
        UUID userId = UUID.randomUUID();
        RoleEntity adminRole = RoleEntity.builder().id((short) 4).code("ADMIN").build();
        UserEntity user = userWithRolesSet(userId, new HashSet<>(Set.of(adminRole)));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.countByRoleCode("ADMIN")).thenReturn(2L);
        when(userMapper.toUserRolesResponse(user)).thenReturn(
                new com.tickefy.auth.modules.auth.dto.UserRolesResponse(
                        userId.toString(), java.util.List.of()));

        userService.revokeRole(userId, "ADMIN");

        verify(userRepository, times(1)).save(user);
        // ADMIN role must have been removed
        assertThat(user.getRoles()).extracting("code").doesNotContain("ADMIN");
    }

    // -----------------------------------------------------------------------
    // revokeRole — user does not have the role → no-op, save NOT called
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("revokeRole user does not have ORGANIZER: no-op, save never called")
    void revokeRole_userLacksRole_noSaveCalled() {
        UUID userId = UUID.randomUUID();
        // User has AUDIENCE but not ORGANIZER
        RoleEntity audienceRole = RoleEntity.builder().id((short) 1).code("AUDIENCE").build();
        UserEntity user = userWithRolesSet(userId, new HashSet<>(Set.of(audienceRole)));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userMapper.toUserRolesResponse(user)).thenReturn(
                new com.tickefy.auth.modules.auth.dto.UserRolesResponse(
                        userId.toString(), java.util.List.of("AUDIENCE")));

        userService.revokeRole(userId, "ORGANIZER");

        verify(userRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // getMe — user not found → 404 USER_NOT_FOUND
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("getMe user not found: throws ApiException 404 USER_NOT_FOUND")
    void getMe_userNotFound_throwsUserNotFound404() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getMe(userId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    // -----------------------------------------------------------------------
    // getMe — user found → delegates to userMapper, returns UserResponse
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("getMe user found: delegates to userMapper.toUserResponse, returns result")
    void getMe_userFound_returnsMappedResponse() {
        UUID userId = UUID.randomUUID();
        UserEntity user = userWithRolesSet(userId, new HashSet<>());
        com.tickefy.auth.modules.auth.dto.UserResponse expectedResponse =
                new com.tickefy.auth.modules.auth.dto.UserResponse(
                        userId.toString(), "user@test.com", "Test User", java.util.List.of("AUDIENCE"));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userMapper.toUserResponse(user)).thenReturn(expectedResponse);

        com.tickefy.auth.modules.auth.dto.UserResponse result = userService.getMe(userId);

        assertThat(result).isEqualTo(expectedResponse);
        verify(userMapper, times(1)).toUserResponse(user);
    }
}
