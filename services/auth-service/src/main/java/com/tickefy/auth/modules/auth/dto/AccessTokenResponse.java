package com.tickefy.auth.modules.auth.dto;

public record AccessTokenResponse(String accessToken, String tokenType, long expiresIn) {}
