package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.infrastructure.persistence.entity.OutboxEventEntity;
import dev.infinia.store.infrastructure.persistence.repository.OutboxJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox retry semantics (ADR review fix): a FAILED event whose backoff has
 * elapsed must stay selectable — the relay used to filter on PENDING only,
 * permanently dropping every webhook after its first transient failure — while
 * DEAD (attempts exhausted) is terminal.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.datasource.url="
        + "jdbc:h2:mem:outbox-retry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
class OutboxRetryTest {

    @Autowired
    OutboxJpaRepository outbox;

    @jakarta.persistence.PersistenceContext
    jakarta.persistence.EntityManager entityManager;

    @Test
    void failedEventsStayRetryableUntilMarkedDead() {
        UUID id = seed("FAILED", Instant.now().minusSeconds(60));
        assertThat(outbox.findRetryable(Instant.now()))
                .extracting(e -> e.id).contains(id);

        // Backoff not elapsed yet → not selected.
        OutboxEventEntity event = outbox.findById(id).orElseThrow();
        event.nextAttemptAt = Instant.now().plusSeconds(600);
        outbox.saveAndFlush(event);
        assertThat(outbox.findRetryable(Instant.now()))
                .extracting(e -> e.id).doesNotContain(id);

        // Attempts exhausted → terminal, never selected again.
        event.nextAttemptAt = Instant.now().minusSeconds(60);
        outbox.saveAndFlush(event);
        outbox.markDead(id);
        outbox.flush();
        // @Modifying bypasses the persistence context — clear before re-reading.
        entityManager.clear();
        assertThat(outbox.findRetryable(Instant.now()))
                .extracting(e -> e.id).doesNotContain(id);
        assertThat(outbox.findById(id).orElseThrow().status).isEqualTo("DEAD");
    }

    @Test
    void pendingEventsRemainSelectable() {
        UUID id = seed("PENDING", Instant.now().minusSeconds(1));
        assertThat(outbox.findRetryable(Instant.now()))
                .extracting(e -> e.id).contains(id);
    }

    private UUID seed(String status, Instant nextAttemptAt) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.id = UUID.randomUUID();
        event.aggregateType = "release";
        event.aggregateId = "agg-1";
        event.type = "release.published";
        event.payload = "{}";
        event.status = status;
        event.attempts = 1;
        event.nextAttemptAt = nextAttemptAt;
        event.createdAt = Instant.now().minusSeconds(120);
        return outbox.saveAndFlush(event).id;
    }
}
