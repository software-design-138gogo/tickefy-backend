package com.tickefy.checkin.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService eticketHttpClientExecutor(
            @Value("${eticket.http.executor-threads:256}") int executorThreads) {
        return Executors.newFixedThreadPool(executorThreads);
    }

    /**
     * 3 s connect timeout, 5 s read timeout (B-TIMEOUT fix).
     * Uses JDK HttpClient so high-concurrency check-in bursts reuse a modern
     * HTTP client instead of creating one-off URLConnection requests.
     */
    @Bean
    public RestTemplate restTemplate(
            @Qualifier("eticketHttpClientExecutor") ExecutorService executor,
            @Value("${eticket.http.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${eticket.http.read-timeout-ms:5000}") int readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .executor(executor)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return new RestTemplate(factory);
    }
}
