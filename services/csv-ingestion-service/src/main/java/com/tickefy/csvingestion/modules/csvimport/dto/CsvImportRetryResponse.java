package com.tickefy.csvingestion.modules.csvimport.dto;

import java.util.UUID;

public record CsvImportRetryResponse(UUID importJobId, String status) {}
