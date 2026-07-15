package com.tickefy.notification.modules.outbox.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalEmailMessage {
    private UUID outboxId;
    private String recipientEmail;
    private String payloadJson;
}
