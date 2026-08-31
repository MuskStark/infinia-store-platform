package dev.infinia.store.app.web;

import dev.infinia.store.app.config.StoreProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.view.RedirectView;

/** Store Web login bridge; the Spring-generated login page is not a product surface. */
@Controller
class StoreWebLoginController {

    record SessionLoginCsrf(String parameterName, String token) {}

    private final StoreProperties properties;

    StoreWebLoginController(StoreProperties properties) {
        this.properties = properties;
    }

    /** CSRF material used by Store Web before it submits the browser-session login. */
    @GetMapping("/oauth2/session-login/csrf")
    @ResponseBody
    SessionLoginCsrf sessionLoginCsrf(CsrfToken csrf) {
        return new SessionLoginCsrf(csrf.getParameterName(), csrf.getToken());
    }

    /** Compatibility only: old bookmarks leave the deprecated backend login page. */
    @GetMapping("/login")
    RedirectView legacyLogin(HttpServletResponse response) {
        response.setHeader("Deprecation", "true");
        response.setHeader("Link", "<" + properties.webSignInUri()
                + ">; rel=\"successor-version\"");
        return new RedirectView(properties.webSignInUri());
    }
}
