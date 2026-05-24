package ffdd.compliance.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class OutboxPublishResult {
    private int scanned;
    private int published;
    private int skipped;
    private int failed;
    private List<String> eventIds = new ArrayList<>();
}
