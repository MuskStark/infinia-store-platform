package dev.infinia.store.domain.port;

import dev.infinia.store.domain.model.InvitationCode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Invitation ports (邀请注册): shareable single-use codes and the tiny
 * key-value store for runtime-editable operator settings. Implementations live
 * in store-infrastructure.
 */
public final class InvitationRepositories {

    private InvitationRepositories() {}

    public interface InvitationCodeRepository {
        void save(InvitationCode code);

        Optional<InvitationCode> findByCode(String code);

        /** Locking read so concurrent registrations cannot redeem one code twice. */
        Optional<InvitationCode> findByCodeForUpdate(String code);

        /** The issuer's codes, newest first. */
        List<InvitationCode> findByCreatedBy(UUID createdBy);

        /** Codes the issuer created since {@code since} — the monthly quota meter. */
        long countByCreatedBySince(UUID createdBy, Instant since);

        /** Newest codes across all issuers — the admin console. */
        List<InvitationCode> findRecent(int limit);
    }

    public interface SystemSettingRepository {
        Optional<String> find(String key);

        void save(String key, String value);
    }
}
