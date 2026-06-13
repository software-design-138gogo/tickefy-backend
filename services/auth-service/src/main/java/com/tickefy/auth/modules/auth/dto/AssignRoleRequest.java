package com.tickefy.auth.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record AssignRoleRequest(@NotBlank String role) {}
