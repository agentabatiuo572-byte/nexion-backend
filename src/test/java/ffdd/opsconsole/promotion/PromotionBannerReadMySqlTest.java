package ffdd.opsconsole.promotion;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import ffdd.opsconsole.promotion.application.PromotionBannerService;
import ffdd.opsconsole.promotion.application.PromotionPublicService;
import ffdd.opsconsole.promotion.mapper.PromotionMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static ffdd.opsconsole.promotion.domain.PromotionValues.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="GROWTH_PROMOTION_RUNTIME", matches="1")
class PromotionBannerReadMySqlTest {
    @Test void realSqlListsOnlyCurrentlyActivePublishedVersionsAndBindsArtworkToThatVersion() throws Exception {
        var credentials = new HashMap<String,String>();
        for (String line : Files.readAllLines(Path.of("D:/CodexData/test-environments/workflow-runs/growth-promotions-20261007/mysql/client.private.ini"))) {
            if (!line.contains("=") || line.strip().startsWith("#")) continue;
            var pair = line.split("=", 2);
            credentials.put(pair[0].strip(), pair[1].strip().replaceAll("^\"|\"$", ""));
        }
        try (var connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:33339/growth_promotions_20261007?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&sessionVariables=time_zone='%2B08:00'", credentials.get("user"), credentials.get("password"))) {
            // Temporary tables shadow task tables only on this connection; no persistent activity or money is changed.
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TEMPORARY TABLE nx_promotion(activity_id VARCHAR(128) PRIMARY KEY,active_version BIGINT,status VARCHAR(32),deleted_at DATETIME(6))");
                statement.execute("CREATE TEMPORARY TABLE nx_promotion_version(activity_id VARCHAR(128),version BIGINT,status VARCHAR(32),starts_at DATETIME(6),ends_at DATETIME(6),contract_json JSON,resolved_policies_json JSON,PRIMARY KEY(activity_id,version))");
            }
            var key = "admin/growth/promotion-banner/20261011/8bbef0bd-8f4e-4c89-a7be-57238a041a36.png";
            var asset = Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
            Instant now = Instant.now();
            for (String state : List.of("ACTIVE", "SCHEDULED", "PAUSED", "ENDED", "ARCHIVED", "DRAFT", "PENDING_APPROVAL", "APPROVED", "FUTURE", "EXPIRED", "UNPUBLISHED")) {
                Instant from = now.plusSeconds(state.equals("FUTURE") ? 3600 : -3600);
                Instant to = now.plusSeconds(state.equals("EXPIRED") ? -1 : 7200);
                var contract = values("bannerAssetId", asset, "startsAt", from.toString(), "endsAt", to.toString(), "rules", List.of(), "placement", "home.purchase-promotion");
                try (var root = connection.prepareStatement("INSERT INTO nx_promotion(activity_id,active_version,status) VALUES(?,2,?)");
                     var version = connection.prepareStatement("INSERT INTO nx_promotion_version VALUES(?,2,?,?,?,?,NULL)")) {
                    root.setString(1, state); root.setString(2, Set.of("FUTURE", "EXPIRED", "UNPUBLISHED").contains(state) ? "ACTIVE" : state); root.executeUpdate();
                    version.setString(1, state); version.setString(2, state.equals("UNPUBLISHED") ? "DRAFT" : "PUBLISHED");
                    version.setTimestamp(3, timestamp(from)); version.setTimestamp(4, timestamp(to)); version.setString(5, json(contract)); version.executeUpdate();
                }
            }
            var config = new MybatisConfiguration(new Environment("promotion-banner-read-isolated", new SpringManagedTransactionFactory(), new SingleConnectionDataSource(connection, true)));
            config.addMapper(PromotionMapper.class);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
                var db = session.getMapper(PromotionMapper.class);
                var storage = mock(ObjectStorageService.class);
                byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/qXcAAAAASUVORK5CYII=");
                when(storage.exists(key)).thenReturn(true);
                when(storage.get(key)).thenAnswer(i -> new ByteArrayInputStream(png));
                var banners = new PromotionBannerService(db, storage);
                var publicApi = new PromotionPublicService(db, null, null, banners);
                var items = maps(publicApi.page(null, null, 100, null, "home.purchase-promotion").get("items"));
                assertEquals(Set.of("ACTIVE", "SCHEDULED"), new HashSet<>(items.stream().map(i -> text(i.get("activityId"))).toList()));
                for (var item : items) assertEquals("ACTIVE", item.get("state"));
                assertArrayEquals(png, banners.read("ACTIVE", 2).bytes());
                assertEquals(404, assertThrows(BizException.class, () -> banners.read("ACTIVE", 1)).getCode());
                for (String id : List.of("PAUSED", "ENDED", "ARCHIVED", "DRAFT", "PENDING_APPROVAL", "APPROVED", "FUTURE", "EXPIRED", "UNPUBLISHED"))
                    assertEquals(404, assertThrows(BizException.class, () -> banners.read(id, 2)).getCode(), id);
                assertEquals("PAUSED", publicApi.get(null, "PAUSED").get("state"));
                assertNull(publicApi.get(null, "PAUSED").get("bannerImageUrl"));
                assertEquals("ENDED", publicApi.get(null, "ENDED").get("state"));
                verify(storage, times(1)).get(key);
            }
        }
    }
}
