package dev.infinia.store.app.web;

import dev.infinia.store.app.service.CurrentPrincipal;
import dev.infinia.store.app.service.RemoteDatabaseService;
import dev.infinia.store.contract.api.AdminDatabaseDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Platform-admin remote database configuration (远程数据库配置): register remote
 * JDBC endpoints with sealed credentials, probe connectivity live, activate one
 * as the store's data source (applied on restart) and inspect what the running
 * instance is connected to. Guarded by the /api/v1/admin/** PLATFORM_ADMIN rule.
 */
@RestController
@RequestMapping("/api/v1/admin/databases")
class AdminDatabaseController {

    private final RemoteDatabaseService databases;
    private final CurrentPrincipal principal;

    AdminDatabaseController(RemoteDatabaseService databases, CurrentPrincipal principal) {
        this.databases = databases;
        this.principal = principal;
    }

    @GetMapping
    public List<AdminDatabaseDtos.RemoteDatabaseDto> list() {
        principal.require();
        return databases.list();
    }

    /** Live connectivity of the running instance (no credentials in the reply). */
    @GetMapping("/status")
    public AdminDatabaseDtos.DataSourceStatusDto status() {
        principal.require();
        return databases.status();
    }

    @PostMapping
    public ResponseEntity<AdminDatabaseDtos.RemoteDatabaseDto> create(
            @RequestBody AdminDatabaseDtos.CreateRemoteDatabaseRequest request) {
        UUID admin = principal.requireUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(databases.create(admin, request));
    }

    @PutMapping("/{databaseId}")
    public AdminDatabaseDtos.RemoteDatabaseDto update(@PathVariable UUID databaseId,
            @RequestBody AdminDatabaseDtos.UpdateRemoteDatabaseRequest request) {
        return databases.update(principal.requireUserId(), databaseId, request);
    }

    @DeleteMapping("/{databaseId}")
    public ResponseEntity<Void> delete(@PathVariable UUID databaseId) {
        databases.delete(principal.requireUserId(), databaseId);
        return ResponseEntity.noContent().build();
    }

    /** Opens a real JDBC connection and reports the server's product/version. */
    @PostMapping("/{databaseId}/test")
    public AdminDatabaseDtos.TestResultDto test(@PathVariable UUID databaseId) {
        return databases.test(principal.requireUserId(), databaseId);
    }

    /**
     * Activate ({@code enabled=true}) or deactivate the data-source override.
     * Activation requires a successful live probe and takes effect on restart.
     */
    @PostMapping("/{databaseId}/activation")
    public AdminDatabaseDtos.RemoteDatabaseDto activation(@PathVariable UUID databaseId,
            @RequestBody AdminDatabaseDtos.ActivationRequest request) {
        UUID admin = principal.requireUserId();
        if (request != null && Boolean.TRUE.equals(request.enabled())) {
            return databases.activate(admin, databaseId);
        }
        databases.deactivate(admin);
        return databases.getOrThrow(databaseId);
    }

    /** Global deactivate — drop the override and return to the default database. */
    @PostMapping("/deactivate")
    public ResponseEntity<Void> deactivate() {
        databases.deactivate(principal.requireUserId());
        return ResponseEntity.noContent().build();
    }
}
