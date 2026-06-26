package com.tickefy.event.common.exception;

import com.tickefy.event.common.constants.HeaderConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> details =
                ex.getBindingResult().getFieldErrors().stream()
                        .collect(
                                Collectors.toMap(
                                        FieldError::getField,
                                        fieldError ->
                                                fieldError.getDefaultMessage() != null
                                                        ? fieldError.getDefaultMessage()
                                                        : "Invalid value",
                                        (first, second) -> first,
                                        LinkedHashMap::new));

        return buildProblemDetail(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_ERROR,
                "Invalid request data",
                details,
                request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        Map<String, String> details =
                ex.getConstraintViolations().stream()
                        .collect(
                                Collectors.toMap(
                                        violation -> violation.getPropertyPath().toString(),
                                        violation -> violation.getMessage(),
                                        (first, second) -> first,
                                        LinkedHashMap::new));

        return buildProblemDetail(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_ERROR,
                "Invalid request data",
                details,
                request);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ProblemDetail> handlePropertyReferenceException(
            PropertyReferenceException ex, HttpServletRequest request) {
        return buildProblemDetail(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_ERROR,
                "Invalid sorting property: " + ex.getPropertyName(),
                null,
                request);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        return buildProblemDetail(ex.getStatus(), ex.getErrorCode(), ex.getMessage(), ex.getDetails(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        return buildProblemDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                null,
                request);
    }

    private ResponseEntity<ProblemDetail> buildProblemDetail(
            HttpStatus status, ErrorCode errorCode, String message, Object details, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, message);
        problemDetail.setTitle(errorCode.name());
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        if (requestId != null) {
            problemDetail.setProperty("requestId", requestId);
        }
        
        if (details != null) {
            problemDetail.setProperty("errors", details);
        }
        
        return ResponseEntity.status(status).body(problemDetail);
    }
}
