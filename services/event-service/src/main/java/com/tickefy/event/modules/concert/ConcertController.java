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

}
