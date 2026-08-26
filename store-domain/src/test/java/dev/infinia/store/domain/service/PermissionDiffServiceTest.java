package dev.infinia.store.domain.service;

import dev.infinia.store.domain.model.Release;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PermissionDiffServiceTest {

    private Release.PermissionDecl perm(String id, String scope, boolean required) {
        return new Release.PermissionDecl(id, scope, required, "reason");
    }

    @Test
    void addedPermissionRequiresConfirmation() {
        Release old = new Release();
        old.permissions = List.of(perm("fs.read", "fs:~/.fengyu/plugins", true));
        Release next = new Release();
        next.permissions = List.of(perm("fs.read", "fs:~/.fengyu/plugins", true),
                perm("net.fetch", "network:*", true));

        PermissionDiffService.PermissionDiff diff = PermissionDiffService.diff(old, next);
        assertTrue(diff.requiresConfirmation());
        assertTrue(diff.added().contains("net.fetch"));
        assertTrue(diff.removed().isEmpty());
        assertTrue(diff.escalated().isEmpty());
    }

    @Test
    void scopeWideningIsEscalation() {
        Release old = new Release();
        old.permissions = List.of(perm("net.fetch", "network:host:api.example.com", true));
        Release next = new Release();
        next.permissions = List.of(perm("net.fetch", "network:*", true));

        PermissionDiffService.PermissionDiff diff = PermissionDiffService.diff(old, next);
        assertTrue(diff.escalated().contains("net.fetch"));
        assertTrue(diff.requiresConfirmation());
    }

    @Test
    void requiredUpgradeIsEscalation() {
        Release old = new Release();
        old.permissions = List.of(perm("fs.write", "fs:~/.cache/app", false));
        Release next = new Release();
        next.permissions = List.of(perm("fs.write", "fs:~/.cache/app", true));

        assertTrue(PermissionDiffService.diff(old, next).escalated().contains("fs.write"));
    }

    @Test
    void removalAloneDoesNotRequireConfirmation() {
        Release old = new Release();
        old.permissions = List.of(perm("net.fetch", "network:*", true));
        Release next = new Release();
        next.permissions = List.of();

        PermissionDiffService.PermissionDiff diff = PermissionDiffService.diff(old, next);
        assertTrue(diff.removed().contains("net.fetch"));
        assertFalse(diff.requiresConfirmation());
    }

    @Test
    void identicalPermissionsYieldEmptyDiff() {
        Release old = new Release();
        old.permissions = List.of(perm("fs.read", "fs:~/.fengyu/plugins", true));
        Release next = new Release();
        next.permissions = List.of(perm("fs.read", "fs:~/.fengyu/plugins", true));

        assertTrue(PermissionDiffService.diff(old, next).isEmpty());
        assertFalse(PermissionDiffService.diff(old, next).requiresConfirmation());
    }

    @Test
    void firstInstallTreatsEverythingAsAdded() {
        Release next = new Release();
        next.permissions = List.of(perm("fs.read", "fs:~/.fengyu/plugins", true));

        PermissionDiffService.PermissionDiff diff = PermissionDiffService.diff(null, next);
        assertEquals(1, diff.added().size());
        assertTrue(diff.requiresConfirmation());
    }
}
