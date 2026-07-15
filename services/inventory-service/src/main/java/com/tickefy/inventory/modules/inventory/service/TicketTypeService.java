package com.tickefy.inventory.modules.inventory.service;

import com.tickefy.inventory.common.exception.ApiException;
import com.tickefy.inventory.common.exception.ErrorCode;
import com.tickefy.inventory.modules.inventory.dto.AvailabilityResponse;
import com.tickefy.inventory.modules.inventory.dto.CreateTicketTypeRequest;
import com.tickefy.inventory.modules.inventory.dto.TicketTypeResponse;
import com.tickefy.inventory.modules.inventory.entity.TicketTypeEntity;
import com.tickefy.inventory.modules.inventory.entity.TicketTypeInventoryEntity;
import com.tickefy.inventory.modules.inventory.mapper.TicketTypeMapper;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeInventoryRepository;
import com.tickefy.inventory.modules.inventory.repository.TicketTypeRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketTypeService {

    private static final Logger log = LoggerFactory.getLogger(TicketTypeService.class);

    private final TicketTypeRepository ticketTypeRepository;
    private final TicketTypeInventoryRepository inventoryRepository;
    private final InventoryRedisService redisService;
    private final TicketTypeMapper mapper;

    public TicketTypeService(
            TicketTypeRepository ticketTypeRepository,
            TicketTypeInventoryRepository inventoryRepository,
            InventoryRedisService redisService,
            TicketTypeMapper mapper) {
        this.ticketTypeRepository = ticketTypeRepository;
        this.inventoryRepository = inventoryRepository;
        this.redisService = redisService;
        this.mapper = mapper;
    }

    @Transactional
    public TicketTypeResponse create(UUID concertId, CreateTicketTypeRequest req) {
        if (!req.saleStartAt().isBefore(req.saleEndAt())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "saleEndAt must be after saleStartAt", HttpStatus.BAD_REQUEST);
        }

        TicketTypeEntity entity = TicketTypeEntity.builder()
                .concertId(concertId)
                .name(req.name())
                .price(req.price())
                .perUserLimit(req.perUserLimit())
                .saleStartAt(req.saleStartAt())
                .saleEndAt(req.saleEndAt())
                .build();
        entity = ticketTypeRepository.save(entity);

        TicketTypeInventoryEntity inventory = TicketTypeInventoryEntity.builder()
                .ticketType(entity)
                .totalQty(req.totalQty())
                .soldQty(0)
                .reservedQty(0)
                .build();
        inventoryRepository.save(inventory);

        final UUID ttId = entity.getId();
        final int totalQty = req.totalQty();

        // Seed Redis after commit (transaction commits first due to @Transactional boundary)
        // We seed here — if Redis is down, M3 will load on next access
        redisService.seedStock(ttId, totalQty);
        redisService.seedMeta(ttId, req.perUserLimit(), req.price(), req.saleStartAt(), req.saleEndAt());

        log.info("Created ticket type id={} concertId={} totalQty={}", ttId, concertId, totalQty);
        // Fresh ticket type: nothing sold/reserved yet, so available == total.
        return mapper.toResponse(entity, totalQty, totalQty, 0, 0);
    }

    /**
     * Marks every ticket type of a cancelled concert (concert.cancelled consumer, §6.3). Separate bean
     * from the consumer so the @Transactional proxy applies (no self-invocation, §8). Idempotent: a
     * redelivered/re-published event re-sets the same value — final state converges.
     *
     * @return number of ticket-type rows matched for the concert
     */
    @Transactional
    public int markConcertCancelled(UUID concertId) {
        int marked = ticketTypeRepository.markConcertCancelled(concertId);
        log.info("Marked concert cancelled concertId={} ticketTypesMatched={}", concertId, marked);
        return marked;
    }

    @Transactional(readOnly = true)
    public List<TicketTypeResponse> list(UUID concertId) {
        List<TicketTypeEntity> entities = ticketTypeRepository.findByConcertId(concertId);
        return entities.stream()
                .map(e -> {
                    Integer available = resolveAvailable(e.getId());
                    // total/sold/reserved from Postgres (ticket_type_inventory). null-safe if row missing.
                    TicketTypeInventoryEntity inv =
                            inventoryRepository.findByTicketTypeId(e.getId()).orElse(null);
                    Integer total = inv != null ? inv.getTotalQty() : null;
                    Integer sold = inv != null ? inv.getSoldQty() : null;
                    Integer reserved = inv != null ? inv.getReservedQty() : null;
                    return mapper.toResponse(e, available, total, sold, reserved);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse availability(UUID ticketTypeId) {
        TicketTypeEntity entity = ticketTypeRepository.findById(ticketTypeId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Ticket type not found", HttpStatus.NOT_FOUND));

        Integer available = resolveAvailable(ticketTypeId);
        String status = mapper.computeStatus(entity.getSaleStartAt(), entity.getSaleEndAt());
        return new AvailabilityResponse(ticketTypeId, available, status);
    }

    /**
     * M3: seed-if-missing then read from Redis; fallback to DB if Redis down.
     */
    private Integer resolveAvailable(UUID ticketTypeId) {
        // Try Redis (with M3 seed-if-missing)
        try {
            if (!redisService.stockKeyExists(ticketTypeId)) {
                // M3: key missing — rebuild from DB
                TicketTypeInventoryEntity inv = inventoryRepository.findByTicketTypeId(ticketTypeId).orElse(null);
                if (inv != null) {
                    int fromDb = inv.getTotalQty() - inv.getSoldQty() - inv.getReservedQty();
                    redisService.setStock(ticketTypeId, fromDb);
                    log.debug("M3 seed-if-missing for availability ticketTypeId={} value={}", ticketTypeId, fromDb);
                    return fromDb;
                }
            }
            Integer available = redisService.getAvailable(ticketTypeId);
            if (available != null) return available;
        } catch (Exception e) {
            log.warn("Redis unavailable for availability check ticketTypeId={}, falling back to DB", ticketTypeId, e);
        }

        // Fallback: compute from DB
        return inventoryRepository.findByTicketTypeId(ticketTypeId)
                .map(inv -> inv.getTotalQty() - inv.getSoldQty() - inv.getReservedQty())
                .orElse(0);
    }
}
