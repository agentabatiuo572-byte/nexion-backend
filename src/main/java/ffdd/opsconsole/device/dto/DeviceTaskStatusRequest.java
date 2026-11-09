package ffdd.opsconsole.device.dto;

public record DeviceTaskStatusRequest(
        String status,
        String reason,
        String operator,String poolCode) {
    public DeviceTaskStatusRequest(String status,String reason,String operator) {this(status,reason,operator,null); }
}
