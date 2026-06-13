package com.tickefy.auth.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Email(message = "Email must be a valid email address") @NotBlank(message = "Email is required") String email,
        @Size(min = 8, message = "Password must be at least 8 characters") @NotBlank(message = "Password is required") String password,
        @NotBlank(message = "Full name is required") String fullName) {}
