package com.tickefy.event.modules.artist;

import com.tickefy.event.common.constants.HeaderConstants;
import com.tickefy.event.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/artists")
@RequiredArgsConstructor
public class AdminArtistController {

    private final ArtistService artistService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ArtistResponse> createArtist(
            @Valid @RequestBody ArtistRequest body,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        ArtistResponse response = artistService.createArtist(body);
        return ApiResponse.success(response, requestId);
    }
}
