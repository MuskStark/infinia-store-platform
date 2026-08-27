package dev.infinia.store.scanner;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Secret and malicious-content heuristics (design §8.2 step 5, §13.1).
 * Findings are conservative: only well-formed, high-confidence patterns block.
 */
public final class ContentScanners {

    private ContentScanners() {}

    private record Rule(String id, String severity, Pattern pattern, String description) {}

    private static final List<Rule> SECRET_RULES = List.of(
            new Rule("secret.aws-access-key", "CRITICAL",
                    Pattern.compile("AKIA[0-9A-Z]{16}"), "AWS access key id"),
            new Rule("secret.aws-secret", "CRITICAL",
                    Pattern.compile("(?i)aws.{0,20}['\\\"]?[0-9a-zA-Z/+]{40}['\\\"]?"), "AWS secret key"),
            new Rule("secret.private-key", "CRITICAL",
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"), "Private key material"),
            new Rule("secret.github-token", "CRITICAL",
                    Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,255}"), "GitHub token"),
            new Rule("secret.google-api-key", "CRITICAL",
                    Pattern.compile("AIza[0-9A-Za-z\\-_]{35}"), "Google API key"),
            new Rule("secret.generic-assignment", "ERROR",
                    Pattern.compile("(?i)(password|passwd|secret|token|api[_-]?key)\\s*[:=]\\s*['\\\"][^'\\\"]{12,}['\\\"]"),
                    "Hard-coded credential assignment"));

    private static final List<Rule> CONTENT_RULES = List.of(
            // Command must open the substitution ($(rm …), $(curl …)) — without the
            // boundary, words like "openxmlformats" inside minified JS bundles match "rm".
            new Rule("content.shell-injection", "ERROR",
                    Pattern.compile("\\$\\(\\s*(?:rm|curl|wget|chmod|nc)\\b[^)]*\\)"), "Shell command substitution with dangerous commands"),
            new Rule("content.rmrw-fs", "ERROR",
                    Pattern.compile("(?i)(rm\\s+-rf\\s+[/~]|fs\\.rmSync\\s*\\(\\s*['\\\"]/)"), "Recursive filesystem deletion from root"),
            new Rule("content.eval-remote", "ERROR",
                    Pattern.compile("(?i)eval\\s*\\(\\s*(?:atob|Buffer\\.from)\\s*\\("), "Eval of encoded payload"),
            new Rule("content.child-process", "WARN",
                    Pattern.compile("require\\s*\\(\\s*['\\\"]child_process['\\\"]\\s*\\)|from\\s+['\\\"]child_process['\\\"]"), "Child process usage requires review"),
            new Rule("content.postinstall-script", "WARN",
                    Pattern.compile("(?i)\"postinstall\"\\s*:"), "Install-time script requires review"),
            new Rule("content.remote-code-exec", "CRITICAL",
                    Pattern.compile("(?i)(curl|wget|invoke-webrequest|irm)\\s+[^|;]*\\|\\s*(bash|sh|zsh|powershell)"), "Remote script piped to shell"));

    private static final List<String> SCANNABLE_EXTENSIONS = List.of(
            ".json", ".js", ".mjs", ".cjs", ".ts", ".md", ".txt", ".yml", ".yaml", ".xml",
            ".html", ".py", ".sh", ".env", ".properties", ".toml", "");

    public static void scanFile(String path, String content, ScanResult result) {
        String lower = path.toLowerCase();
        boolean scannable = SCANNABLE_EXTENSIONS.stream().anyMatch(lower::endsWith)
                || !lower.contains(".");
        if (!scannable || content.length() > 2_000_000) {
            return;
        }
        for (Rule rule : SECRET_RULES) {
            if (rule.pattern().matcher(content).find()) {
                result.findings.add(new ScanResult.Finding(rule.severity(), rule.id(),
                        rule.description(), path));
            }
        }
        for (Rule rule : CONTENT_RULES) {
            if (rule.pattern().matcher(content).find()) {
                result.findings.add(new ScanResult.Finding(rule.severity(), rule.id(),
                        rule.description(), path));
            }
        }
    }

    public static void scanJsonForPlaintextSecrets(Map<String, Object> json, ScanResult result) {
        // MCP templates must never embed secrets (design §6.4).
        Object secrets = json.get("requiredSecrets");
        if (secrets instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> secret) {
                    String name = String.valueOf(secret.get("name")).toLowerCase();
                    boolean secretish = name.contains("token") || name.contains("secret")
                            || name.contains("password") || name.contains("authorization")
                            || name.contains("credential") || name.contains("api-key")
                            || name.contains("apikey");
                    if (secretish && !Boolean.TRUE.equals(secret.get("sensitive"))) {
                        result.error("mcp.secret-not-marked",
                                "Secret '" + secret.get("name") + "' must be marked sensitive");
                    }
                }
            }
        }
    }
}
