package ffdd.opsconsole.common.boundary;

/** Loaded native CPU executor observation; independent output verification remains mandatory. */
public interface PhoneComputeEngineContract {
    boolean ready(long userId,long deviceId,String installationId);
}
