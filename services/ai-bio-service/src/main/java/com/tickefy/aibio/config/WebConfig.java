package com.tickefy.aibio.config;

import com.tickefy.aibio.common.logging.RequestIdFilter;
import com.tickefy.aibio.common.logging.RequestLoggingFilter;
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
}
