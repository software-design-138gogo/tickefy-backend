package com.tickefy.csvingestion.modules.csvimport.mapper;

import com.tickefy.csvingestion.modules.csvimport.dto.CsvImportStatusResponse;
import com.tickefy.csvingestion.modules.csvimport.dto.ImportErrorRow;
import com.tickefy.csvingestion.modules.csvimport.dto.ImportSummary;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportErrorEntity;
import com.tickefy.csvingestion.modules.csvimport.entity.ImportJobEntity;
import java.util.List;
import org.springframework.stereotype.Component;

/** Hand-written entity -> DTO mapping (no MapStruct, CLAUDE §3). */
@Component
public class CsvImportMapper {

    public CsvImportStatusResponse toStatusResponse(
            ImportJobEntity job, List<ImportErrorEntity> errors) {
        ImportSummary summary = new ImportSummary(
                job.getTotalRows(),
                job.getSuccessRows(),
                job.getFailedRows(),
                job.getDuplicateRows());
        List<ImportErrorRow> errorRows = errors.stream()
                .map(e -> new ImportErrorRow(e.getLineNumber(), e.getRawData(), e.getReason()))
                .toList();
        return new CsvImportStatusResponse(job.getId(), job.getStatus(), summary, errorRows);
    }
}
