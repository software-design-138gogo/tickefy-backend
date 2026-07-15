package com.tickefy.csvingestion.modules.csvimport.controller;

import com.tickefy.csvingestion.common.constants.HeaderConstants;
import com.tickefy.csvingestion.common.response.ApiResponse;
import com.tickefy.csvingestion.modules.csvimport.dto.CsvImportAcceptedResponse;
import com.tickefy.csvingestion.modules.csvimport.dto.CsvImportRetryResponse;
import com.tickefy.csvingestion.modules.csvimport.dto.CsvImportStatusResponse;
import com.tickefy.csvingestion.modules.csvimport.service.CsvImportService;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/csv-import")
@PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
public class CsvImportController {

    private final CsvImportService csvImportService;

    public CsvImportController(CsvImportService csvImportService) {
        this.csvImportService = csvImportService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CsvImportAcceptedResponse>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("concertId") UUID concertId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            Authentication auth) {
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        String bearerToken = authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7)
                : authorizationHeader;
        String sub = auth.getName();
        boolean isAdmin = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);

        UUID importJobId = csvImportService.createImportJob(file, concertId, sub, isAdmin, bearerToken);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(new CsvImportAcceptedResponse(importJobId), requestId));
    }

    @GetMapping("/{importJobId}")
    public ResponseEntity<ApiResponse<CsvImportStatusResponse>> getStatus(
            @PathVariable UUID importJobId, Authentication auth) {
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        CsvImportStatusResponse data =
                csvImportService.getStatus(importJobId, auth.getName(), isAdmin(auth));
        return ResponseEntity.ok(ApiResponse.success(data, requestId));
    }

    @PostMapping("/{importJobId}/retry")
    public ResponseEntity<ApiResponse<CsvImportRetryResponse>> retry(
            @PathVariable UUID importJobId, Authentication auth) {
        String requestId = MDC.get(HeaderConstants.REQUEST_ID);
        CsvImportRetryResponse data =
                csvImportService.retry(importJobId, auth.getName(), isAdmin(auth));
        return ResponseEntity.ok(ApiResponse.success(data, requestId));
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }
}
