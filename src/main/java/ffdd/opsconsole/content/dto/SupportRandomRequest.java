package ffdd.opsconsole.content.dto;

import ffdd.opsconsole.content.domain.SupportRandom.Customer;
import java.util.List;

public final class SupportRandomRequest {
    private SupportRandomRequest() {}
    public record Preview(List<Customer> customers,Boolean allFiltered,String keyword,String reason) {}
    public record Confirm(String previewId,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=SupportBindingRequest.StrictId.class) Long expectedRulesVersion,String reason) {}
}
