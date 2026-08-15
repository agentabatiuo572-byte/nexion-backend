package ffdd.opsconsole.common.api;

public final class OpsAdminApi {
    public static final String ADMIN_PREFIX = "/api/admin";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String PRODUCT_REVISION_HEADER = "X-Product-Revision";
    public static final String REASON_FIELD = "reason";

    private OpsAdminApi() {
    }
}
