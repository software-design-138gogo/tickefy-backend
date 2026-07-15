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
    public ApiResponse<InternalConcertSummaryResponse> getConcertForInternal(
            @PathVariable UUID id,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        return ApiResponse.success(concertService.getInternalConcert(id), requestId);
    }

    @GetMapping("/{id}/ai-context")
    public ApiResponse<AiConcertContextResponse> getAiContext(
            @PathVariable UUID id,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        return ApiResponse.success(concertService.getAiContext(id), requestId);
    }
}
