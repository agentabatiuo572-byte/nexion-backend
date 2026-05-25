package ffdd.earnings.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class EarningTickBatchRequest {
    @Valid
    @NotEmpty
    @Size(max = 500)
    private List<EarningTickRequest> ticks = new ArrayList<>();

    private boolean settleMilestones = true;
}
