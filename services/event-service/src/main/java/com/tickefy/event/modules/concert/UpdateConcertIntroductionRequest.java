package com.tickefy.event.modules.concert;

import jakarta.validation.constraints.NotBlank;

public record UpdateConcertIntroductionRequest(
        @NotBlank(message = "Concert introduction is required") String concertIntroduction) {}
