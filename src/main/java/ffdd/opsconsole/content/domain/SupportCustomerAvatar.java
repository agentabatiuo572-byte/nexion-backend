package ffdd.opsconsole.content.domain;

/** Console reference to a customer's own uploaded avatar; null when there is none, so no request is made. */
public final class SupportCustomerAvatar {
    private SupportCustomerAvatar() {}

    public static boolean owned(Long customer, String key) {
        return customer != null && key != null && key.startsWith("users/" + customer + "/avatar/") && !key.contains("..");
    }

    public static String ref(Long customer, String key) {
        return owned(customer, key) ? "/api/admin/content/support-workbench/customers/" + customer + "/avatar" : null;
    }
}
