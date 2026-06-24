package com.tickefy.csvingestion.modules.csvimport.dto;

public record ImportSummary(int totalRows, int successRows, int failedRows, int duplicateRows) {}
