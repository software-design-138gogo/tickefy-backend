package com.tickefy.csvingestion.modules.csvimport.storage;

import com.tickefy.csvingestion.common.exception.ApiException;
import com.tickefy.csvingestion.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Object storage (MinIO) IO/server failure. */
public class ObjectStorageException extends ApiException {

    public ObjectStorageException(String message, Throwable cause) {
        super(ErrorCode.OBJECT_STORAGE_UNAVAILABLE, message, HttpStatus.SERVICE_UNAVAILABLE);
        initCause(cause);
    }
}
