package com.tickefy.auth.modules.auth.dto;

import java.util.List;

public record UserRolesResponse(String userId, List<String> roles) {}
