package com.tickefy.csvingestion.modules.csvimport.dto;

import java.util.List;
import java.util.UUID;

public record CsvImportStatusResponse(
        UUID importJobId, String status, ImportSummary summary, List<ImportErrorRow> errorRows) {}
