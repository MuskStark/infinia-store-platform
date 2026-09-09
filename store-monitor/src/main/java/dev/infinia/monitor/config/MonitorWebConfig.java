package dev.infinia.monitor.config;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Serving the embedded Monitor Web SPA: hashed assets cache forever, the
 * shell revalidates on every use (see {@link DefaultNoCacheFilter}).
 */
@Configuration
public class MonitorWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic());
    }

    @Bean
    FilterRegistrationBean<DefaultNoCacheFilter> defaultNoCacheFilter() {
        FilterRegistrationBean<DefaultNoCacheFilter> registration =
                new FilterRegistrationBean<>(new DefaultNoCacheFilter());
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.FORWARD,
                DispatcherType.ERROR);
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        return registration;
    }
}
