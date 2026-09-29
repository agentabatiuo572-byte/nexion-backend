package ffdd.opsconsole.content.dto;

import java.util.List;

public record SupportAgentSeatAssignmentRequest(
        String position,
        List<String> serviceTypes,
        List<String> tags,
        Integer maxConcurrent,
        Boolean enabled,
        Boolean transferable,
        Boolean busy,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentUsing=SupportBindingRequest.StrictId.class) List<Long> userIds,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=SupportBindingRequest.StrictId.class) Long expectedVersion,
        String operator,
        String reason, List<SupportBindingRequest.Customer> customers) {
    public SupportAgentSeatAssignmentRequest(String position,List<String> serviceTypes,List<String> tags,Integer maxConcurrent,Boolean enabled,Boolean transferable,Boolean busy,List<Long> userIds,Long expectedVersion,String operator,String reason) {
        this(position,serviceTypes,tags,maxConcurrent,enabled,transferable,busy,userIds,expectedVersion,operator,reason,null);
    }
    public SupportAgentSeatAssignmentRequest(
            String position, List<String> serviceTypes, List<String> tags, Integer maxConcurrent,
            Boolean enabled, Boolean transferable, Boolean busy, List<Long> userIds, String operator, String reason) {
        this(position, serviceTypes, tags, maxConcurrent, enabled, transferable, busy, userIds, 1L, operator, reason, null);
    }
}
