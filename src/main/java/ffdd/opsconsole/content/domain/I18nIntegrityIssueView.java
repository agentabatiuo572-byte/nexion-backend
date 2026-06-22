package ffdd.opsconsole.content.domain;

import java.util.List;

public record I18nIntegrityIssueView(
        String code,
        String kind,
        int cnt,
        List<String> samples,
        String status) {
}
