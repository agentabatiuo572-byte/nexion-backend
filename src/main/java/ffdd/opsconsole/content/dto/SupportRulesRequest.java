package ffdd.opsconsole.content.dto;

@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=SupportRulesRequest.StrictNumbers.class)
public record SupportRulesRequest(Integer dormantDays, Integer maintenanceDays, Integer activityWindowDays,
        String inheritanceMode, Integer maxInheritanceDepth, Long expectedVersion, String reason,
        String unboundAssignmentMode) {
    public SupportRulesRequest(Integer dormantDays,Integer maintenanceDays,Integer activityWindowDays,
            String inheritanceMode,Integer maxInheritanceDepth,Long expectedVersion,String reason) {
        this(dormantDays,maintenanceDays,activityWindowDays,inheritanceMode,maxInheritanceDepth,expectedVersion,reason,null);
    }
    /** Retain the pre-extension command digest when the optional mode is omitted. */
    public String commandFingerprint() {
        String old="SupportRulesRequest[dormantDays="+dormantDays+", maintenanceDays="+maintenanceDays
            +", activityWindowDays="+activityWindowDays+", inheritanceMode="+inheritanceMode
            +", maxInheritanceDepth="+maxInheritanceDepth+", expectedVersion="+expectedVersion+", reason="+reason+"]";
        return unboundAssignmentMode==null?old:old+"|unboundAssignmentMode="+unboundAssignmentMode;
    }
    /** This command accepts JSON integers, never Jackson's default fractional/string coercion. */
    public static final class StrictNumbers extends com.fasterxml.jackson.databind.JsonDeserializer<SupportRulesRequest> {
        @Override public SupportRulesRequest deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            com.fasterxml.jackson.databind.JsonNode n=parser.getCodec().readTree(parser);
            if(!n.isObject())return context.reportInputMismatch(SupportRulesRequest.class,"Expected object");
            return new SupportRulesRequest(integer(n,"dormantDays",context),integer(n,"maintenanceDays",context),
                integer(n,"activityWindowDays",context),text(n,"inheritanceMode",context),integer(n,"maxInheritanceDepth",context),
                number(n,"expectedVersion",context),text(n,"reason",context),text(n,"unboundAssignmentMode",context));
        }
        private Integer integer(com.fasterxml.jackson.databind.JsonNode n,String key,com.fasterxml.jackson.databind.DeserializationContext c) throws java.io.IOException {
            var value=n.path(key);if(value.isMissingNode() || value.isNull())return null;
            if(!value.isIntegralNumber() || !value.canConvertToInt())return c.reportInputMismatch(SupportRulesRequest.class,"%s requires an integer",key);
            return value.intValue();
        }
        private Long number(com.fasterxml.jackson.databind.JsonNode n,String key,com.fasterxml.jackson.databind.DeserializationContext c) throws java.io.IOException {
            var value=n.path(key);if(value.isMissingNode() || value.isNull())return null;
            if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<1 || value.longValue()>9007199254740991L)return c.reportInputMismatch(SupportRulesRequest.class,"%s requires an integer",key);
            return value.longValue();
        }
        private String text(com.fasterxml.jackson.databind.JsonNode n,String key,com.fasterxml.jackson.databind.DeserializationContext c) throws java.io.IOException {
            var value=n.path(key);if(value.isMissingNode() || value.isNull())return null;
            if(!value.isTextual())return c.reportInputMismatch(SupportRulesRequest.class,"%s requires text",key);
            return value.textValue();
        }
    }
}
