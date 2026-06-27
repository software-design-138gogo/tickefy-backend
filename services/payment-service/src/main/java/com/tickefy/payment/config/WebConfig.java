package com.tickefy.payment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickefy.payment.common.logging.RequestIdFilter;
import com.tickefy.payment.common.logging.RequestLoggingFilter;
import com.tickefy.payment.common.security.InternalTokenFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestIdFilter());
        registration.setOrder(1);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilterRegistration() {
        FilterRegistrationBean<RequestLoggingFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestLoggingFilter());
        registration.setOrder(2);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilterRegistration(
            @Value("${app.internal.token:}") String internalToken, ObjectMapper objectMapper) {
        FilterRegistrationBean<InternalTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new InternalTokenFilter(internalToken, objectMapper));
        registration.setOrder(3); // after RequestId(1) + Logging(2), before controllers
        registration.addUrlPatterns("/*"); // filter narrows to /internal/ internally
        return registration;
    }
}
