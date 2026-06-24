package com.tickefy.csvingestion.modules.csvimport.event;

import java.util.UUID;

/** Metadata-only payload for VipGuestImportFailed (no PII). */
public record VipGuestImportFailedPayload(UUID importJobId, UUID concertId, String failureReason) {}
