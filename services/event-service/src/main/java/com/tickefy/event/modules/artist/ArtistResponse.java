package com.tickefy.event.modules.artist;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ArtistResponse {
    private UUID id;
    private String name;
    private String bio;
    private Instant bioGeneratedAt;
    private String pressKitUrl;
    private Instant createdAt;
    private Instant updatedAt;

    public static ArtistResponse from(Artist artist) {
        return ArtistResponse.builder()
                .id(artist.getId())
                .name(artist.getName())
                .bio(artist.getBio())
                .bioGeneratedAt(artist.getBioGeneratedAt())
                .pressKitUrl(artist.getPressKitUrl())
                .createdAt(artist.getCreatedAt())
                .updatedAt(artist.getUpdatedAt())
                .build();
    }
}
