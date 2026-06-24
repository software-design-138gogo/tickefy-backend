package com.tickefy.csvingestion.modules.csvimport.storage;

import java.io.InputStream;
import java.util.List;

/** Abstraction over object storage (MinIO/S3) for CSV source + error-report objects. */
public interface ObjectStorageClient {

    void putObject(String key, InputStream stream, long size, String contentType);

    InputStream getObject(String key);

    boolean exists(String key);

    /** List object keys under a prefix (cron-scan pickup). No match → empty list. */
    List<String> listObjects(String prefix);
}
