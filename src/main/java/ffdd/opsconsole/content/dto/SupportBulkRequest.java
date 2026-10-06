package ffdd.opsconsole.content.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

public final class SupportBulkRequest {
    private SupportBulkRequest() {}
    public record Filters(String accountState, String maintenanceState, String level, List<String> tagIds,
            String registeredFrom, String registeredTo, String activityFrom, String activityTo,
            String depositMin, String depositMax, String withdrawalMin, String withdrawalMax,
            String currency, Boolean includeUnknown, String keyword) {}
    public record Preview(Filters filters,
            @JsonDeserialize(contentUsing=SupportBindingRequest.StrictId.class) List<Long> customerIds,
            @JsonDeserialize(contentUsing=SupportBindingRequest.StrictId.class) List<Long> excludedIds,
            String selectionMode) {}
    public record Create(String selectionId, String intent, String kind, String content, String skuId,
            SupportLinkTarget linkTarget, String assetId, String reason) {}
    public record Mutation(@JsonDeserialize(using=SupportBindingRequest.StrictId.class) Long expectedVersion,
            String reason) {}
}
