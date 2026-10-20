package ffdd.opsconsole.auth.application;

/** Authentication-entry hint only. Persisted session context is authoritative after issuance. */
public enum AppSessionSurface {
    H5("UVEL H5"),
    APP("UVEL Phone App"),
    UNKNOWN("UVEL App / H5");

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
