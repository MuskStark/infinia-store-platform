package dev.infinia.store.domain.service;

import dev.infinia.store.domain.model.Release;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Computes the permission difference between two releases (design §9.3). Adding
 * permissions or widening a permission's scope always counts as an escalation and
 * requires fresh user confirmation on the client.
 */
public final class PermissionDiffService {

    public record PermissionDiff(Set<String> added, Set<String> removed,
            Set<String> escalated, boolean requiresConfirmation) {

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && escalated.isEmpty();
        }
    }

    private PermissionDiffService() {}

    public static PermissionDiff diff(Release previous, Release next) {
        Map<String, Release.PermissionDecl> oldPerms = index(previous);
        Map<String, Release.PermissionDecl> newPerms = index(next);

        Set<String> added = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        Set<String> escalated = new LinkedHashSet<>();

        for (Map.Entry<String, Release.PermissionDecl> e : newPerms.entrySet()) {
            String id = e.getKey();
            Release.PermissionDecl decl = e.getValue();
            Release.PermissionDecl old = oldPerms.get(id);
            if (old == null) {
                added.add(id);
            } else if (widened(old, decl)) {
                escalated.add(id);
            }
        }
        for (String id : oldPerms.keySet()) {
            if (!newPerms.containsKey(id)) {
                removed.add(id);
            }
        }
        boolean requiresConfirmation = !added.isEmpty() || !escalated.isEmpty();
        return new PermissionDiff(added, removed, escalated, requiresConfirmation);
    }

    private static boolean widened(Release.PermissionDecl old, Release.PermissionDecl cur) {
        // A permission that becomes required, or whose scope broadens
        // (e.g. "network:host:api.example.com" -> "network:*"), is an escalation.
        if (!old.required() && cur.required()) {
            return true;
        }
        String oldScope = old.scope() == null ? "" : old.scope();
        String newScope = cur.scope() == null ? "" : cur.scope();
        if (oldScope.equals(newScope)) {
            return false;
        }
        return newScope.equals("*") || oldScope.contains(":") && newScope.endsWith(":*")
                && newScope.startsWith(oldScope.substring(0, oldScope.indexOf(':') + 1));
    }

    private static Map<String, Release.PermissionDecl> index(Release release) {
        Map<String, Release.PermissionDecl> map = new LinkedHashMap<>();
        if (release != null && release.permissions != null) {
            for (Release.PermissionDecl p : release.permissions) {
                map.put(p.permissionId(), p);
            }
        }
        return map;
    }
}
