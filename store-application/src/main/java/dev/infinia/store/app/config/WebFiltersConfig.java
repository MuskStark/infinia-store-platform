package dev.infinia.store.app.config;

import org.springframework.boot.servlet.filter.OrderedFormContentFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The auto-configured {@link OrderedFormContentFilter} URL-decodes PUT bodies
 * labelled application/x-www-form-urlencoded — curl's --data-binary default —
 * and explodes on binary package uploads before the request reaches MVC. The
 * store has no PUT/PATCH form endpoints (its only forms are the OAuth token and
 * login POSTs, which this filter never touches), so its registration is simply
 * disabled.
 */
@Configuration
public class WebFiltersConfig {

    @Bean
    public FilterRegistrationBean<OrderedFormContentFilter> disableFormContentFilter(
            OrderedFormContentFilter filter) {
        FilterRegistrationBean<OrderedFormContentFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
