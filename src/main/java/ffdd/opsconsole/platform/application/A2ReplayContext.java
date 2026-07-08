package ffdd.opsconsole.platform.application;
public final class A2ReplayContext {
    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> false);
    public static void enterReplay() { REPLAYING.set(true); }
    public static void exitReplay() { REPLAYING.remove(); }
    public static boolean isReplaying() { return REPLAYING.get(); }
}
