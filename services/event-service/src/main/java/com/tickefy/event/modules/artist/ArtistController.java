package com.tickefy.event.modules.artist;

import com.tickefy.event.common.constants.HeaderConstants;
import com.tickefy.event.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/artists")
public class ArtistController {

    private final ArtistService artistService;

    public ArtistController(ArtistService artistService) {
        this.artistService = artistService;
    }

    @GetMapping
    public ApiResponse<Page<ArtistResponse>> listArtists(
            @ParameterObject @PageableDefault(size = 10, sort = "name") Pageable pageable,
            HttpServletRequest request) {
        String requestId = (String) request.getAttribute(HeaderConstants.REQUEST_ID);
        Page<ArtistResponse> page = artistService.listArtists(pageable);
        return ApiResponse.success(page, requestId);
    }
}
