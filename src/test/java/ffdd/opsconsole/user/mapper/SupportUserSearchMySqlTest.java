package ffdd.opsconsole.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import ffdd.opsconsole.user.infrastructure.MybatisUserOpsRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Read-only live regression: credentials and target identity are supplied by the local test runner. */
@EnabledIfEnvironmentVariable(named = "NEXION_TEST_USER_ID", matches = "[0-9]+")
class SupportUserSearchMySqlTest {
    private Connection connection;
    private SqlSession session;
    private MybatisUserOpsRepository repository;
    private long userId;
    private String phone;
    private String countryCode;
    private String nickname;

    @BeforeEach
    void openReadOnlySnapshot() throws Exception {
        userId = Long.parseLong(System.getenv("NEXION_TEST_USER_ID"));
        connection = DriverManager.getConnection(System.getenv("NEXION_DB_URL"),
                System.getenv("NEXION_DB_USERNAME"), System.getenv("NEXION_DB_PASSWORD"));
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("SELECT phone,country_code,nickname FROM nx_user WHERE id=? AND is_deleted=0")) {
            statement.setLong(1, userId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                phone = rows.getString(1);
                countryCode = rows.getString(2);
                nickname = rows.getString(3);
            }
        }
        Configuration config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(UserOpsMapper.class);
        session = new SqlSessionFactoryBuilder().build(config).openSession(connection);
        repository = new MybatisUserOpsRepository(session.getMapper(UserOpsMapper.class));
    }

    @AfterEach
    void closeSnapshot() throws Exception {
        if (connection != null && !connection.isClosed()) connection.rollback();
        if (session != null) session.close();
        if (connection != null && !connection.isClosed()) connection.close();
    }

    @Test
    void phoneSuffixFullInternationalNameAndUserCodeFindTheSameRealUser() {
        String digits = phone.replaceAll("[^0-9]", "");
        for (String keyword : List.of(digits.substring(digits.length() - 4), phone,
                "+" + countryCode.replaceAll("[^0-9]", "") + " " + phone, nickname, "U" + userId)) {
            var result = repository.pageSupportProfiles(UserQueryRequest.basic(keyword, null, null, 1, 8, null));
            assertThat(result.getTotal()).isPositive();
            assertThat(result.getRecords()).extracting(UserAccountView::id).contains(userId);
            assertThat(result.getRecords()).filteredOn(user -> user.id().equals(userId))
                    .allSatisfy(user -> assertThat(user.phoneMasked()).contains("****").isNotEqualTo(phone));
        }
    }

    @Test
    void unfilteredPagesExposeAllUsersWithoutGapsOrDuplicates() {
        Set<Long> ids = new HashSet<>();
        long total = -1;
        for (int page = 1; total < 0 || (page - 1L) * 8 < total; page++) {
            var result = repository.pageSupportProfiles(UserQueryRequest.basic(null, null, null, page, 8, null));
            if (total < 0) total = result.getTotal();
            assertThat(result.getTotal()).isEqualTo(total);
            assertThat(result.getPageNum()).isEqualTo(page);
            assertThat(result.getPageSize()).isEqualTo(8);
            assertThat(result.getRecords()).hasSize((int) Math.min(8, total - (page - 1L) * 8));
            for (UserAccountView user : result.getRecords()) assertThat(ids.add(user.id())).isTrue();
        }
        assertThat(ids).hasSize((int) total).contains(userId);
    }
}
