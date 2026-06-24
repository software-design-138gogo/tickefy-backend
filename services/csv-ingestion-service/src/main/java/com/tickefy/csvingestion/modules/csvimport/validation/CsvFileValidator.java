package com.tickefy.csvingestion.modules.csvimport.validation;

import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.common.exception.ErrorCode;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * File-level validation for CSV uploads. Runs BEFORE any concert lookup / object-storage put /
 * job creation. Reads the whole file into memory (<= 10MB enforced by multipart config) — row
 * streaming-parse is T4, not here.
 */
@Component
public class CsvFileValidator {

    /** Locked header (Hoàng): exactly these columns, in this order. */
    static final String EXPECTED_HEADER = "name,email,ticket_type";

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** Validated CSV payload handed to the service for storage. */
    public record ValidatedCsv(byte[] bytes) {}

    public ValidatedCsv validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(
                    ErrorCode.INVALID_FILE_FORMAT, "CSV file is required", HttpStatus.BAD_REQUEST);
        }

        validateExtensionAndContentType(file);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(
                    ErrorCode.INVALID_FILE_FORMAT, "Unable to read uploaded file", HttpStatus.BAD_REQUEST);
        }

        bytes = stripBom(bytes);
        String content = decodeUtf8(bytes);
        validateHeader(content);

        return new ValidatedCsv(bytes);
    }

    private void validateExtensionAndContentType(MultipartFile file) {
        String filename = file.getOriginalFilename();
        boolean csvExtension =
                filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".csv");
        String contentType = file.getContentType();
        boolean csvContentType = contentType == null
                || contentType.equalsIgnoreCase("text/csv")
                || contentType.equalsIgnoreCase("application/vnd.ms-excel")
                || contentType.equalsIgnoreCase("text/plain")
                || contentType.equalsIgnoreCase("application/octet-stream");
        if (!csvExtension || !csvContentType) {
            throw new ApiException(
                    ErrorCode.INVALID_FILE_FORMAT,
                    "File must be a .csv file",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private byte[] stripBom(byte[] bytes) {
        if (bytes.length >= 3
                && bytes[0] == UTF8_BOM[0]
                && bytes[1] == UTF8_BOM[1]
                && bytes[2] == UTF8_BOM[2]) {
            byte[] out = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, out, 0, out.length);
            return out;
        }
        return bytes;
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ApiException(
                    ErrorCode.INVALID_ENCODING, "File must be UTF-8 encoded", HttpStatus.BAD_REQUEST);
        }
    }

    private void validateHeader(String content) {
        int newline = content.indexOf('\n');
        String firstLine = (newline >= 0 ? content.substring(0, newline) : content);
        String header = firstLine.replace("\r", "").trim();
        if (!EXPECTED_HEADER.equals(header)) {
            throw new ApiException(
                    ErrorCode.INVALID_FILE_FORMAT,
                    "CSV header must be: " + EXPECTED_HEADER,
                    HttpStatus.BAD_REQUEST);
        }
    }
}
