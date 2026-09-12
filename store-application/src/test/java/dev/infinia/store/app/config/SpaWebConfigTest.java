package dev.infinia.store.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.mvc.ParameterizableViewController;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SpaWebConfigTest {
    static class Registry extends ViewControllerRegistry {
        Registry() { super(new StaticWebApplicationContext()); }
        SimpleUrlHandlerMapping mapping() { return buildHandlerMapping(); }
    }

    @Test
    void introductionAndStoreUseTheSameShell() throws Exception {
        Registry registry = new Registry();
        new SpaWebConfig().addViewControllers(registry);
        var routes = registry.mapping().getUrlMap();
        for (String path : new String[]{"/", "/store", "/store/"}) {
            var controller = (ParameterizableViewController) routes.get(path);
            assertNotNull(controller);
            ModelAndView view = controller.handleRequest(
                    new MockHttpServletRequest("GET", path), new MockHttpServletResponse());
            assertNotNull(view);
            assertEquals("forward:/index.html", view.getViewName());
        }
        assertNotNull(routes.get("/site"));
    }
}
