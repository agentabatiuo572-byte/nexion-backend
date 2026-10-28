package ffdd.opsconsole.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RetiredDeviceIntegrationControllerTest {
    @Test
    void oldDeviceControlAndPairingUrlsCannotExecuteForAnyVerb() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new RetiredDeviceIntegrationController()).build();
        for (String path : new String[]{"/api/app/janus/reports", "/api/app/janus/commands/pending",
                "/api/app/janus/commands/ack", "/api/app/janus/takeover/progress",
                "/api/app/janus/compute-share/enrollments/claim", "/api/admin/janus/devices",
                "/api/admin/janus/remote-targets", "/api/admin/janus/strategies/legacy/publish",
                "/api/app/compute-share/enrollments", "/api/app/compute-share/enrollments/legacy"}) {
            for (HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
                    HttpMethod.PATCH, HttpMethod.DELETE}) {
                mvc.perform(request(method, path).contentType("application/json").content("{}"))
                        .andExpect(status().isGone()).andExpect(jsonPath("$.code").value(410));
            }
        }
    }
}
