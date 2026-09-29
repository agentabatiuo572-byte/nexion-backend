package ffdd.opsconsole.content.dto;

/** Field boundaries must be unambiguous even when free text contains record delimiters. */
public final class SupportMessagePayload {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private SupportMessagePayload() {}
    public static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalArgumentException("SUPPORT_MESSAGE_PAYLOAD_INVALID",ex);}
    }
}
