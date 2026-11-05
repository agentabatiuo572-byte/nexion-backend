package ffdd.opsconsole.content.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record I18nIntegrityIssueView(
        String code,
        String kind,
        int cnt,
        List<String> samples,
        String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer repairedCount) {

    public I18nIntegrityIssueView(String code, String kind, int cnt, List<String> samples, String status) {
        this(code, kind, cnt, samples, status, null);
    }
}
