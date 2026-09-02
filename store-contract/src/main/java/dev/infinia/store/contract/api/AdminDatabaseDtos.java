package dev.infinia.store.contract.api;

import java.util.List;

/**
 * DTOs for the admin remote-database console (远程数据库配置): register remote
 * JDBC endpoints with encrypted credentials, verify connectivity and activate
 * one as the store's data source (applied on restart).
 */
public final class AdminDatabaseDtos {

    private AdminDatabaseDtos() {}

    /** A registered connection — the password is never returned. */
    public record RemoteDatabaseDto(
            String databaseId,
            String name,
            String jdbcUrl,
            String username,
            boolean enabled,
            String lastTestedAt,
            Boolean lastTestOk,
            String lastTestError,
            String createdAt,
            String updatedAt) {
    }

    public record CreateRemoteDatabaseRequest(
            String name,
            String jdbcUrl,
            String username,
            String password) {
    }

    /** Partial update; a blank/absent password keeps the stored one. */
    public record UpdateRemoteDatabaseRequest(
            String name,
            String jdbcUrl,
            String username,
            String password) {
    }

    /** Live connectivity probe result (also persisted on the row). */
    public record TestResultDto(
            boolean ok,
            String productName,
            String productVersion,
            String testedAt,
            String error) {
    }

    /** The data source the running instance actually uses right now. */
    public record DataSourceStatusDto(
            String productName,
            String productVersion,
            String url,
            String username,
            boolean remoteOverrideActive,
            String overrideName) {
    }

    public record ActivationRequest(Boolean enabled) {
    }

    public record RemoteDatabasePage(List<RemoteDatabaseDto> databases) {
    }
}
