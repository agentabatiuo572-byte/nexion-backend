package ffdd.opsconsole.content.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

class SupportCustomerAvatarTest {
    @Test void conversationRowsCarryOnlyTheConsoleReference() throws Exception {
        var withAvatar = new ContentConversationView(1L, "CV-1", 20L, "support", "OPEN", null, null, 0, "", null,
                null, null, null, null, null, null, null, null, 1L, 1L, null, false, 0L, null, null,
                "Linh", "users/20/avatar/a.png");
        var json = new ObjectMapper().registerModule(new JavaTimeModule()).readTree(
                new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(withAvatar));
        assertThat(json.get("customerAvatarRef").asText()).isEqualTo("/api/admin/content/support-workbench/customers/20/avatar");
        assertThat(json.has("customerAvatarKey")).isFalse();
    }

    @Test void foreignOrTraversingKeysAreNotTheCustomersAvatar() {
        assertThat(SupportCustomerAvatar.ref(20L, "users/21/avatar/a.png")).isNull();
        assertThat(SupportCustomerAvatar.ref(20L, "users/20/avatar/../../x.png")).isNull();
        assertThat(SupportCustomerAvatar.ref(20L, null)).isNull();
        assertThat(SupportCustomerAvatar.ref(null, "users/20/avatar/a.png")).isNull();
    }
}
