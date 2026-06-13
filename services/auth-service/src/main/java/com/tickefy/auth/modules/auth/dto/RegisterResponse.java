package com.tickefy.auth.modules.auth.dto;

import java.util.List;

public record RegisterResponse(String userId, String email, String fullName, List<String> roles) {}
