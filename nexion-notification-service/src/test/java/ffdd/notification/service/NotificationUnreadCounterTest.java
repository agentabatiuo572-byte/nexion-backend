package ffdd.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.notification.dto.NotificationUnreadCountResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class NotificationUnreadCounterTest {
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final NotificationUnreadCounter counter = new NotificationUnreadCounter(redisTemplate, 60);

    @Test
    void returnsCachedUnreadCountWhenRedisHasValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("notification:unread:10001")).thenReturn("7");

        NotificationUnreadCountResponse response = counter.count(10001L, () -> 3L);

        assertThat(response.getUserId()).isEqualTo(10001L);
        assertThat(response.getUnreadCount()).isEqualTo(7);
        assertThat(response.getCacheStatus()).isEqualTo("HIT");
    }

    @Test
    void rebuildsUnreadCountFromDatabaseOnCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        NotificationUnreadCountResponse response = counter.count(10001L, () -> 4L);

        assertThat(response.getUnreadCount()).isEqualTo(4);
        assertThat(response.getCacheStatus()).isEqualTo("MISS");
        verify(valueOperations).set("notification:unread:10001", "4", Duration.ofSeconds(60));
    }

    @Test
    void fallsBackToDatabaseWhenRedisFails() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        NotificationUnreadCountResponse response = counter.count(10001L, () -> 5L);

        assertThat(response.getUnreadCount()).isEqualTo(5);
        assertThat(response.getCacheStatus()).isEqualTo("FALLBACK");
    }
}
