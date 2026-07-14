package com.tickefy.auth.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tickefy.auth.BaseIntegrationTest;
import com.tickefy.auth.modules.auth.bootstrap.TestCustomerSeeder;
import com.tickefy.auth.modules.auth.entity.RoleEntity;
import com.tickefy.auth.modules.auth.entity.UserEntity;
import com.tickefy.auth.modules.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

/**
 * TestCustomerSeeder is dev-gated (app.dev.seed.enabled). This test enables it so the seeder auto-runs
 * at context startup, then verifies the 2 fixed AUDIENCE customers exist with a real bcrypt hash and
 * that re-running is idempotent (no duplicate).
 */
@TestPropertySource(properties = "app.dev.seed.enabled=true")
class TestCustomerSeederTest extends BaseIntegrationTest {

    private static final String EMAIL1 = "e2e.customer1@tickefy.local";
    private static final String EMAIL2 = "e2e.customer2@tickefy.local";
    private static final String PASSWORD = "Customer@12345";

    @Autowired private TestCustomerSeeder seeder;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void seedsTwoAudienceCustomers_realHash_idempotent() {
        // Seeder already auto-ran at startup. Verify both accounts.
        assertThat(userRepository.existsByEmail(EMAIL1)).isTrue();
        assertThat(userRepository.existsByEmail(EMAIL2)).isTrue();

        UserEntity c1 = userRepository.findByEmail(EMAIL1).orElseThrow();
        assertThat(c1.getRoles()).extracting(RoleEntity::getCode).contains("AUDIENCE");
        assertThat(c1.getPasswordHash()).isNotEqualTo(PASSWORD); // stored hashed, not raw
        assertThat(passwordEncoder.matches(PASSWORD, c1.getPasswordHash())).isTrue();

        // Re-run — idempotent guard, no second row.
        seeder.run(null);
        long c1Count = userRepository.findAll().stream()
                .filter(u -> EMAIL1.equals(u.getEmail()))
                .count();
        assertThat(c1Count).isEqualTo(1L);
    }
}
