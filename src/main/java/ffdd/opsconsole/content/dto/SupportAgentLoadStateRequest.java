package ffdd.opsconsole.content.dto;

public record SupportAgentLoadStateRequest(
        Integer cap,
        Boolean busy,
        Long expectedProfileVersion) {
    public SupportAgentLoadStateRequest(Integer cap, Boolean busy) {
        this(cap, busy, null);
    }
}
