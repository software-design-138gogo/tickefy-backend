package com.tickefy.csvingestion.modules.csvimport.storage;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(
            @Value("${app.object-storage.endpoint}") String endpoint,
            @Value("${app.object-storage.access-key}") String accessKey,
            @Value("${app.object-storage.secret-key}") String secretKey,
            @Value("${app.object-storage.region}") String region) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .region(region)
                .build();
    }
}
