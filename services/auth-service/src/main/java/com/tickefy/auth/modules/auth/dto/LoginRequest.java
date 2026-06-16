package com.tickefy.auth.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Email(message = "Email must be a valid email address") @NotBlank(message = "Email is required") String email,
        @NotBlank(message = "Password is required") String password) {}
