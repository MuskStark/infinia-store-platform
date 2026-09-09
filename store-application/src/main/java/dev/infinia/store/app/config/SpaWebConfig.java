package dev.infinia.store.app.config;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Serving the embedded Store Web SPA (single-jar deployment). The Vite build output
 * ships under {@code classpath:/static} and Boot's default static handling already
 * serves {@code /}; this adds immutable caching for hashed assets and keeps the
 * FengYu-advertised /web link working. History-mode deep-link fallback lives in
 * {@link dev.infinia.store.app.web.StoreProblemDetails} — it must intercept the
 * NoResourceFoundException before the generic handler turns it into a 500.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Vite content-hashes every asset filename, so /assets/** can be cached
        // forever (this handler's Cache-Control overrides the DefaultNoCacheFilter);
        // index.html stays revalidate-always via that filter — a cached stale
        // shell would reference chunks an upgrade removed.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // The FengYu compat layer advertises {base-url}/web as a listing's web page;
        // the SPA now lives at the root.
        registry.addRedirectViewController("/web", "/");
        // Explicit "/" → forward:/index.html instead of Boot's welcome-page magic:
        // the forward goes through the normal filter chain, so the shell leaves
        // with the DefaultNoCacheFilter's revalidate-always Cache-Control (the
        // welcome page handler serves it outside any header policy).
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Bean
    FilterRegistrationBean<DefaultNoCacheFilter> defaultNoCacheFilter() {
        // REQUEST + FORWARD + ERROR: the SPA shell is served through the welcome
        // page (an internal forward), direct /index.html, and the history-mode
        // fallback's error dispatch — the header must land on all three.
        FilterRegistrationBean<DefaultNoCacheFilter> registration =
                new FilterRegistrationBean<>(new DefaultNoCacheFilter());
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.FORWARD,
                DispatcherType.ERROR);
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        return registration;
    }
}
