package com.tickefy.auth.modules.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tickefy.auth.modules.auth.bootstrap.AdminBootstrapRunner;
import com.tickefy.auth.modules.auth.entity.RoleEntity;
import com.tickefy.auth.modules.auth.entity.UserEntity;
import com.tickefy.auth.modules.auth.repository.RoleRepository;
import com.tickefy.auth.modules.auth.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure unit tests for AdminBootstrapRunner — NO Spring context, NO Docker, NO DB.
 * Constructor injected mocks; @Value fields set via ReflectionTestUtils.setField.
 *
 * AC coverage (maps to AC2.13-9 in UserIntegrationTest, covering unit branch):
 *   - email blank → save NEVER called
 *   - email non-blank, existsByEmail=true → save NEVER called (idempotent)
 *   - existsByEmail=false, ADMIN role found → save called 1x, passwordEncoder.encode called
 *   - save throws DataIntegrityViolationException (race) → run() does NOT throw
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerUnitTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AdminBootstrapRunner runner;

    @BeforeEach
    void setUp() {
        runner = new AdminBootstrapRunner(userRepository, roleRepository, passwordEncoder);
    }

    // -----------------------------------------------------------------------
    // AC: email blank → run() returns immediately, save NEVER called
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("bootstrap disabled when adminEmail blank: save never called")
    void run_blankEmail_saveNeverCalled() throws Exception {
        ReflectionTestUtils.setField(runner, "adminEmail", "");
        ReflectionTestUtils.setField(runner, "adminPassword", "SomePass1!");
        ReflectionTestUtils.setField(runner, "adminFullName", "Admin");

        runner.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).existsByEmail(anyString());
    }

    // -----------------------------------------------------------------------
    // AC: email null → run() returns immediately, save NEVER called
    // (defensive: @Value default is "" but null guard also needed)
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("bootstrap disabled when adminEmail null: save never called")
    void run_nullEmail_saveNeverCalled() throws Exception {
        ReflectionTestUtils.setField(runner, "adminEmail", null);
        ReflectionTestUtils.setField(runner, "adminPassword", "SomePass1!");
        ReflectionTestUtils.setField(runner, "adminFullName", "Admin");

        runner.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // AC: existsByEmail=true → idempotent skip, save NEVER called
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("bootstrap idempotent: existsByEmail=true → save never called")
    void run_emailAlreadyExists_saveNeverCalled() throws Exception {
        ReflectionTestUtils.setField(runner, "adminEmail", "admin@tickefy.com");
        ReflectionTestUtils.setField(runner, "adminPassword", "SomePass1!");
        ReflectionTestUtils.setField(runner, "adminFullName", "Admin");

        when(userRepository.existsByEmail("admin@tickefy.com")).thenReturn(true);

        runner.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    // -----------------------------------------------------------------------
    // AC: existsByEmail=false + ADMIN role found → save called once, encode called
    // User saved has ADMIN role in its roles set.
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("bootstrap creates admin: existsByEmail=false + ADMIN role → save called 1x, password encoded")
    void run_newAdmin_saveCalledWithEncodedPasswordAndAdminRole() throws Exception {
        String email = "admin@tickefy.com";
        String plainPassword = "AdminPass1!";
        String encodedPassword = "$2a$10$hashedvalue";

        ReflectionTestUtils.setField(runner, "adminEmail", email);
        ReflectionTestUtils.setField(runner, "adminPassword", plainPassword);
        ReflectionTestUtils.setField(runner, "adminFullName", "System Admin");

        when(userRepository.existsByEmail(email)).thenReturn(false);

        RoleEntity adminRole = RoleEntity.builder()
                .id((short) 4)
                .code("ADMIN")
                .build();
        when(roleRepository.findByCode("ADMIN")).thenReturn(Optional.of(adminRole));
        when(passwordEncoder.encode(plainPassword)).thenReturn(encodedPassword);

        // Capture the saved entity using ArgumentCaptor
        org.mockito.ArgumentCaptor<UserEntity> captor =
                org.mockito.ArgumentCaptor.forClass(UserEntity.class);

        runner.run(new DefaultApplicationArguments());

        verify(passwordEncoder, times(1)).encode(plainPassword);
        verify(userRepository, times(1)).save(captor.capture());

        UserEntity saved = captor.getValue();
        // password must be the encoded one (not plaintext)
        org.assertj.core.api.Assertions.assertThat(saved.getPasswordHash()).isEqualTo(encodedPassword);
        // email set correctly
        org.assertj.core.api.Assertions.assertThat(saved.getEmail()).isEqualTo(email);
        // ADMIN role must be in the roles set
        org.assertj.core.api.Assertions.assertThat(saved.getRoles())
                .extracting("code")
                .containsExactly("ADMIN");
    }

    // -----------------------------------------------------------------------
    // AC: save throws DataIntegrityViolationException (race condition) →
    //     run() does NOT re-throw, graceful handling
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("bootstrap race condition: DataIntegrityViolationException on save → run() does not throw")
    void run_dataintegrityViolation_doesNotThrow() throws Exception {
        String email = "admin@tickefy.com";

        ReflectionTestUtils.setField(runner, "adminEmail", email);
        ReflectionTestUtils.setField(runner, "adminPassword", "AdminPass1!");
        ReflectionTestUtils.setField(runner, "adminFullName", "System Admin");

        when(userRepository.existsByEmail(email)).thenReturn(false);

        RoleEntity adminRole = RoleEntity.builder()
                .id((short) 4)
                .code("ADMIN")
                .build();
        when(roleRepository.findByCode("ADMIN")).thenReturn(Optional.of(adminRole));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hash");
        when(userRepository.save(any(UserEntity.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violated"));

        // Must NOT throw
        org.assertj.core.api.Assertions.assertThatCode(
                () -> runner.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // AC: ADMIN role missing in DB (misconfigured DB) →
    //     run() does NOT throw externally (Exception caught in outer catch)
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("bootstrap ADMIN role missing in DB: run() does not throw (graceful)")
    void run_adminRoleMissingInDb_doesNotThrow() throws Exception {
        String email = "admin@tickefy.com";

        ReflectionTestUtils.setField(runner, "adminEmail", email);
        ReflectionTestUtils.setField(runner, "adminPassword", "AdminPass1!");
        ReflectionTestUtils.setField(runner, "adminFullName", "System Admin");

        when(userRepository.existsByEmail(email)).thenReturn(false);
        // ADMIN role not found → orElseThrow() will throw IllegalStateException
        when(roleRepository.findByCode("ADMIN")).thenReturn(Optional.empty());

        // The outer catch(Exception e) in run() swallows this
        org.assertj.core.api.Assertions.assertThatCode(
                () -> runner.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();

        // save must NOT have been called
        verify(userRepository, never()).save(any());
    }
}
