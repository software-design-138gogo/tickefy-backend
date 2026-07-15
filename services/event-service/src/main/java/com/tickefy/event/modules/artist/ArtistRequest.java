package com.tickefy.event.modules.artist;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ArtistRequest {
    @NotBlank(message = "Tên nghệ sĩ không được để trống")
    private String name;
    
    private String bio;
    
    private String pressKitUrl;
}
