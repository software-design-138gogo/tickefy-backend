package com.tickefy.csvingestion.modules.csvimport.storage;

import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MinioObjectStorageClient implements ObjectStorageClient {

    private final MinioClient minioClient;
    private final String bucket;

    public MinioObjectStorageClient(
            MinioClient minioClient, @Value("${app.object-storage.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    @Override
    public void putObject(String key, InputStream stream, long size, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .stream(stream, size, -1)
                            .contentType(contentType)
                            .build());
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to put object: " + key, e);
        }
    }

    @Override
    public InputStream getObject(String key) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to get object: " + key, e);
        }
    }

    @Override
    public List<String> listObjects(String prefix) {
        List<String> keys = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build());
            for (Result<Item> r : results) {
                keys.add(r.get().objectName());
            }
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to list objects: " + prefix, e);
        }
        return keys;
    }

    @Override
    public boolean exists(String key) {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() != null ? e.errorResponse().code() : null;
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                return false;
            }
            throw new ObjectStorageException("Failed to stat object: " + key, e);
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to stat object: " + key, e);
        }
    }
}
