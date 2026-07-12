package ffdd.opsconsole.content.domain;

import java.util.List;

public record NovaSocialEventPage(
        List<NovaSocialEventView> items,
        int page,
        int pageSize,
        long total) {
}
