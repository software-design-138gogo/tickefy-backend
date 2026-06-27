package com.tickefy.notification.modules.core.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Composite id for {@link ProcessedMessage} ({@code messageId} + {@code eventType}). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedMessageId implements Serializable {

    private UUID messageId;
    private String eventType;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProcessedMessageId that = (ProcessedMessageId) o;
        return Objects.equals(messageId, that.messageId)
                && Objects.equals(eventType, that.eventType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, eventType);
    }
}
