package com.tickefy.event.common.exception;

public enum ErrorCode {
    VALIDATION_ERROR,
    UNAUTHORIZED,
    FORBIDDEN,
    RESOURCE_NOT_FOUND,
    CONFLICT,
    INTERNAL_SERVER_ERROR,
    SERVICE_UNAVAILABLE,
    
    // Event Service Specific
    CONCERT_NOT_FOUND,
    CONCERT_ACCESS_DENIED,
    OBJECT_STORAGE_UNAVAILABLE
}
