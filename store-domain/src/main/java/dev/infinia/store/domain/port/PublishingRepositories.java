package dev.infinia.store.domain.port;

import dev.infinia.store.domain.model.AuditRecord;
import dev.infinia.store.domain.model.OutboxRecord;
import dev.infinia.store.domain.model.Review;
import dev.infinia.store.domain.model.SigningKeyInfo;
import dev.infinia.store.domain.model.UploadSessionInfo;
import dev.infinia.store.domain.model.WebhookInfo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Ports for the publishing pipeline: reviews, uploads, keys, audit, outbox, webhooks. */
public final class PublishingRepositories {

    private PublishingRepositories() {}

    public interface ReviewRepository {
        Optional<Review> findById(UUID id);

        Optional<Review> findLatestByReleaseId(UUID releaseId);

        void save(Review review);

        List<Review> findByStatus(String status, int limit);
    }

    public interface UploadSessionRepository {
        Optional<UploadSessionInfo> findById(UUID id);

        void save(UploadSessionInfo session);

        List<UploadSessionInfo> findByReleaseId(UUID releaseId);
    }

    public interface SigningKeyRepository {
        Optional<SigningKeyInfo> findByKeyId(String keyId);

        void save(SigningKeyInfo key);

        List<SigningKeyInfo> findByOwnerTypeAndStatus(String ownerType, String status);
    }

    public interface AuditEventRepository {
        void append(AuditRecord record);

        List<AuditRecord> findRecent(int limit, String resourceType);
    }

    public interface OutboxRepository {
        void enqueue(OutboxRecord record);

        List<OutboxRecord> findPending(int limit, Instant now);

        void markDispatched(UUID id);

        void markFailed(UUID id, Instant nextAttemptAt);
    }

    public interface WebhookRepository {
        List<WebhookInfo> findActiveByEventAndOrganization(String eventType, UUID organizationId);

        List<WebhookInfo> findByOrganizationId(UUID organizationId);

        Optional<WebhookInfo> findById(UUID id);

        void save(WebhookInfo webhook);
    }
}
