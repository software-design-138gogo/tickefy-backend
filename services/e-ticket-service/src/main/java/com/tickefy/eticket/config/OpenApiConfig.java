package com.tickefy.eticket.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Tickefy E-Ticket Service API")
                                .version("v1")
                                .description("E-ticket issuing and verification service for Tickefy."));
    }
}
