package dev.infinia.store.app.config;

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
 * revalidate on every use — which handlers with better knowledge override
 * ({@code /assets/**} sets a one-year max-age for content-hashed files,
 * downloads set {@code no-store}).
 *
 * <p>Without this the SPA's index.html carries no Cache-Control header at
 * all, so browsers heuristic-cache it across deployments. A stale shell then
 * references hashed chunks that no longer exist on the upgraded server:
 * lazy-route loads 404 and every navigation silently dies until a hard
 * refresh — exactly "clicking the nav does nothing" after an upgrade.</p>
 *
 * <p>Registered for REQUEST, FORWARD and ERROR dispatches (re-set
 * idempotently on each; see {@link SpaWebConfig}) because the shell reaches
 * the client through three paths: the welcome page (an internal forward to
 * /index.html), direct /index.html, and the history-mode fallback, which
 * serves the shell from the NoResourceFoundException error dispatch.</p>
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
