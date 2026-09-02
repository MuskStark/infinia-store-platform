package dev.infinia.store.app.web;

import dev.infinia.store.app.service.CatalogService;
import dev.infinia.store.contract.api.DeliveryDtos;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.contract.error.StoreErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signed app update feed (design §8.4). Anonymous; rollout bucketing is stable per
 * opaque installId and never uses account, email or IP.
 */
@RestController
@RequestMapping("/api/v1/updates")
public class UpdatesController {

    private final CatalogService catalog;

    public UpdatesController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/app")
    public DeliveryDtos.AppUpdateDto app(@RequestParam String current,
            @RequestParam(defaultValue = "stable") String channel,
            @RequestParam String os, @RequestParam String arch,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String variant,
            @RequestParam String installId) {
        if (current == null || !dev.infinia.store.contract.semver.SemVer.isValid(current)) {
            throw new DomainException(StoreErrorCode.INVALID_SEMVER,
                    "current must be a valid SemVer");
        }
        CatalogService.UpdateFeed feed = catalog.appUpdate(current,
                Channel.valueOf(channel.trim().toUpperCase()), os, arch, mode, variant, installId);
        if (feed.latestVersion() == null) {
            return new DeliveryDtos.AppUpdateDto(null, false, 100, null, java.util.List.of(), null, null,
                    null, null, null, channel);
        }
        DeliveryDtos.AppUpdateArtifactDto primary = feed.artifacts().isEmpty() ? null
                : feed.artifacts().get(0);
        return new DeliveryDtos.AppUpdateDto(
                feed.latestVersion(),
                false, // forced updates never bypass local confirmation (design §8.4)
                feed.rolloutPercent(),
                feed.release() == null ? null : feed.release().changelogMarkdown,
                feed.artifacts(),
                primary == null ? null : primary.sha256(),
                primary == null ? null : primary.signature(),
                primary == null ? null : primary.keyId(),
                feed.release() == null || feed.release().publishedAt == null ? null
                        : feed.release().publishedAt.toString(),
                "4.0.0",
                feed.channel() == null ? channel : feed.channel().name().toLowerCase());
    }
}
