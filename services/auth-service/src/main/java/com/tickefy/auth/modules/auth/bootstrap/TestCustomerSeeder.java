package com.tickefy.auth.modules.auth.bootstrap;

import com.tickefy.auth.modules.auth.entity.RoleEntity;
import com.tickefy.auth.modules.auth.entity.UserEntity;
import com.tickefy.auth.modules.auth.repository.RoleRepository;
import com.tickefy.auth.modules.auth.repository.UserRepository;
import com.tickefy.auth.modules.auth.security.RoleName;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Dev-only seeder: creates fixed test accounts so the FE E2E has stable login credentials.
 *
 * <p>Two kinds of accounts, all created the same way:
 * <ul>
 *   <li>generic E2E customers ({@code e2e.customer1/2@tickefy.local}, AUDIENCE);</li>
 *   <li>personal dev accounts for the team ({@code tanhiep24135@gmail.com} AUDIENCE,
 *       {@code hiepvip22@gmail.com} ADMIN) — convenience logins for manual dev/testing.</li>
 * </ul>
 *
 * <p><b>Dev-gated</b> by {@code app.dev.seed.enabled=true} (default false) — set only in
 * {@code docker-compose.dev.yml} / {@code application-dev.yml}; the prod image leaves it unset so NONE
 * of these accounts (including the ADMIN personal account) are ever created in production. (Distinct
 * from {@link AdminBootstrapRunner}, which is intentionally ungated because a real admin, configured
 * from env vars, is needed in every environment.)
 *
 * <p>Passwords are stored via the real {@link PasswordEncoder} (bcrypt) — never raw. Idempotent per
 * email via {@code existsByEmail}. The plaintext password is never logged. These emails/passwords are
 * well-known dev fixtures and must never exist in a production database.
 */
@Component
@ConditionalOnProperty(name = "app.dev.seed.enabled", havingValue = "true")
public class TestCustomerSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TestCustomerSeeder.class);

    private static final String CUSTOMER_PASSWORD = "Customer@12345";
    private static final String PERSONAL_PASSWORD = "Hiep@12345";

    /** Fixed dev accounts. Role resolved per-account via {@link RoleRepository#findByCode}. */
    private static final List<SeedAccount> ACCOUNTS = List.of(
            new SeedAccount("e2e.customer1@tickefy.local", "E2E Customer 1", RoleName.AUDIENCE.name(), CUSTOMER_PASSWORD),
            new SeedAccount("e2e.customer2@tickefy.local", "E2E Customer 2", RoleName.AUDIENCE.name(), CUSTOMER_PASSWORD),
            new SeedAccount("tanhiep24135@gmail.com", "Tan Hiep Le", RoleName.AUDIENCE.name(), PERSONAL_PASSWORD),
            new SeedAccount("hiepvip22@gmail.com", "Hiep Admin", RoleName.ADMIN.name(), PERSONAL_PASSWORD));

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public TestCustomerSeeder(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** Fixed dev account spec. */
    private record SeedAccount(String email, String fullName, String roleCode, String password) {}

    @Override
    public void run(ApplicationArguments args) {
        for (SeedAccount acc : ACCOUNTS) {
            if (userRepository.existsByEmail(acc.email())) {
                log.info("Dev seed account already exists email={} — skipping", acc.email());
                continue;
            }
            RoleEntity role = roleRepository.findByCode(acc.roleCode()).orElse(null);
            if (role == null) {
                log.warn("Dev seed account skipped email={} — role {} not found", acc.email(), acc.roleCode());
                continue;
            }
            try {
                UserEntity user = UserEntity.builder()
                        .email(acc.email())
                        .passwordHash(passwordEncoder.encode(acc.password()))
                        .fullName(acc.fullName())
                        .enabled(true)
                        .build();
                user.getRoles().add(role);
                userRepository.save(user);
                log.info("Dev seed account created email={} role={}", acc.email(), acc.roleCode());
            } catch (DataIntegrityViolationException e) {
                log.warn("Dev seed account race (unique email) email={} — another instance created it", acc.email());
            } catch (Exception e) {
                log.error("Dev seed account failed email={} — continuing", acc.email(), e);
            }
        }
    }
}
