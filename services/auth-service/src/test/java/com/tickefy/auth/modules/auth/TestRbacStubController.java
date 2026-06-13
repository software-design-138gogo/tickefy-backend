package com.tickefy.auth.modules.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only stub controller to exercise @PreAuthorize method security.
 * Only loaded under "test" profile — NEVER deployed to production.
 */
@RestController
@RequestMapping("/test-rbac")
@Profile("test")
public class TestRbacStubController {

    @GetMapping("/organizer-only")
    @PreAuthorize("hasRole('ORGANIZER')")
    public String organizerOnly() {
        return "organizer-resource";
    }

    @GetMapping("/checkin-only")
    @PreAuthorize("hasRole('CHECKIN_STAFF')")
    public String checkinOnly() {
        return "checkin-resource";
    }

    @GetMapping("/admin-only")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminOnly() {
        return "admin-resource";
    }

    @GetMapping("/audience-only")
    @PreAuthorize("hasRole('AUDIENCE')")
    public String audienceOnly() {
        return "audience-resource";
    }
}
