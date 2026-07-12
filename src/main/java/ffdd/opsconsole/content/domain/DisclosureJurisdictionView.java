package ffdd.opsconsole.content.domain;

import java.util.List;

public record DisclosureJurisdictionView(
        String code,
        String name,
        List<String> countryCodes,
        String version,
        String status,
        String publishedAt,
        long affected,
        double ackProgress,
        long blocked) {

    public DisclosureJurisdictionView(String code, String name, String version, String status,
                                      String publishedAt, long affected, double ackProgress, long blocked) {
        this(code, name, List.of(), version, status, publishedAt, affected, ackProgress, blocked);
    }
}
