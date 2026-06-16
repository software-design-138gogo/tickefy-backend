package com.tickefy.inventory.modules.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ticket_type_inventory")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketTypeInventoryEntity {

    @Id
    @Column(name = "ticket_type_id", nullable = false)
    private UUID ticketTypeId;

    @MapsId
    @OneToOne
    @JoinColumn(name = "ticket_type_id")
    private TicketTypeEntity ticketType;

    @Column(name = "total_qty", nullable = false)
    private Integer totalQty;

    @Column(name = "sold_qty", nullable = false)
    @Builder.Default
    private Integer soldQty = 0;

    @Column(name = "reserved_qty", nullable = false)
    @Builder.Default
    private Integer reservedQty = 0;
}
