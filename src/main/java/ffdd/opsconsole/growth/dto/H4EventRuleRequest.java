package ffdd.opsconsole.growth.dto;

import java.util.Map;

public record H4EventRuleRequest(long expectedRevision, boolean enabled, String startsAt, String endsAt,
        Map<String,Object> rules, String reason) { }
