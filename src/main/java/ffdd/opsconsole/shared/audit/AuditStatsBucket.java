package ffdd.opsconsole.shared.audit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditStatsBucket {
    private String key;
    private Long count;
}
