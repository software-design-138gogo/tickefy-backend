package com.tickefy.event.modules.venue;

import com.tickefy.event.common.exception.ApiException;
import com.tickefy.event.common.exception.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VenueService {

    private final VenueRepository venueRepository;

    @Transactional(readOnly = true)
    public Page<VenueResponse> listVenues(Pageable pageable) {
        return venueRepository.findAll(pageable)
                .map(VenueResponse::from);
    }

    @Transactional
    public VenueResponse createVenue(VenueRequest request) {
        Venue venue = new Venue();
        venue.setName(request.getName());
        venue.setAddress(request.getAddress());
        venue.setCity(request.getCity());
        venue.setCapacity(request.getCapacity());

        venue = venueRepository.save(venue);
        return VenueResponse.from(venue);
    }

    @Transactional(readOnly = true)
    public Venue getVenueEntityById(UUID id) {
        return venueRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy venue với id: " + id, HttpStatus.NOT_FOUND));
    }
}
