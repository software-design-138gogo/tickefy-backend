package com.tickefy.event.modules.concert;

import com.tickefy.event.common.constants.HeaderConstants;
import com.tickefy.event.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/concerts")
@RequiredArgsConstructor
public class InternalConcertController {

    private final ConcertService concertService;

    @GetMapping("/{id}")
    public ApiResponse<ConcertResponse> getConcertForInternal(
            @PathVariable UUID id,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        // Can be customized if internal needs different fields
        return ApiResponse.success(concertService.getConcertById(id), requestId);
    }
}
