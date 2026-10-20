package ffdd.opsconsole.common.boundary;

/** Caller holds the account lock; implementations join the authentication transaction. */
public interface PhoneExecutionSessionContract {
    void authenticatedApp(long userId, String installationId, boolean executionSupported);
    void endedApp(long userId);
    /** Internal dispatcher check; a worker never impersonates a user bearer. Non-phone devices pass unchanged. */
    void requireExecutionOwner(long userId, long userDeviceId);
}
