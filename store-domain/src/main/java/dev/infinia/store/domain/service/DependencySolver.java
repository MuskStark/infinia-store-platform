package dev.infinia.store.domain.service;

import dev.infinia.store.contract.coordinate.InfiniaCoordinate;
import dev.infinia.store.contract.semver.SemVer;
import dev.infinia.store.contract.semver.SemVerRange;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.domain.model.Release;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Environment-aware dependency solving (design §9.4).
 *
 * <ul>
 *   <li>Prefer releases in the requested channel (STABLE by default).</li>
 *   <li>Pick the smallest satisfying version for constrained dependencies, the newest
 *       for the unconstrained root.</li>
 *   <li>Only consider installable (published) releases offered by the catalog.</li>
 *   <li>The dependency graph must be acyclic; extensions may never depend on FLOW.</li>
 *   <li>Missing optional dependencies produce warnings; missing required ones make
 *       the plan unresolvable.</li>
 * </ul>
 */
public final class DependencySolver {

    /** A resolvable release candidate offered by the catalog port. */
    public record Candidate(InfiniaCoordinate coordinate, String releaseId, SemVer version,
            Channel channel, String requiresHost, List<Release.ArtifactInfo> artifacts,
            List<Release.PermissionDecl> permissions, List<Release.DependencyDecl> dependencies) {}

    /** Supplies installable candidates for a listing coordinate, newest version first. */
    public interface Catalog {
        List<Candidate> candidatesFor(InfiniaCoordinate listing);
    }

    public record ClientEnvironment(String hostVersion, String os, String arch, Channel channel,
            Map<String, String> installed) {

        public static ClientEnvironment anonymous(String hostVersion, String os, String arch) {
            return new ClientEnvironment(hostVersion, os, arch, Channel.STABLE, Map.of());
        }
    }

    public record Resolved(Candidate candidate, boolean alreadyInstalled, String reason) {}

    public record Missing(String coordinate, String range, boolean optional, String reason) {}

    public record Result(boolean resolvable, List<Resolved> plan, List<Missing> missing,
            List<String> warnings) {}

    private static final int MAX_NODES = 256;
    public static final String RULE_NO_FLOW_DEPS = "Extensions cannot depend on FLOW items";

    private final Catalog catalog;

    public DependencySolver(Catalog catalog) {
        this.catalog = catalog;
    }

    public Result resolve(InfiniaCoordinate root, String rootRange, ClientEnvironment env) {
        Map<String, Resolved> chosen = new HashMap<>();
        Set<String> visiting = new LinkedHashSet<>();
        List<Missing> missing = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> installed = env.installed() == null ? Map.of() : env.installed();

        boolean rootOk = resolveNode(root, rootRange, null, false, env, chosen, visiting, missing,
                warnings, installed, 0);
        boolean resolvable = rootOk && missing.stream().noneMatch(m -> !m.optional());
        return new Result(resolvable, new ArrayList<>(chosen.values()), missing, warnings);
    }

    private boolean resolveNode(InfiniaCoordinate coordinate, String range, ListingType parentType,
            boolean optionalDecl, ClientEnvironment env, Map<String, Resolved> chosen,
            Set<String> visiting, List<Missing> missing, List<String> warnings,
            Map<String, String> installed, int depth) {
        String key = coordinate.listingPart().toString();
        if (chosen.containsKey(key)) {
            return true;
        }
        if (visiting.contains(key)) {
            warnings.add("Dependency cycle detected at " + key);
            return false;
        }
        if (depth > MAX_NODES) {
            warnings.add("Dependency graph too deep at " + key);
            return false;
        }
        // Extensions (non-FLOW items) may never depend on FLOW (design §9.4).
        if (coordinate.type == ListingType.FLOW && parentType != null && parentType != ListingType.FLOW) {
            missing.add(new Missing(key, range, optionalDecl, RULE_NO_FLOW_DEPS));
            return false;
        }

        List<Candidate> candidates = catalog.candidatesFor(coordinate.listingPart());
        Candidate selected = select(candidates, range, env);
        if (selected == null) {
            missing.add(new Missing(key, range, optionalDecl,
                    "No installable release satisfies the constraints"));
            return false;
        }

        visiting.add(key);
        boolean depsOk = true;
        for (Release.DependencyDecl dep : selected.dependencies()) {
            try {
                InfiniaCoordinate depCoord = InfiniaCoordinate.parse(dep.coordinate());
                boolean ok = resolveNode(depCoord, dep.range(), coordinate.type, dep.optional(),
                        env, chosen, visiting, missing, warnings, installed, depth + 1);
                if (!ok) {
                    if (dep.optional()) {
                        warnings.add("Optional dependency unresolved: " + dep.coordinate());
                    } else {
                        depsOk = false;
                    }
                }
            } catch (IllegalArgumentException e) {
                missing.add(new Missing(dep.coordinate(), dep.range(), dep.optional(),
                        "Invalid dependency coordinate"));
                if (!dep.optional()) {
                    depsOk = false;
                }
            }
        }
        visiting.remove(key);

        if (!depsOk) {
            return false;
        }
        String installedVersion = installed.get(key);
        boolean already = installedVersion != null;
        if (already) {
            try {
                already = SemVer.parse(installedVersion).equals(selected.version());
            } catch (IllegalArgumentException ignored) {
                already = false;
            }
        }
        chosen.put(key, new Resolved(selected, already, selected.version().toString()));
        return true;
    }

    /**
     * Selection: prefer the requested channel, then the smallest satisfying version for
     * constrained ranges ({@code minimal}) or the newest for the unconstrained root.
     */
    static Candidate select(List<Candidate> candidates, String range, ClientEnvironment env) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        SemVerRange constraint = null;
        boolean constrained = range != null && !range.isBlank() && !range.equals("*");
        if (constrained) {
            try {
                constraint = SemVerRange.parse(range);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        Channel wanted = env.channel() == null ? Channel.STABLE : env.channel();
        List<Candidate> viable = new ArrayList<>();
        for (Candidate c : candidates) {
            if (constraint != null && !constraint.matches(c.version())) {
                continue;
            }
            if (!hostOk(c, env)) {
                continue;
            }
            viable.add(c);
        }
        if (viable.isEmpty()) {
            return null;
        }
        List<Candidate> sameChannel = viable.stream().filter(c -> c.channel() == wanted).toList();
        List<Candidate> pool = sameChannel.isEmpty() ? viable : sameChannel;
        Comparator<Candidate> byVersion = Comparator.comparing(Candidate::version);
        return constrained
                ? pool.stream().min(byVersion).orElseThrow()
                : pool.stream().max(byVersion).orElseThrow();
    }

    private static boolean hostOk(Candidate c, ClientEnvironment env) {
        if (c.requiresHost() == null || c.requiresHost().isBlank()) {
            return true;
        }
        if (env.hostVersion() == null) {
            return false;
        }
        try {
            return SemVerRange.parse(c.requiresHost()).matches(SemVer.parse(env.hostVersion()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
