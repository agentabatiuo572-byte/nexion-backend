package ffdd.opsconsole.auth.application;

/** A client-declared label for the session list, never an authorization signal. */
public enum AppSessionSurface {
    H5("NexGrid H5"),
    APP("NexGrid Phone App"),
    UNKNOWN("NexGrid App / H5");

    public static final String APP_HEADER = "X-NexGrid-Client-Surface";
    private final String deviceName;

    AppSessionSurface(String deviceName) {
        this.deviceName = deviceName;
    }

    public String deviceName() {
        return deviceName;
    }

    public static AppSessionSurface from(boolean cookieMode, String clientSurface) {
        if (cookieMode) return H5;
        return "APP".equals(clientSurface) ? APP : UNKNOWN;
    }
}
