package com.tickefy.csvingestion.modules.csvimport.event;

import java.util.UUID;

/** Metadata-only payload for VipGuestImportCompleted (no PII). */
public record VipGuestImportCompletedPayload(
        UUID importJobId,
        UUID concertId,
        String status,
        int totalRows,
        int successRows,
        int failedRows,
        int duplicateRows) {}
