package com.tickefy.aibio.modules.health;

import com.tickefy.aibio.common.constants.HeaderConstants;
import com.tickefy.aibio.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @Value("${spring.application.name:ai-bio-service}")
    private String serviceName;

    @GetMapping("/health")
    public ApiResponse<HealthResponse> health(HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        HealthResponse response = new HealthResponse("ok", serviceName, Instant.now());
        return ApiResponse.success(response, requestId);
    }
}
