package dev.infinia.store.app;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.util.List;

/**
 * Gives every {@code @SpringBootTest} class its own empty H2 database.
 *
 * <p>The suite otherwise shares one in-memory database across all test classes
 * (fast: one context, one Flyway run), but classes mutate state — the app
 * release flow publishes newer versions of the seeded listing, the SSRF
 * conformance test leaves a failed-sync upstream source — so the outcome
 * depended on surefire's class execution order, which differs between
 * machines and even between CI checkouts (filesystem order). Each class now
 * boots against {@code jdbc:h2:mem:<class>;DB_CLOSE_DELAY=-1} with the same
 * PostgreSQL-compatibility settings as the shared default, re-seeded fresh by
 * whatever seeding its profile performs. Within a class, methods still share
 * the database — tests are written for that.</p>
 *
 * <p>A class that declares its own {@code spring.datasource.url} (in
 * {@code @SpringBootTest(properties = ...)} or {@code @TestPropertySource})
 * wins: the customizer only fills the gap, it never overrides.</p>
 */
public final class FreshDatabasePerClass {

    private FreshDatabasePerClass() {}

    public static class Factory implements ContextCustomizerFactory {
        @Override
        public ContextCustomizer createContextCustomizer(Class<?> testClass,
                List<ContextConfigurationAttributes> configAttributes) {
            return new Customizer(testClass.getName());
        }
    }

    private record Customizer(String dbName) implements ContextCustomizer {
        @Override
        public void customizeContext(ConfigurableApplicationContext context,
                MergedContextConfiguration mergedConfig) {
            for (String property : mergedConfig.getPropertySourceProperties()) {
                if (property.startsWith("spring.datasource.url=")) {
                    return; // the class pinned its own database
                }
            }
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "spring.datasource.url=jdbc:h2:mem:" + dbName
                            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                            + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        }
    }
}
