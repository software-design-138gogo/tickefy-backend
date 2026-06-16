package com.tickefy.event.modules.artist;

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
public class ArtistService {

    private final ArtistRepository artistRepository;

    @Transactional(readOnly = true)
    public Page<ArtistResponse> listArtists(Pageable pageable) {
        return artistRepository.findAll(pageable)
                .map(ArtistResponse::from);
    }

    @Transactional
    public ArtistResponse createArtist(ArtistRequest request) {
        Artist artist = new Artist();
        artist.setName(request.getName());
        artist.setBio(request.getBio());
        artist.setPressKitUrl(request.getPressKitUrl());

        artist = artistRepository.save(artist);
        return ArtistResponse.from(artist);
    }

    @Transactional(readOnly = true)
    public Artist getArtistEntityById(UUID id) {
        return artistRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy artist với id: " + id, HttpStatus.NOT_FOUND));
    }
}
