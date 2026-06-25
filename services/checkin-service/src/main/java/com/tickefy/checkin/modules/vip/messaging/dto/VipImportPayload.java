package com.tickefy.checkin.modules.vip.messaging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Payload portion of VipGuestImportCompleted envelope (§6.4).
 * Metadata-only — no PII (§15).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VipImportPayload(
        UUID importJobId,
        UUID concertId,
        String status,
        int totalRows,
        int successRows,
        int failedRows,
        int duplicateRows
) {}
