package ffdd.opsconsole.user.domain;

public record UserProfileExportFile(
        String fileName,
        byte[] body,
        int rowCount,
        java.util.List<Long> customerIds) {
    public UserProfileExportFile {
        if(customerIds!=null) customerIds=java.util.List.copyOf(customerIds);
    }
    public UserProfileExportFile(String fileName,byte[] body,int rowCount) {
        this(fileName,body,rowCount,null);
    }
}
