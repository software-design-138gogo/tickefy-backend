package com.tickefy.csvingestion.modules.csvimport.dto;

public record ImportErrorRow(int lineNumber, String rawData, String reason) {}
