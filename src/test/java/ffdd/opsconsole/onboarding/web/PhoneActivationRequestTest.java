package ffdd.opsconsole.onboarding.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.onboarding.application.OnboardingCalibrationService.ActionRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PhoneActivationRequestTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void missingBindingVersionAndExplicitFirstBindingAreDifferent() throws Exception {
        ActionRequest absent = json.readValue("{\"deviceId\":\"phone-B\",\"bindingVersionPresent\":true}", ActionRequest.class);
        ActionRequest first = json.readValue("{\"deviceId\":\"phone-B\",\"expectedBindingVersion\":null}", ActionRequest.class);
        assertThat(absent.bindingVersionPresent()).isFalse();
        assertThat(first.bindingVersionPresent()).isTrue();
        assertThat(first.expectedBindingVersion()).isNull();
    }

    @Test void expectedVersionsAndConfirmationAreStrictlyTyped() throws Exception {
        ActionRequest request = json.readValue("""
                {"deviceId":"phone-B","expectedRevision":4,"expectedBindingVersion":2,
                 "expectedPolicyVersion":7,"replacementConfirmed":true,"exceptionGrantId":"grant-1"}
                """, ActionRequest.class);
        assertThat(request.expectedRevision()).isEqualTo(4);
        assertThat(request.expectedBindingVersion()).isEqualTo(2);
        assertThat(request.expectedPolicyVersion()).isEqualTo(7);
        assertThat(request.replacementConfirmed()).isTrue();
        for (String invalid : new String[] {"{\"expectedBindingVersion\":1.5}",
                "{\"expectedPolicyVersion\":-1}", "{\"replacementConfirmed\":\"true\"}",
                "{\"expectedBindingVersion\":9223372036854775808}"})
            assertThatThrownBy(() -> json.readValue(invalid, ActionRequest.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
