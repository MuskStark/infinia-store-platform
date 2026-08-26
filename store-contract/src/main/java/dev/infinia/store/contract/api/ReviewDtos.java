package dev.infinia.store.contract.api;

import java.util.List;

/** DTOs for moderation and human review (design §8.2). */
public final class ReviewDtos {

    private ReviewDtos() {}

    public record ReviewDto(
            String reviewId,
            String releaseId,
            String listingCoordinate,
            String listingName,
            String version,
            String status,
            String submittedAt,
            String decidedAt,
            String reviewerId,
            String notes,
            List<PublisherDtos.ScanFindingDto> findings) {
    }

    public record ReviewDecisionRequest(String decision, String notes) {}

    public record OrganizationDto(
            String organizationId,
            String slug,
            String name,
            String createdAt) {}

    public record CreateOrganizationRequest(String slug, String name) {}

    public record WebhookDto(
            String webhookId,
            String organizationId,
            String url,
            List<String> events,
            boolean active,
            String createdAt) {}

    public record CreateWebhookRequest(String url, List<String> events) {}
}
