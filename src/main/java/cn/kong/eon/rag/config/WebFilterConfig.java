package cn.kong.eon.rag.config;

import cn.kong.eon.rag.config.properties.KbProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Web 过滤器装配。
 */
@Configuration
public class WebFilterConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyFilter(KbProperties kbProperties) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiKeyAuthFilter(kbProperties));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(10);
        return registration;
    }
}
