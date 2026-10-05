package com.ticket.core.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class WebConfig {

    private final TenantAuthenticationFilter tenantAuthenticationFilter;

    @Bean
    public FilterRegistrationBean<TenantAuthenticationFilter> tenantFilterRegistration() {
        FilterRegistrationBean<TenantAuthenticationFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(tenantAuthenticationFilter);
        registrationBean.addUrlPatterns("/v1/*");
        registrationBean.setOrder(1);
        return registrationBean;
    }
}
