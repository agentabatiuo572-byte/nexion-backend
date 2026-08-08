package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.content.domain.SupportFaqView;
import ffdd.opsconsole.content.domain.SupportKnowledgeRepository;
import ffdd.opsconsole.content.domain.SupportSlaBaseline;
import ffdd.opsconsole.content.domain.SupportSlaView;
import ffdd.opsconsole.content.dto.SupportFaqUpsertRequest;
import ffdd.opsconsole.content.dto.SupportSlaUpdateRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SupportSlaBaselineInitializerTest {
    @Test
    void startupAddsEveryMissingCategoryWithoutOverwritingExistingOperationsConfig() {
        FakeRepository repository = new FakeRepository();
        repository.sla.add(new SupportSlaView(
                "withdrawal", 10, 6, "support2-withdrawal",
                "D2/K4 support2 on-call", 7L, LocalDateTime.of(2026, 7, 28, 0, 0)));
        SupportSlaBaselineInitializer initializer = new SupportSlaBaselineInitializer(
                repository,
                Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC));

        initializer.run(null);
        initializer.run(null);

        assertThat(repository.sla)
                .extracting(SupportSlaView::category)
                .containsExactlyInAnyOrderElementsOf(SupportSlaBaseline.CATEGORIES);
        assertThat(repository.sla).hasSize(SupportSlaBaseline.CATEGORIES.size());
        assertThat(repository.sla.stream()
                .filter(row -> row.category().equals("withdrawal"))
                .findFirst()
                .orElseThrow())
                .satisfies(row -> {
                    assertThat(row.firstResponseMins()).isEqualTo(10);
                    assertThat(row.resolutionHours()).isEqualTo(6);
                    assertThat(row.queue()).isEqualTo("support2-withdrawal");
                    assertThat(row.version()).isEqualTo(7L);
                });
    }

    private static final class FakeRepository implements SupportKnowledgeRepository {
        private final List<SupportSlaView> sla = new ArrayList<>();

        @Override
        public List<SupportSlaView> listSla() {
            return List.copyOf(sla);
        }

        @Override
        public void upsertSla(String category, SupportSlaUpdateRequest request, LocalDateTime now) {
            if (sla.stream().noneMatch(row -> row.category().equals(category))) {
                sla.add(new SupportSlaView(
                        category,
                        request.firstResponseMins(),
                        request.resolutionHours(),
                        request.queue(),
                        request.escalation(),
                        1L,
                        now));
            }
        }

        @Override
        public void ensureSeedData(LocalDateTime now) {
        }

        @Override
        public List<SupportFaqView> listFaqs() {
            return List.of();
        }

        @Override
        public Optional<SupportFaqView> findFaq(String faqId) {
            return Optional.empty();
        }

        @Override
        public SupportFaqView createFaq(String faqId, SupportFaqUpsertRequest request, LocalDateTime now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateFaq(String faqId, SupportFaqUpsertRequest request, LocalDateTime now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateFaqStatus(String faqId, String status, LocalDateTime now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteFaq(String faqId, LocalDateTime now) {
            throw new UnsupportedOperationException();
        }
    }
}
