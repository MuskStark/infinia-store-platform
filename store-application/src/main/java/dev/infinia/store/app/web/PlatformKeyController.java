package dev.infinia.store.app.web;

import dev.infinia.store.contract.api.PlatformKeyDtos;
import dev.infinia.store.domain.model.SigningKeyInfo;
import dev.infinia.store.domain.port.PublishingRepositories;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public platform trust anchors (design §8.3): the ACTIVE platform signing
 * keys a FengYu host provisions into {@code trusted-store-keys.json} to run
 * with {@code fengyu.store.require-signature=true}. Anonymous by design —
 * public keys are public (JWKS precedent) and anchor provisioning must not
 * need an account; the private half never leaves KMS/HSM (ADR-006).
 */
@RestController
public class PlatformKeyController {

    private final PublishingRepositories.SigningKeyRepository signingKeys;

    public PlatformKeyController(PublishingRepositories.SigningKeyRepository signingKeys) {
        this.signingKeys = signingKeys;
    }

    @GetMapping("/api/v1/platform-keys")
    public PlatformKeyDtos.PlatformKeysDto platformKeys() {
        List<PlatformKeyDtos.PlatformKeyDto> keys = signingKeys
                .findByOwnerTypeAndStatus(SigningKeyInfo.OWNER_PLATFORM, "ACTIVE")
                .stream()
                .map(k -> new PlatformKeyDtos.PlatformKeyDto(k.keyId(), k.algorithm(),
                        k.publicKeyBase64(), k.validFrom(), k.validTo()))
                .toList();
        return new PlatformKeyDtos.PlatformKeysDto(keys);
    }
}
