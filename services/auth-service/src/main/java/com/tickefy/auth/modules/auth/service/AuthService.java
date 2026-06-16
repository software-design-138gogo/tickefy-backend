package com.tickefy.auth.modules.auth.service;

import com.tickefy.auth.common.exception.ApiException;
import com.tickefy.auth.common.exception.ErrorCode;
import com.tickefy.auth.modules.auth.dto.AccessTokenResponse;
import com.tickefy.auth.modules.auth.dto.LoginRequest;
import com.tickefy.auth.modules.auth.dto.RefreshTokenRequest;
import com.tickefy.auth.modules.auth.dto.RegisterRequest;
import com.tickefy.auth.modules.auth.dto.RegisterResponse;
import com.tickefy.auth.modules.auth.dto.TokenResponse;
import com.tickefy.auth.modules.auth.entity.RefreshTokenEntity;
import com.tickefy.auth.modules.auth.entity.RoleEntity;
import com.tickefy.auth.modules.auth.entity.UserEntity;
import com.tickefy.auth.modules.auth.mapper.UserMapper;
import com.tickefy.auth.modules.auth.repository.RefreshTokenRepository;
import com.tickefy.auth.modules.auth.repository.RoleRepository;
import com.tickefy.auth.modules.auth.repository.UserRepository;
import com.tickefy.auth.modules.auth.security.JwtBlacklistService;
import com.tickefy.auth.modules.auth.security.JwtTokenProvider;
import com.tickefy.auth.modules.auth.security.RoleName;
import io.jsonwebtoken.Claims;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtBlacklistService jwtBlacklistService;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;

    // Precomputed bcrypt hash used as a constant-time guard when the email is unknown,
    // so login latency does not reveal whether an email exists (anti user-enumeration).
    private final String dummyHash;

    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenService refreshTokenService,
            JwtTokenProvider jwtTokenProvider,
            JwtBlacklistService jwtBlacklistService,
            PasswordEncoder passwordEncoder,
            UserMapper userMapper) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenService = refreshTokenService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtBlacklistService = jwtBlacklistService;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.dummyHash = passwordEncoder.encode("timing-guard-not-a-real-password");
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ApiException(
                    ErrorCode.EMAIL_ALREADY_EXISTS,
                    "Email already exists",
                    HttpStatus.CONFLICT);
        }

        RoleEntity audienceRole = roleRepository
                .findByCode(RoleName.AUDIENCE.name())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        "Default role AUDIENCE not found",
                        HttpStatus.INTERNAL_SERVER_ERROR));

        String hashedPassword = passwordEncoder.encode(request.password());
        UserEntity user = UserEntity.builder()
                .email(request.email())
                .passwordHash(hashedPassword)
                .fullName(request.fullName())
                .enabled(true)
                .build();
        user.getRoles().add(audienceRole);

        userRepository.save(user);
        return userMapper.toRegisterResponse(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByEmail(request.email()).orElse(null);
        if (user == null) {
            // Run bcrypt even when the user is absent to keep response time constant.
            passwordEncoder.matches(request.password(), dummyHash);
            throw new ApiException(
                    ErrorCode.INVALID_CREDENTIALS,
                    "Invalid email or password",
                    HttpStatus.UNAUTHORIZED);
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(
                    ErrorCode.INVALID_CREDENTIALS,
                    "Invalid email or password",
                    HttpStatus.UNAUTHORIZED);
        }

        List<String> roles = user.getRoles().stream()
                .map(RoleEntity::getCode)
                .collect(Collectors.toList());

        JwtTokenProvider.AccessTokenResult accessResult =
                jwtTokenProvider.issueAccessToken(user.getId().toString(), user.getEmail(), roles);

        String refreshRaw = refreshTokenService.createRefreshToken(user.getId());

        long expiresIn = accessResult.expiresAt().getEpochSecond() - Instant.now().getEpochSecond();

        return new TokenResponse(accessResult.token(), refreshRaw, "Bearer", expiresIn);
    }

    @Transactional
    public AccessTokenResponse refresh(RefreshTokenRequest request) {
        RefreshTokenEntity refreshToken = refreshTokenService.verifyRefreshToken(request.refreshToken());

        UUID userId = refreshToken.getUserId();
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INVALID_TOKEN,
                        "User not found for refresh token",
                        HttpStatus.UNAUTHORIZED));

        List<String> roles = user.getRoles().stream()
                .map(RoleEntity::getCode)
                .collect(Collectors.toList());

        JwtTokenProvider.AccessTokenResult accessResult =
                jwtTokenProvider.issueAccessToken(user.getId().toString(), user.getEmail(), roles);

        long expiresIn = accessResult.expiresAt().getEpochSecond() - Instant.now().getEpochSecond();

        return new AccessTokenResponse(accessResult.token(), "Bearer", expiresIn);
    }

    @Transactional
    public void logout(String bearerToken) {
        String token = bearerToken.startsWith("Bearer ") ? bearerToken.substring(7) : bearerToken;
        Claims claims = jwtTokenProvider.parseAccessToken(token);

        String jti = claims.getId();
        Instant exp = claims.getExpiration().toInstant();
        Instant now = Instant.now();

        Duration ttl = Duration.between(now, exp);
        if (ttl.isPositive()) {
            jwtBlacklistService.blacklist(jti, ttl);
        }

        String userId = claims.getSubject();
        refreshTokenService.revokeAllForUser(UUID.fromString(userId));
    }
}
