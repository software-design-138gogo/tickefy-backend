package com.tickefy.auth.modules.auth.bootstrap;

import com.tickefy.auth.modules.auth.entity.UserEntity;
import com.tickefy.auth.modules.auth.repository.RoleRepository;
import com.tickefy.auth.modules.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.admin.email:}")
    private String adminEmail;

    @Value("${app.bootstrap.admin.password:}")
    private String adminPassword;

    @Value("${app.bootstrap.admin.full-name:System Admin}")
    private String adminFullName;

    public AdminBootstrapRunner(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminEmail.isBlank()) {
            log.info("Bootstrap admin disabled (BOOTSTRAP_ADMIN_EMAIL not set)");
            return;
        }

        if (userRepository.existsByEmail(adminEmail)) {
            log.info("Bootstrap admin already exists for email={} — skipping", adminEmail);
            return;
        }

        try {
            var adminRole = roleRepository.findByCode("ADMIN")
                    .orElseThrow(() -> new IllegalStateException("ADMIN role not found in DB"));

            UserEntity admin = UserEntity.builder()
                    .email(adminEmail)
                    .passwordHash(passwordEncoder.encode(adminPassword))
                    .fullName(adminFullName)
                    .enabled(true)
                    .build();
            admin.getRoles().add(adminRole);

            userRepository.save(admin);
            log.info("Bootstrap admin created: email={}", adminEmail);

        } catch (DataIntegrityViolationException e) {
            log.warn("Bootstrap admin race condition (unique email violated) — another instance may have created it. email={}", adminEmail, e);
        } catch (Exception e) {
            log.error("Bootstrap admin creation failed — application continues. email={}", adminEmail, e);
        }
    }
}
