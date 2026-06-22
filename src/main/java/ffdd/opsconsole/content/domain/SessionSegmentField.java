package ffdd.opsconsole.content.domain;

import java.util.List;

public record SessionSegmentField(
        String id,
        String label,
        List<String> ops,
        List<String> vals,
        String unit) {
}
