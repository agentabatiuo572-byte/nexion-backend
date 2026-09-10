package ffdd.opsconsole.auth.dto;

import java.util.List;

/** Authoritative offset page for the C1 account-deletion queue. */
public record AccountDeletionAdminPage(
        List<AccountDeletionAdminView> records,
        long total,
        int page,
        int limit) {
}
