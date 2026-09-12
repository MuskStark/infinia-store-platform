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
 * Serves one Vite SPA for the store (/store) and project introduction (/).
 * Both routes use the same shell and hashed assets. History fallback lives in
 * {@link dev.infinia.store.app.web.StoreProblemDetails}.
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
        // the marketplace now lives under /store.
        registry.addRedirectViewController("/web", "/store");
        // Explicit "/" → forward:/index.html instead of Boot's welcome-page magic:
        // the forward goes through the normal filter chain, so the shell leaves
        // with the DefaultNoCacheFilter's revalidate-always Cache-Control (the
        // welcome page handler serves it outside any header policy).
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/store").setViewName("forward:/index.html");
        registry.addViewController("/store/").setViewName("forward:/index.html");
        registry.addRedirectViewController("/site", "/");
        registry.addRedirectViewController("/site/", "/");
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
