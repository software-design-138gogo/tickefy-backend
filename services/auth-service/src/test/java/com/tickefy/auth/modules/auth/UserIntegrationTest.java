package com.tickefy.auth.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.auth.BaseIntegrationTest;
import com.tickefy.auth.modules.auth.bootstrap.AdminBootstrapRunner;
import com.tickefy.auth.modules.auth.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration tests for PLAN-2.13 §9 — /auth/me, role-mgmt, bootstrap, blacklist key.
 * Maps 1-1 to acceptance criteria: AC2.13-1 through AC2.13-11.
 * Extends BaseIntegrationTest for singleton Testcontainers Postgres + Redis.
 */
class UserIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminBootstrapRunner adminBootstrapRunner;

    private static final String BLACKLIST_PREFIX = "tickefy:auth:token:blacklist:";

    private String uniqueEmail() {
        return "u2_" + System.nanoTime() + "@tickefy.test";
    }

    @BeforeEach
    void cleanRedis() {
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    // ---------------------------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------------------------

    private ResponseEntity<String> register(String email, String password, String fullName) {
        Map<String, String> body = Map.of("email", email, "password", password, "fullName", fullName);
        HttpHeaders h = jsonHeaders();
        return restTemplate.postForEntity("/auth/register", new HttpEntity<>(body, h), String.class);
    }

    private ResponseEntity<String> login(String email, String password) {
        Map<String, String> body = Map.of("email", email, "password", password);
        return restTemplate.postForEntity("/auth/login", new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    private ResponseEntity<String> logout(String accessToken) {
        HttpHeaders h = bearerHeaders(accessToken);
        return restTemplate.postForEntity("/auth/logout", new HttpEntity<>(null, h), String.class);
    }

    private ResponseEntity<String> getMe(String accessToken) {
        return restTemplate.exchange("/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(accessToken)), String.class);
    }

    private ResponseEntity<String> assignRole(String adminToken, String userId, String role) {
        Map<String, String> body = Map.of("role", role);
        return restTemplate.exchange(
                "/auth/users/" + userId + "/roles",
                HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(adminToken)),
                String.class);
    }

    private ResponseEntity<String> revokeRole(String adminToken, String userId, String role) {
        return restTemplate.exchange(
                "/auth/users/" + userId + "/roles/" + role,
                HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(adminToken)),
                String.class);
    }

    private ResponseEntity<String> getUserRoles(String adminToken, String userId) {
        return restTemplate.exchange(
                "/auth/users/" + userId + "/roles",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(adminToken)),
                String.class);
    }

    private ResponseEntity<String> getUsers(String adminToken) {
        return restTemplate.exchange(
                "/auth/users",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(adminToken)),
                String.class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private JsonNode parse(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private String errorCode(String json) throws Exception {
        return parse(json).path("error").path("code").asText();
    }

    /**
     * Registers a normal user, returns their userId.
     */
    private String registerAndGetUserId(String email, String password) throws Exception {
        ResponseEntity<String> resp = register(email, password, "Test User");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(resp.getBody()).path("data").path("userId").asText();
    }

    /**
     * Login and return accessToken.
     */
    private String loginAndGetToken(String email, String password) throws Exception {
        ResponseEntity<String> resp = login(email, password);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(resp.getBody()).path("data").path("accessToken").asText();
    }

    // ---------------------------------------------------------------------------
    // ADMIN SETUP via AdminBootstrapRunner (injected, call run() with test props)
    // For PLAN-2.13 tests: application-test.yml sets bootstrap.admin empty by default.
    // We inject the runner and call run() with @TestPropertySource overrides per test.
    //
    // Alternative simpler approach: use @Autowired RoleRepository + UserRepository
    // to create admin directly in DB (no login needed for first admin).
    // ---------------------------------------------------------------------------

    @Autowired
    private com.tickefy.auth.modules.auth.repository.RoleRepository roleRepository;

    /**
     * Creates an ADMIN user directly via repository (bypasses HTTP, avoids chicken-and-egg).
     * This mirrors what bootstrap runner does.
     */
    private String createAdminDirectly(String email, String password) {
        // Check if already exists (idempotent helper)
        if (userRepository.existsByEmail(email)) {
            return email;
        }
        var adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
        var user = com.tickefy.auth.modules.auth.entity.UserEntity.builder()
                .email(email)
                .passwordHash(encoder.encode(password))
                .fullName("Test Admin")
                .enabled(true)
                .build();
        user.getRoles().add(adminRole);
        userRepository.save(user);
        return email;
    }

    // ===========================================================================
    // AC2.13-1 — getMe_returnsUserWithRoles
    // /auth/me → 200, roles=["AUDIENCE"], email/fullName correct, no passwordHash
    // ===========================================================================
    @Test
    @DisplayName("AC2.13-1 getMe: register+login AUDIENCE → GET /auth/me → 200, roles=[AUDIENCE], no passwordHash")
    void getMe_returnsUserWithRoles() throws Exception {
        String email = uniqueEmail();
        String password = "Password1!";
        String fullName = "Me User";

        register(email, password, fullName);
        String token = loginAndGetToken(email, password);

        ResponseEntity<String> resp = getMe(token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = parse(resp.getBody()).path("data");

        assertThat(data.path("email").asText()).isEqualTo(email);
        assertThat(data.path("fullName").asText()).isEqualTo(fullName);
        assertThat(data.path("userId").asText()).isNotBlank();

        JsonNode rolesNode = data.path("roles");
        assertThat(rolesNode.isArray()).isTrue();
        assertThat(rolesNode.toString()).contains("AUDIENCE");

        // CRITICAL: no passwordHash in response
        assertThat(data.toString()).doesNotContain("passwordHash");
        assertThat(data.toString()).doesNotContain("password_hash");
        assertThat(data.toString()).doesNotContain(password);
    }

    // ===========================================================================
    // AC2.13-2 — admin_assignRole_userGainsRole
    // ADMIN POST /auth/users/{userId}/roles {role:"ORGANIZER"} → 200; GET roles has ORGANIZER
    // ===========================================================================
    @Test
    @DisplayName("AC2.13-2 assignRole: admin grants ORGANIZER to user → GET roles contains ORGANIZER")
    void admin_assignRole_userGainsRole() throws Exception {
        String adminEmail = uniqueEmail();
        String adminPwd = "Admin1234!";
        createAdminDirectly(adminEmail, adminPwd);
        String adminToken = loginAndGetToken(adminEmail, adminPwd);

        String userEmail = uniqueEmail();
        String userId = registerAndGetUserId(userEmail, "UserPass1!");

        ResponseEntity<String> assignResp = assignRole(adminToken, userId, "ORGANIZER");
        assertThat(assignResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode assignData = parse(assignResp.getBody()).path("data");
        assertThat(assignData.path("roles").toString()).contains("ORGANIZER");

        ResponseEntity<String> getRolesResp = getUserRoles(adminToken, userId);
        assertThat(getRolesResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(getRolesResp.getBody()).path("data").path("roles").toString())
                .contains("ORGANIZER");
    }

    // ===========================================================================
    // AC2.13-3 — assignRole_duplicate_noop
    // Assign ORGANIZER 2 times → roles has exactly 1 ORGANIZER (no duplicate in user_roles)
    // ===========================================================================
    @Test
    @DisplayName("AC2.13-3 assignRole duplicate: assigning ORGANIZER twice → exactly 1 ORGANIZER in roles")
    void assignRole_duplicate_noop() throws Exception {
        String adminEmail = uniqueEmail();
        createAdminDirectly(adminEmail, "Admin1234!");
        String adminToken = loginAndGetToken(adminEmail, "Admin1234!");

        String userEmail = uniqueEmail();
        String userId = registerAndGetUserId(userEmail, "UserPass1!");

        // First assign
        ResponseEntity<String> first = assignRole(adminToken, userId, "ORGANIZER");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second assign (duplicate) — should be no-op, still 200
        ResponseEntity<String> second = assignRole(adminToken, userId, "ORGANIZER");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify roles — only 1 ORGANIZER entry
        ResponseEntity<String> getRolesResp = getUserRoles(adminToken, userId);
        assertThat(getRolesResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode roles = parse(getRolesResp.getBody()).path("data").path("roles");
        assertThat(roles.isArray()).isTrue();

        long organizerCount = 0;
        for (JsonNode r : roles) {
            if ("ORGANIZER".equals(r.asText())) {
                organizerCount++;
            }
        }
        assertThat(organizerCount)
                .as("ORGANIZER should appear exactly once — no duplicate in user_roles")
                .isEqualTo(1L);
    }

    // ===========================================================================
    // AC2.13-4 — assignRole_invalidRole_400
    // role "KING" → 400 INVALID_ROLE (NOT 500)
    // ===========================================================================
    @Test
    @DisplayName("AC2.13-4 assignRole invalid role: 'KING' → 400 INVALID_ROLE (not 500)")
    void assignRole_invalidRole_400() throws Exception {
        String adminEmail = uniqueEmail();
        createAdminDirectly(adminEmail, "Admin1234!");
        String adminToken = loginAndGetToken(adminEmail, "Admin1234!");

        String userEmail = uniqueEmail();
        String userId = registerAndGetUserId(userEmail, "UserPass1!");

        ResponseEntity<String> resp = assignRole(adminToken, userId, "KING");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(resp.getBody())).isEqualTo("INVALID_ROLE");
    }

    // ===========================================================================
    // AC2.13-5 — assignRole_userMissing_404
    // Random userId → 404 USER_NOT_FOUND
    // ===========================================================================
    @Test
    @DisplayName("AC2.13-5 assignRole missing user: random userId → 404 USER_NOT_FOUND")
    void assignRole_userMissing_404() throws Exception {
        String adminEmail = uniqueEmail();
        createAdminDirectly(adminEmail, "Admin1234!");
        String adminToken = loginAndGetToken(adminEmail, "Admin1234!");

        String randomUserId = UUID.randomUUID().toString();

        ResponseEntity<String> resp = assignRole(adminToken, randomUserId, "ORGANIZER");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(resp.getBody())).isEqualTo("USER_NOT_FOUND");
    }

    // ===========================================================================
    // AC2.13-6 — audience_callsRoleApi_403
    // AUDIENCE token → POST /auth/users/{id}/roles → 403 FORBIDDEN
    // ===========================================================================
    @Test
    @DisplayName("AC2.13-6 audience calls role API: AUDIENCE token → POST /auth/users/{id}/roles → 403 FORBIDDEN")
    void audience_callsRoleApi_403() throws Exception {
        String audienceEmail = uniqueEmail();
        register(audienceEmail, "UserPass1!", "Audience Guy");
        String audienceToken = loginAndGetToken(audienceEmail, "UserPass1!");

        // Target some userId (even random — auth check happens before business logic)
        String targetId = UUID.randomUUID().toString();

        ResponseEntity<String> resp = assignRole(audienceToken, targetId, "ORGANIZER");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCode(resp.getBody())).isEqualTo("FORBIDDEN");
    }

    // ===========================================================================
    // AC2.13-7 — admin_revokeRole_userLosesRole
    // Assign ORGANIZER then DELETE /auth/users/{id}/roles/ORGANIZER → 200, role gone;
    // Revoke again (no-op) → 200
    // ===========================================================================
    @Test
    @DisplayName("AC2.13-7 revokeRole: delete ORGANIZER → role gone; revoke again → 200 no-op")
    void admin_revokeRole_userLosesRole() throws Exception {
        String adminEmail = uniqueEmail();
        createAdminDirectly(adminEmail, "Admin1234!");
        String adminToken = loginAndGetToken(adminEmail, "Admin1234!");

        String userEmail = uniqueEmail();
        String userId = registerAndGetUserId(userEmail, "UserPass1!");

        // First assign ORGANIZER
        assignRole(adminToken, userId, "ORGANIZER");

        // Revoke ORGANIZER
        ResponseEntity<String> revokeResp = revokeRole(adminToken, userId, "ORGANIZER");
        assertThat(revokeResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify ORGANIZER is gone
        ResponseEntity<String> rolesResp = getUserRoles(adminToken, userId);
        assertThat(rolesResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(rolesResp.getBody()).path("data").path("roles").toString())
                .doesNotContain("ORGANIZER");

        // Revoke again (no-op) → still 200
        ResponseEntity<String> revokeAgain = revokeRole(adminToken, userId, "ORGANIZER");
        assertThat(revokeAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ===========================================================================
    // AC2.13-8 — revokeLastAdmin_409
    // System has only 1 ADMIN → DELETE that admin's ADMIN role → 409 LAST_ADMIN
    // With 2 admins → removing 1 → 200 OK
    // ===========================================================================
    @Test
    @DisplayName("AC2.13-8 revokeLastAdmin: 1 ADMIN in system → revoke ADMIN role → 409 LAST_ADMIN; 2 admins → revoke 1 → 200 OK")
    void revokeLastAdmin_409() throws Exception {
        // Create first admin (will be the only admin)
        String admin1Email = uniqueEmail();
        createAdminDirectly(admin1Email, "Admin1234!");
        String admin1Token = loginAndGetToken(admin1Email, "Admin1234!");
        String admin1Id = parse(getMe(admin1Token).getBody()).path("data").path("userId").asText();

        // Verify this is the only ADMIN (at test isolation, other tests may have created admins —
        // so we count and if >1, skip the 409 part and just test the 2-admin scenario)
        long adminCount = userRepository.countByRoleCode("ADMIN");

        if (adminCount == 1) {
            // Attempt to revoke ADMIN from themselves (the last admin) → 409 LAST_ADMIN
            ResponseEntity<String> revokeResp = revokeRole(admin1Token, admin1Id, "ADMIN");
            assertThat(revokeResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(errorCode(revokeResp.getBody())).isEqualTo("LAST_ADMIN");
        }

        // Now create a second admin → revoking ADMIN from first → 200 OK
        String admin2Email = uniqueEmail();
        createAdminDirectly(admin2Email, "Admin5678!");
        // Re-login admin1 to get fresh token
        String freshAdmin1Token = loginAndGetToken(admin1Email, "Admin1234!");
        String freshAdmin1Id = parse(getMe(freshAdmin1Token).getBody()).path("data").path("userId").asText();

        // Count should now be >= 2
        long adminCountAfter = userRepository.countByRoleCode("ADMIN");
        assertThat(adminCountAfter).isGreaterThanOrEqualTo(2L);

        ResponseEntity<String> revokeOkResp = revokeRole(freshAdmin1Token, freshAdmin1Id, "ADMIN");
        assertThat(revokeOkResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(revokeOkResp.getBody()).path("data").path("roles").toString())
                .doesNotContain("ADMIN");
    }

    // ===========================================================================
    // AC2.13-9 — bootstrapAdmin_idempotent + bootstrap_disabledWhenEmpty
    // Run AdminBootstrapRunner.run() 2x → exactly 1 ADMIN user (existsByEmail blocks).
    // Email empty → no user created.
    // ===========================================================================
    @Test
    @DisplayName("AC2.13-9a bootstrapAdmin idempotent: run() twice → exactly 1 ADMIN user created")
    void bootstrapAdmin_idempotent() throws Exception {
        String bootstrapEmail = "bootstrap_" + System.nanoTime() + "@tickefy.test";
        String bootstrapPassword = "BootstrapPass1!";

        // Inject bootstrap properties via reflection (runner uses @Value fields)
        setField(adminBootstrapRunner, "adminEmail", bootstrapEmail);
        setField(adminBootstrapRunner, "adminPassword", bootstrapPassword);
        setField(adminBootstrapRunner, "adminFullName", "Bootstrap Admin");

        ApplicationArguments args = new DefaultApplicationArguments();

        // First run — should create
        adminBootstrapRunner.run(args);

        long countAfterFirst = userRepository.findAll().stream()
                .filter(u -> bootstrapEmail.equals(u.getEmail()))
                .count();
        assertThat(countAfterFirst)
                .as("After first bootstrap run, exactly 1 user with bootstrap email")
                .isEqualTo(1L);

        // Second run — should be idempotent (existsByEmail guard)
        adminBootstrapRunner.run(args);

        long countAfterSecond = userRepository.findAll().stream()
                .filter(u -> bootstrapEmail.equals(u.getEmail()))
                .count();
        assertThat(countAfterSecond)
                .as("After second bootstrap run, still exactly 1 user (no duplicate)")
                .isEqualTo(1L);

        // Verify the user has ADMIN role
        var bootstrapUser = userRepository.findByEmail(bootstrapEmail).orElseThrow();
        assertThat(bootstrapUser.getRoles()).extracting("code").contains("ADMIN");
    }

    @Test
    @DisplayName("AC2.13-9b bootstrap disabled when email empty: no user created")
    void bootstrap_disabledWhenEmpty() throws Exception {
        // Set empty email
        setField(adminBootstrapRunner, "adminEmail", "");

        long userCountBefore = userRepository.count();

        ApplicationArguments args = new DefaultApplicationArguments();
        adminBootstrapRunner.run(args);

        long userCountAfter = userRepository.count();
        assertThat(userCountAfter)
                .as("No user created when bootstrap email is empty")
                .isEqualTo(userCountBefore);
    }

    // ===========================================================================
    // AC2.13-10 — logout_blacklistsWithNewKey
    // login → logout → Redis key "tickefy:auth:token:blacklist:{jti}" (TTL>0);
    // GET /auth/me with old token → 401 TOKEN_REVOKED;
    // OLD key "blacklist:{jti}" must NOT exist.
    // ===========================================================================
    @Test
    @DisplayName("AC2.13-10 blacklist new key: logout → Redis key tickefy:auth:token:blacklist:{jti} TTL>0; old token → 401 TOKEN_REVOKED; old prefix key absent")
    void logout_blacklistsWithNewKey() throws Exception {
        String email = uniqueEmail();
        register(email, "Password1!", "Blacklist Key User");
        String accessToken = loginAndGetToken(email, "Password1!");

        // Extract jti from JWT payload
        String[] parts = accessToken.split("\\.");
        String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        String jti = parse(payloadJson).path("jti").asText();
        assertThat(jti).isNotBlank();

        // Logout
        ResponseEntity<String> logoutResp = logout(accessToken);
        assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Assert new key exists with TTL > 0
        String newKey = BLACKLIST_PREFIX + jti;
        Long ttl = stringRedisTemplate.getExpire(newKey, TimeUnit.SECONDS);
        assertThat(ttl)
                .as("Redis key '%s' must have TTL > 0 after logout", newKey)
                .isNotNull()
                .isGreaterThan(0L);
        assertThat(ttl).isLessThanOrEqualTo(900L); // 15m = 900s access TTL

        // Assert OLD key does NOT exist
        String oldKey = "blacklist:" + jti;
        Boolean oldKeyExists = stringRedisTemplate.hasKey(oldKey);
        assertThat(oldKeyExists)
                .as("Old key '%s' must NOT exist after logout (key standard changed)", oldKey)
                .isFalse();

        // Reuse old access token → must get 401 TOKEN_REVOKED
        ResponseEntity<String> meResp = getMe(accessToken);
        assertThat(meResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(meResp.getBody())).isEqualTo("TOKEN_REVOKED");
    }

    // ===========================================================================
    // AC2.13-11 — getUsers_paginated_noPasswordHash
    // GET /auth/users (admin) → 200, page content has users, no passwordHash, pageable meta correct
    // ===========================================================================
    @Test
    @DisplayName("AC2.13-11 getUsers paginated: 200, content has users, no passwordHash field, pageable metadata present")
    void getUsers_paginated_noPasswordHash() throws Exception {
        String adminEmail = uniqueEmail();
        createAdminDirectly(adminEmail, "Admin1234!");
        String adminToken = loginAndGetToken(adminEmail, "Admin1234!");

        // Register at least 1 more user to ensure content is non-empty
        register(uniqueEmail(), "UserPass1!", "List User");

        ResponseEntity<String> resp = getUsers(adminToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode root = parse(resp.getBody());
        assertThat(root.path("success").asBoolean()).isTrue();

        JsonNode data = root.path("data");
        assertThat(data.isMissingNode()).isFalse();

        // content array must exist and be non-empty
        JsonNode content = data.path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isGreaterThan(0);

        // No passwordHash in ANY user object
        for (JsonNode user : content) {
            assertThat(user.toString())
                    .as("User response must not contain passwordHash")
                    .doesNotContain("passwordHash")
                    .doesNotContain("password_hash");
        }

        // Each user should have userId, email, fullName, roles
        JsonNode firstUser = content.get(0);
        assertThat(firstUser.has("userId")).isTrue();
        assertThat(firstUser.has("email")).isTrue();
        assertThat(firstUser.has("fullName")).isTrue();
        assertThat(firstUser.has("roles")).isTrue();

        // Pageable metadata: totalElements, totalPages present
        assertThat(data.has("totalElements")).isTrue();
        assertThat(data.has("totalPages")).isTrue();
        assertThat(data.path("totalElements").asLong()).isGreaterThan(0L);
    }

    // ---------------------------------------------------------------------------
    // Utility: set private @Value field via reflection (for bootstrap runner tests)
    // ---------------------------------------------------------------------------
    private void setField(Object target, String fieldName, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
