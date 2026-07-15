package com.tickefy.checkin.modules.vip.messaging.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Envelope for VipGuestImportCompleted event (§6.4).
 * Shape: { messageId, eventType, eventVersion, occurredAt, payload }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VipImportEnvelope(
        String messageId,
        String eventType,
        String eventVersion,
        String occurredAt,
        VipImportPayload payload
) {}
