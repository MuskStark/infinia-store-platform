package dev.infinia.store.contract.api;

import java.util.List;

/** DTOs for identity, sessions, devices, library and telemetry. */
public final class AccountDtos {

    private AccountDtos() {}

    public record RegisterRequest(String email, String password, String displayName) {}

    public record PublicUserDto(
            String userId,
            String email,
            String displayName,
            List<String> roles,
            String createdAt) {
    }

    public record UpdateProfileRequest(String displayName) {}

    public record SessionDto(
            String sessionId,
            String clientId,
            String kind,
            String createdAt,
            String lastUsedAt,
            String remoteIpHash) {
    }

    public record DeviceDto(
            String deviceId,
            String publicId,
            String name,
            String platform,
            String createdAt,
            String lastSeenAt,
            boolean revoked) {
    }

    public record FavoriteDto(
            String listingCoordinate,
            String name,
            String type,
            String latestVersion,
            String addedAt) {
    }

    public record LibraryDto(
            List<FavoriteDto> favorites,
            List<EntitlementDto> entitlements,
            List<InstallEventDto> installHistory) {
    }

    public record EntitlementDto(
            String listingCoordinate,
            boolean free,
            String acquiredAt) {
    }

    /** Optional, batched, idempotent install telemetry (design §10.2 / ADR-009). */
    public record InstallEventRequest(
            String idempotencyKey,
            String coordinate,
            String version,
            String type,
            String action,
            String outcome,
            String hostVersion,
            String os,
            String arch,
            String occurredAt) {
    }

    public record InstallEventDto(
            String idempotencyKey,
            String coordinate,
            String version,
            String action,
            String outcome,
            String occurredAt) {
    }
}
