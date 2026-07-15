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
 * at context startup, then verifies all 4 fixed accounts (2 E2E AUDIENCE customers + 2 personal dev
 * accounts, one AUDIENCE + one ADMIN) exist with real bcrypt hashes, correct roles, and that
 * re-running is idempotent (no duplicate).
 */
@TestPropertySource(properties = "app.dev.seed.enabled=true")
class TestCustomerSeederTest extends BaseIntegrationTest {

    private static final String CUSTOMER1 = "e2e.customer1@tickefy.local";
    private static final String CUSTOMER2 = "e2e.customer2@tickefy.local";
    private static final String PERSONAL_AUDIENCE = "tanhiep24135@gmail.com";
    private static final String PERSONAL_ADMIN = "hiepvip22@gmail.com";
    private static final String CUSTOMER_PASSWORD = "Customer@12345";
    private static final String PERSONAL_PASSWORD = "Hiep@12345";

    @Autowired private TestCustomerSeeder seeder;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void seedsAllFourAccounts_correctRoles_realHash_idempotent() {
        // Seeder already auto-ran at startup. All 4 accounts present.
        for (String email : new String[] {CUSTOMER1, CUSTOMER2, PERSONAL_AUDIENCE, PERSONAL_ADMIN}) {
            assertThat(userRepository.existsByEmail(email)).as("account %s", email).isTrue();
        }

        // Generic customer: AUDIENCE + real hash.
        UserEntity c1 = userRepository.findByEmail(CUSTOMER1).orElseThrow();
        assertThat(c1.getRoles()).extracting(RoleEntity::getCode).contains("AUDIENCE");
        assertThat(passwordEncoder.matches(CUSTOMER_PASSWORD, c1.getPasswordHash())).isTrue();

        // Personal AUDIENCE account.
        UserEntity pa = userRepository.findByEmail(PERSONAL_AUDIENCE).orElseThrow();
        assertThat(pa.getRoles()).extracting(RoleEntity::getCode).containsExactly("AUDIENCE");
        assertThat(pa.getPasswordHash()).isNotEqualTo(PERSONAL_PASSWORD); // stored hashed, not raw
        assertThat(passwordEncoder.matches(PERSONAL_PASSWORD, pa.getPasswordHash())).isTrue();

        // Personal ADMIN account — role must be ADMIN.
        UserEntity admin = userRepository.findByEmail(PERSONAL_ADMIN).orElseThrow();
        assertThat(admin.getRoles()).extracting(RoleEntity::getCode).contains("ADMIN");
        assertThat(passwordEncoder.matches(PERSONAL_PASSWORD, admin.getPasswordHash())).isTrue();

        // Re-run — idempotent guard, no duplicates for any account.
        seeder.run(null);
        for (String email : new String[] {CUSTOMER1, CUSTOMER2, PERSONAL_AUDIENCE, PERSONAL_ADMIN}) {
            long count = userRepository.findAll().stream()
                    .filter(u -> email.equals(u.getEmail()))
                    .count();
            assertThat(count).as("no duplicate for %s", email).isEqualTo(1L);
        }
    }
}
