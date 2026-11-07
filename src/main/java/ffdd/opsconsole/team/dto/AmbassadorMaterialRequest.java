package ffdd.opsconsole.team.dto;

import java.math.BigDecimal;
import java.util.List;

public record AmbassadorMaterialRequest(Long expectedMaterialRevision,List<Attachment> attachments,String statement,BigDecimal budgetUsdt,String bucket) {
    public record Attachment(String fileId,String kind) {}
    public record Review(Long expectedMaterialRevision,String stage,String decision,String reason,Integer verifiedAttendees,BigDecimal recognizedBudgetUsdt,List<String> missingKinds) {}
}
