package ffdd.opsconsole.user.domain;

import java.util.Optional;

public interface UserSeedRepository {
    Optional<Long> findUserIdByLookupKey(String lookupKey);

    void upsertAccountActionSeeds();

    void upsertKycLedgerSeeds();
}
