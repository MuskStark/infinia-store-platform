package dev.infinia.store.app.config;

import org.springframework.context.annotation.Configuration;
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
        // forever; index.html itself stays on the default no-special-caching path.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // The FengYu compat layer advertises {base-url}/web as a listing's web page;
        // the SPA now lives at the root.
        registry.addRedirectViewController("/web", "/");
    }
}
