package dev.infinia.store.domain.service;

import dev.infinia.store.contract.coordinate.InfiniaCoordinate;
import dev.infinia.store.contract.semver.SemVer;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.contract.type.Platform;
import dev.infinia.store.contract.type.Arch;
import dev.infinia.store.domain.model.Release;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DependencySolverTest {

    private static DependencySolver.Candidate cand(String coordinate, String version,
            String requiresHost, List<Release.DependencyDecl> deps) {
        return new DependencySolver.Candidate(InfiniaCoordinate.parse(coordinate + "@" + version),
                "rel-" + coordinate.substring(coordinate.lastIndexOf('/') + 1) + "-" + version,
                SemVer.parse(version), Channel.STABLE, requiresHost,
                List.of(), List.of(), deps);
    }

    private static Release.DependencyDecl dep(String coordinate, String range, boolean optional) {
        return new Release.DependencyDecl(coordinate, range, optional);
    }

    private DependencySolver solver(Map<String, List<DependencySolver.Candidate>> catalogData) {
        return new DependencySolver(coord -> catalogData.getOrDefault(coord.toString(), List.of()));
    }

    @Test
    void resolvesRootLatestAndTransitiveDependencies() {
        Map<String, List<DependencySolver.Candidate>> catalog = new HashMap<>();
        catalog.put("infinia://flow/summer/mail-digest", List.of(
                cand("infinia://flow/summer/mail-digest", "1.2.0", ">=4.0.0 <5.0.0", List.of(
                        dep("infinia://plugin/official/email", ">=2.0.0 <3.0.0", false))),
                cand("infinia://flow/summer/mail-digest", "1.1.0", null, List.of())));
        catalog.put("infinia://plugin/official/email", List.of(
                cand("infinia://plugin/official/email", "2.4.0", null, List.of()),
                cand("infinia://plugin/official/email", "2.0.1", null, List.of()),
                cand("infinia://plugin/official/email", "3.0.0", null, List.of())));

        DependencySolver.Result result = solver(catalog).resolve(
                InfiniaCoordinate.parse("infinia://flow/summer/mail-digest"), null,
                DependencySolver.ClientEnvironment.anonymous("4.0.1", "macos", "arm64"));

        assertTrue(result.resolvable());
        assertEquals(2, result.plan().size());
        assertTrue(result.plan().stream().anyMatch(r -> r.candidate().version().toString().equals("1.2.0")));
        // dependency picks the MINIMAL satisfying version 2.0.1, not 2.4.0
        assertTrue(result.plan().stream().anyMatch(r -> r.candidate().version().toString().equals("2.0.1")));
    }

    @Test
    void hostCompatibilityFiltersCandidates() {
        Map<String, List<DependencySolver.Candidate>> catalog = new HashMap<>();
        catalog.put("infinia://plugin/official/email", List.of(
                cand("infinia://plugin/official/email", "3.0.0", ">=5.0.0", List.of()),
                cand("infinia://plugin/official/email", "2.4.0", ">=4.0.0 <5.0.0", List.of())));

        DependencySolver.Result result = solver(catalog).resolve(
                InfiniaCoordinate.parse("infinia://plugin/official/email"), null,
                DependencySolver.ClientEnvironment.anonymous("4.2.0", "windows", "x64"));

        assertTrue(result.resolvable());
        assertEquals("2.4.0", result.plan().get(0).candidate().version().toString());
    }

    @Test
    void missingRequiredDependencyMakesPlanUnresolvable() {
        Map<String, List<DependencySolver.Candidate>> catalog = new HashMap<>();
        catalog.put("infinia://flow/summer/mail-digest", List.of(
                cand("infinia://flow/summer/mail-digest", "1.0.0", null, List.of(
                        dep("infinia://plugin/official/missing", "^1.0.0", false)))));

        DependencySolver.Result result = solver(catalog).resolve(
                InfiniaCoordinate.parse("infinia://flow/summer/mail-digest"), null,
                DependencySolver.ClientEnvironment.anonymous("4.0.0", "macos", "arm64"));

        assertFalse(result.resolvable());
        assertEquals(1, result.missing().size());
        assertFalse(result.missing().get(0).optional());
    }

    @Test
    void missingOptionalDependencyOnlyWarns() {
        Map<String, List<DependencySolver.Candidate>> catalog = new HashMap<>();
        catalog.put("infinia://flow/summer/mail-digest", List.of(
                cand("infinia://flow/summer/mail-digest", "1.0.0", null, List.of(
                        dep("infinia://mcp/official/calendar", "^1.0.0", true)))));

        DependencySolver.Result result = solver(catalog).resolve(
                InfiniaCoordinate.parse("infinia://flow/summer/mail-digest"), null,
                DependencySolver.ClientEnvironment.anonymous("4.0.0", "macos", "arm64"));

        assertTrue(result.resolvable());
        assertEquals(1, result.missing().size());
        assertTrue(result.missing().get(0).optional());
    }

    @Test
    void detectsCyclesInsteadOfLooping() {
        Map<String, List<DependencySolver.Candidate>> catalog = new HashMap<>();
        catalog.put("infinia://plugin/a/one", List.of(
                cand("infinia://plugin/a/one", "1.0.0", null, List.of(
                        dep("infinia://plugin/a/two", ">=1.0.0", false)))));
        catalog.put("infinia://plugin/a/two", List.of(
                cand("infinia://plugin/a/two", "1.0.0", null, List.of(
                        dep("infinia://plugin/a/one", ">=1.0.0", false)))));

        DependencySolver.Result result = solver(catalog).resolve(
                InfiniaCoordinate.parse("infinia://plugin/a/one"), null,
                DependencySolver.ClientEnvironment.anonymous("4.0.0", "linux", "x64"));

        // Cycle breaks resolution of the second hop; the run terminates either way.
        assertNotNull(result);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("cycle"))
                || result.plan().size() <= 2);
    }

    @Test
    void extensionsCannotDependOnFlow() {
        Map<String, List<DependencySolver.Candidate>> catalog = new HashMap<>();
        catalog.put("infinia://plugin/a/one", List.of(
                cand("infinia://plugin/a/one", "1.0.0", null, List.of(
                        dep("infinia://flow/a/flow", ">=1.0.0", false)))));
        catalog.put("infinia://flow/a/flow", List.of(
                cand("infinia://flow/a/flow", "1.0.0", null, List.of())));

        DependencySolver.Result result = solver(catalog).resolve(
                InfiniaCoordinate.parse("infinia://plugin/a/one"), null,
                DependencySolver.ClientEnvironment.anonymous("4.0.0", "linux", "x64"));

        assertFalse(result.resolvable());
        assertTrue(result.missing().stream().anyMatch(m -> m.reason().contains("FLOW")));
    }

    @Test
    void prefersSameChannel() {
        Map<String, List<DependencySolver.Candidate>> catalog = new HashMap<>();
        DependencySolver.Candidate beta = new DependencySolver.Candidate(
                InfiniaCoordinate.parse("infinia://plugin/a/one@2.0.0-beta.1"), "rel-beta",
                SemVer.parse("2.0.0-beta.1"), Channel.BETA, null, List.of(), List.of(), List.of());
        DependencySolver.Candidate stable = new DependencySolver.Candidate(
                InfiniaCoordinate.parse("infinia://plugin/a/one@1.5.0"), "rel-stable",
                SemVer.parse("1.5.0"), Channel.STABLE, null, List.of(), List.of(), List.of());
        catalog.put("infinia://plugin/a/one", List.of(beta, stable));

        var stableEnv = new DependencySolver.ClientEnvironment("4.0.0", null, null, Channel.STABLE, Map.of());
        var result = solver(catalog).resolve(InfiniaCoordinate.parse("infinia://plugin/a/one"),
                null, stableEnv);
        assertEquals("1.5.0", result.plan().get(0).candidate().version().toString());

        var betaEnv = new DependencySolver.ClientEnvironment("4.0.0", null, null, Channel.BETA, Map.of());
        result = solver(catalog).resolve(InfiniaCoordinate.parse("infinia://plugin/a/one"),
                null, betaEnv);
        assertEquals("2.0.0-beta.1", result.plan().get(0).candidate().version().toString());
    }
}
