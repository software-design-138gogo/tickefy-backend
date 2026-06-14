package com.tickefy.event.modules.concert;

import com.tickefy.event.common.constants.HeaderConstants;
import com.tickefy.event.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/concerts")
public class ConcertController {

    private final ConcertService concertService;

    public ConcertController(ConcertService concertService) {
        this.concertService = concertService;
    }

    /** GET /concerts?status=PUBLISHED&page=0&size=10&sort=eventDate,desc */
    @GetMapping
    public ApiResponse<Page<ConcertResponse>> listConcerts(
            @RequestParam(required = false) ConcertStatus status,
            @ParameterObject @PageableDefault(size = 10, sort = "eventDate") Pageable pageable,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        Page<ConcertResponse> page = concertService.listConcerts(status, pageable);
        return ApiResponse.success(page, requestId);
    }

    /** GET /concerts/{id} */
    @GetMapping("/{id}")
    public ApiResponse<ConcertResponse> getConcert(
            @PathVariable UUID id,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        return ApiResponse.success(concertService.getConcertById(id), requestId);
    }

    /** POST /concerts (Admin/BTC only) */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConcertResponse> createConcert(
            @Valid @RequestBody ConcertRequest body,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        ConcertResponse response = concertService.createConcert(body, userId);
        return ApiResponse.success(response, requestId);
    }

    /** PUT /concerts/{id} (Admin/BTC only) */
    @PutMapping("/{id}")
    public ApiResponse<ConcertResponse> updateConcert(
            @PathVariable UUID id,
            @Valid @RequestBody ConcertRequest body,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        return ApiResponse.success(concertService.updateConcert(id, body), requestId);
    }

    /** POST /concerts/{id}/publish (Admin/BTC only) */
    @PostMapping("/{id}/publish")
    public ApiResponse<ConcertResponse> publishConcert(
            @PathVariable UUID id,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        return ApiResponse.success(concertService.publishConcert(id), requestId);
    }

    /** POST /concerts/{id}/cancel (Admin/BTC only) */
    @PostMapping("/{id}/cancel")
    public ApiResponse<ConcertResponse> cancelConcert(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "") String reason,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        return ApiResponse.success(concertService.cancelConcert(id, reason), requestId);
    }
}
