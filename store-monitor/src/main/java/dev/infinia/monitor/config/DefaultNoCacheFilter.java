package dev.infinia.monitor.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Default cache policy for GET responses: {@code no-cache} — store, but
 * revalidate on every use. The monitor serves its own embedded SPA, and an
 * index.html without Cache-Control gets heuristic-cached across upgrades; a
 * stale shell then 404s its hashed chunks and every navigation silently dies
 * until a hard refresh. Hashed assets override with their long max-age
 * wherever configured. Registered for REQUEST/FORWARD/ERROR dispatches by
 * {@link MonitorWebConfig} — the shell is served through the welcome-page
 * forward, direct /index.html and the history fallback alike.
 */
public class DefaultNoCacheFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse http) {
            String method = ((HttpServletRequest) request).getMethod();
            if ("GET".equals(method) || "HEAD".equals(method)) {
                http.setHeader("Cache-Control", "no-cache");
            }
        }
        chain.doFilter(request, response);
    }
}
