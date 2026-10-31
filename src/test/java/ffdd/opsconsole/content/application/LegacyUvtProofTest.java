package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.domain.LegacyUvtProof;
import org.junit.jupiter.api.Test;

class LegacyUvtProofTest {
    @Test void projectionNeverMutatesRawProofOrBusinessIdsAndOnlyTheForwardCurrencyRenameMatches() throws Exception {
        var raw=new ObjectMapper().readTree("{\"asset\":\"NEX\",\"sourceRef\":\"ORDER:NEX\",\"type\":\"NEX\",\"nested\":[{\"currency\":\"NEX\",\"amount\":12.345678}],\"remark\":\"NEX\"}");
        String bytes=raw.toString();var view=LegacyUvtProof.current(raw);
        assertThat(raw.toString()).isEqualTo(bytes);
        assertThat(view.path("asset").asText()).isEqualTo("UVT");assertThat(view.path("nested").get(0).path("currency").asText()).isEqualTo("UVT");
        assertThat(view.path("sourceRef").asText()).isEqualTo("ORDER:NEX");assertThat(view.path("type").asText()).isEqualTo("NEX");assertThat(view.path("remark").asText()).isEqualTo("NEX");
        assertThat(LegacyUvtProof.matches("NEX","UVT")).isTrue();
        assertThat(LegacyUvtProof.matches("UVT","NEX")).isFalse();assertThat(LegacyUvtProof.matches("NEX","USDT")).isFalse();
    }
}
