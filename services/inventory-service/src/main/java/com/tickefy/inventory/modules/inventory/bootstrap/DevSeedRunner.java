package com.tickefy.inventory.modules.inventory.bootstrap;

import com.tickefy.inventory.modules.inventory.bootstrap.DevSeedService.SeedResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev-only seeder. Populates a fixed concert + 5 fixed ticket-types on startup as a stable anchor for
 * the FE.
 *
 * <p>Gated by {@code app.dev.seed.enabled} (default false), NOT by Spring profile: both the local dev
 * compose AND the GHCR/prod image compose run with {@code SPRING_PROFILES_ACTIVE=docker}, so a
 * profile guard cannot tell dev from prod. The flag is set only in {@code docker-compose.dev.yml}
 * (and {@code application-dev.yml} for local {@code mvnw spring-boot:run}); the prod image leaves it
 * unset, so the seeder never touches real data.
 */
@Component
@ConditionalOnProperty(name = "app.dev.seed.enabled", havingValue = "true")
public class DevSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeedRunner.class);

    private final DevSeedService devSeedService;

    public DevSeedRunner(DevSeedService devSeedService) {
        this.devSeedService = devSeedService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<SeedResult> results = devSeedService.seedAll();
        printSummary(results);
    }

    private void printSummary(List<SeedResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== DEV INVENTORY SEED (FE anchor) ==========\n");
        sb.append("concertId = ").append(DevSeedService.CONCERT_ID).append('\n');
        sb.append(String.format("%-38s | %-5s | %9s | %5s | %9s%n",
                "ticketTypeId", "name", "price", "total", "available"));
        sb.append("-".repeat(80)).append('\n');
        for (SeedResult r : results) {
            sb.append(String.format("%-38s | %-5s | %9d | %5d | %9d%n",
                    r.ticketTypeId(), r.name(), r.price(), r.total(), r.available()));
        }
        sb.append("====================================================");
        log.info(sb.toString());
    }
}
