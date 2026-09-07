package dev.infinia.store.app.config;

import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.http.server.resolver.AsIsFileService;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.eclipse.jgit.util.FS;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Read-only smart-HTTP serving of the exported ecosystem repositories under
 * {@code /git/**} (audit 3.4).
 *
 * <p>The CLAUDE marketplace exports skills and MCP templates as bare git repos
 * under {@code store.export-dir} (see {@code LocalGitExporter}); the exported
 * entries historically carried {@code file://} clone URLs, which only work for a
 * host on the same machine. Mounting JGit's {@link GitServlet} lets remote FengYu
 * hosts clone over http(s): configure {@code store.export.git-public-base} and the
 * marketplace document carries absolute http(s) clone URLs.
 *
 * <p>Protocol surface is strictly read-only: upload-pack (clone/fetch) is enabled,
 * receive-pack (push) is refused at the servlet — a {@code ServiceNotEnabledException}
 * from the factory makes every push attempt fail closed — and dumb-protocol static
 * file serving is disabled so clients must use the smart protocol. The resolver
 * only ever opens {@code <export-dir>/<sanitized-name>.git} directories that the
 * exporter itself created; arbitrary paths and traversal attempts 404.
 */
@Configuration
public class GitHttpConfig {

    /** Exported repo directory names: the exporter's sanitized key + ".git". */
    private static final Pattern REPO_NAME = Pattern.compile("^[A-Za-z0-9._-]+\\.git$");

    @Bean
    public ServletRegistrationBean<GitServlet> gitSmartHttp(
            @Value("${store.export-dir:data/git-exports}") String exportDir) {
        Path base = Path.of(exportDir).toAbsolutePath().normalize();
        GitServlet servlet = new GitServlet();
        servlet.setRepositoryResolver((request, name) -> openRepository(base, name));
        // Clone/fetch only — no push, ever (defense in depth: SecurityConfig also
        // denies /git/**/git-receive-pack at the filter chain).
        servlet.setReceivePackFactory((request, db) -> {
            throw new ServiceNotEnabledException();
        });
        // Smart protocol only; dumb static file serving stays off.
        servlet.setAsIsFileService(AsIsFileService.DISABLED);
        ServletRegistrationBean<GitServlet> registration =
                new ServletRegistrationBean<>(servlet, "/git/*");
        registration.setName("gitSmartHttp");
        return registration;
    }

    /**
     * Opens exactly one exported repo by its directory name, never anything
     * outside the export root. Missing/invalid names and open failures surface
     * as {@link org.eclipse.jgit.errors.RepositoryNotFoundException} so
     * GitServlet answers 404.
     */
    private static Repository openRepository(Path base, String name)
            throws org.eclipse.jgit.errors.RepositoryNotFoundException {
        if (name == null || !REPO_NAME.matcher(name).matches()) {
            throw new org.eclipse.jgit.errors.RepositoryNotFoundException(name);
        }
        Path dir = base.resolve(name).normalize();
        if (!dir.startsWith(base) || !Files.isDirectory(dir)) {
            throw new org.eclipse.jgit.errors.RepositoryNotFoundException(name);
        }
        // Ref-counted through the repository cache: GitServlet closes its request
        // handle; the cached entry keeps reopens cheap across clones.
        try {
            return RepositoryCache.open(RepositoryCache.FileKey.exact(dir.toFile(), FS.DETECTED));
        } catch (IOException e) {
            throw new org.eclipse.jgit.errors.RepositoryNotFoundException(name);
        }
    }
}
