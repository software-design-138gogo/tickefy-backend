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
 * Dev-only seeder: creates fixed AUDIENCE test customers so the FE E2E has stable login credentials.
 *
 * <p><b>Dev-gated</b> by {@code app.dev.seed.enabled=true} (default false) — set only in
 * {@code docker-compose.dev.yml} / {@code application-dev.yml}; the prod image leaves it unset so these
 * test accounts are NEVER created in production. (Distinct from {@link AdminBootstrapRunner}, which is
 * intentionally ungated because a real admin is needed in every environment.)
 *
 * <p>Passwords are stored via the real {@link PasswordEncoder} (bcrypt) — never raw. Idempotent per
 * email via {@code existsByEmail}. The plaintext password is never logged.
 */
@Component
@ConditionalOnProperty(name = "app.dev.seed.enabled", havingValue = "true")
public class TestCustomerSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TestCustomerSeeder.class);

    private static final String PASSWORD = "Customer@12345";

    /** Fixed E2E customer accounts: {email, fullName}. */
    private static final List<String[]> CUSTOMERS = List.of(
            new String[] {"e2e.customer1@tickefy.local", "E2E Customer 1"},
            new String[] {"e2e.customer2@tickefy.local", "E2E Customer 2"});

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

    @Override
    public void run(ApplicationArguments args) {
        RoleEntity audienceRole = roleRepository.findByCode(RoleName.AUDIENCE.name()).orElse(null);
        if (audienceRole == null) {
            log.warn("Dev test-customer seed skipped — AUDIENCE role not found");
            return;
        }

        for (String[] c : CUSTOMERS) {
            String email = c[0];
            if (userRepository.existsByEmail(email)) {
                log.info("Dev test customer already exists email={} — skipping", email);
                continue;
            }
            try {
                UserEntity user = UserEntity.builder()
                        .email(email)
                        .passwordHash(passwordEncoder.encode(PASSWORD))
                        .fullName(c[1])
                        .enabled(true)
                        .build();
                user.getRoles().add(audienceRole);
                userRepository.save(user);
                log.info("Dev test customer created email={} role=AUDIENCE", email);
            } catch (DataIntegrityViolationException e) {
                log.warn("Dev test customer race (unique email) email={} — another instance created it", email);
            } catch (Exception e) {
                log.error("Dev test customer seed failed email={} — continuing", email, e);
            }
        }
    }
}
