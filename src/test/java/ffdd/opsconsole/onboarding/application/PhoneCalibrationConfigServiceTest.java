package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PhoneCalibrationConfigServiceTest {
    private final Map<String,String> values = new HashMap<>();
    private final PlatformConfigFacade config = new PlatformConfigFacade() {
        public Optional<String> activeValue(String key) { return Optional.ofNullable(values.get(key)); }
        public void upsertAdminValue(String key,String value,String type,String group,String remark) { values.put(key,value); }
        public boolean insertAdminValueIfMissing(String key,String value,String type,String group,String remark) {
            return values.putIfAbsent(key,value) == null;
        }
    };
    private PhoneCalibrationConfigService service(long time) {
        return new PhoneCalibrationConfigService(config, Clock.fixed(Instant.ofEpochMilli(time), ZoneOffset.UTC));
    }
    private PhoneCalibrationConfigService.Proposal proposal(long revision,long effectiveAt,String score) {
        return new PhoneCalibrationConfigService.Proposal(revision,effectiveAt,
                PhoneCalibrationPolicyTest.policy(List.of()).thresholds(),
                List.of(PhoneCalibrationPolicyTest.tier("fixture",score,score,true,
                        PhoneCalibrationPolicyTest.chip("fixture-chip","Fixture chip","FIXTURE1"))), null);
    }
    @Test void emptyCatalogHasNoInventedProductionRules() {
        assertThat(service(1000).read().revision()).isZero();
        assertThat(service(1000).activePolicy()).isNull();
        assertThat(values).isEmpty();
    }
    @Test void scheduledPublicationKeepsCurrentUntilItsEffectiveTime() {
        service(1000).publish(proposal(0,0,"18.4"));
        var scheduled = service(2000).publish(proposal(1,5000,"34.2"));
        assertThat(scheduled.current().version()).isEqualTo(1);
        assertThat(scheduled.scheduled().version()).isEqualTo(2);
        assertThat(service(4999).activePolicy().version()).isEqualTo(1);
        assertThat(service(5000).activePolicy().version()).isEqualTo(2);
        assertThat(service(5000).read().scheduled()).isNull();
        assertThat(service(6000).publish(proposal(2,0,"35")).current().version()).isEqualTo(3);
    }
    @Test void staleAndConflictingPublicationsDoNotOverwrite() {
        service(1000).publish(proposal(0,0,"18.4"));
        String before = values.get(PhoneCalibrationConfigService.KEY);
        assertThatThrownBy(() -> service(2000).publish(proposal(0,0,"34.2")))
                .isInstanceOf(BizException.class).hasMessageContaining("VERSION_CONFLICT");
        assertThat(values.get(PhoneCalibrationConfigService.KEY)).isEqualTo(before);
        service(2000).publish(proposal(1,5000,"34.2"));
        before = values.get(PhoneCalibrationConfigService.KEY);
        assertThatThrownBy(() -> service(3000).publish(proposal(2,0,"35")))
                .isInstanceOf(BizException.class).hasMessageContaining("PUBLICATION_PENDING");
        assertThat(values.get(PhoneCalibrationConfigService.KEY)).isEqualTo(before);
    }
    @Test void iosModelRulesPublishAndSurviveAClientThatOmitsThem() {
        var withIos = new PhoneCalibrationConfigService.Proposal(0, 0, PhoneCalibrationPolicyTest.policy(List.of()).thresholds(),
                List.of(), List.of(IosModelRulePolicyTest.ios("ios-iphone-16-pro", "iPhone 16 Pro", "100", "120")));
        assertThat(service(1000).publish(withIos).current().iosModelRules()).hasSize(1);
        // An older E6 client sends no iosModelRules: the published iPhone rules stay.
        var after = service(2000).publish(proposal(1, 0, "18.4"));
        assertThat(after.current().iosModelRules()).extracting(PhoneCalibrationPolicy.IosModelRule::model)
                .containsExactly("iPhone 16 Pro");
        assertThat(after.current().androidSocRules()).hasSize(1);
        // An explicit empty list is a deliberate removal of every iPhone rule.
        var cleared = new PhoneCalibrationConfigService.Proposal(2, 0, after.current().thresholds(), List.of(), List.of());
        assertThat(service(3000).publish(cleared).current().iosModelRules()).isEmpty();
    }
    @Test void corruptStoredRulesDoNotFallbackToPermissiveDefaults() {
        values.put(PhoneCalibrationConfigService.KEY, "{}");
        assertThatThrownBy(() -> service(1000).activePolicy()).isInstanceOf(BizException.class);
    }
}
