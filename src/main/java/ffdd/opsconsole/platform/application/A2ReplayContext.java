package ffdd.opsconsole.platform.application;
public final class A2ReplayContext {
    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<String> OPERATION_ID = new ThreadLocal<>();

    public static void enterReplay() {
        enterReplay(null);
    }

    /** Binds the durable A2 ticket to the replay transaction for downstream audit/outbox correlation. */
    public static void enterReplay(String operationId) {
        REPLAYING.set(true);
        if (operationId == null || operationId.isBlank()) {
            OPERATION_ID.remove();
        } else {
            OPERATION_ID.set(operationId.trim());
        }
    }

    public static void exitReplay() {
        REPLAYING.remove();
        OPERATION_ID.remove();
    }

    public static boolean isReplaying() { return REPLAYING.get(); }
    public static String operationId() { return OPERATION_ID.get(); }
}
