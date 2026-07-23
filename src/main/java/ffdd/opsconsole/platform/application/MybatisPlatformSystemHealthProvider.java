package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisPlatformSystemHealthProvider implements PlatformSystemHealthProvider {
    private final PlatformConfigItemMapper mapper;

    @Override
    public List<Map<String, Object>> currentHealth() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(probe("事件待投递", "nx_event_outbox", this::eventBacklog));
        rows.add(probe("资金账本可读性", "nx_wallet_ledger", this::ledgerRead));
        rows.add(probe("数据库时钟偏差", "MySQL CURRENT_TIMESTAMP", this::databaseClock));
        rows.add(jvmRuntime());
        return List.copyOf(rows);
    }

    private HealthMetric eventBacklog() {
        Map<String, Object> result = mapper.selectA3EventBacklog();
        long backlog = number(result.get("backlog"));
        long oldest = number(result.get("oldest_seconds"));
        String tone = backlog > 1000 || oldest > 3600 ? "bad" : backlog > 100 || oldest > 300 ? "warn" : "ok";
        return new HealthMetric(tone, backlog + " 条 · 最久 " + oldest + " 秒");
    }

    private HealthMetric ledgerRead() {
        long started = System.nanoTime();
        Long count = mapper.countA3LedgerEntries24h();
        long elapsedMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        String tone = elapsedMs > 1000 ? "bad" : elapsedMs > 200 ? "warn" : "ok";
        return new HealthMetric(tone, "24h " + (count == null ? 0 : count) + " 笔 · 查询 " + elapsedMs + "ms");
    }

    private HealthMetric databaseClock() {
        Long databaseEpoch = mapper.selectA3DatabaseEpochMillis();
        long drift = databaseEpoch == null ? Long.MAX_VALUE : Math.abs(Instant.now().toEpochMilli() - databaseEpoch);
        String tone = drift > 5000 ? "bad" : drift > 1000 ? "warn" : "ok";
        return new HealthMetric(tone, drift == Long.MAX_VALUE ? "无法读取" : "偏差 " + drift + "ms");
    }

    private Map<String, Object> jvmRuntime() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long usedMb = memory.getHeapMemoryUsage().getUsed() / 1024 / 1024;
        long maxMb = Math.max(1, memory.getHeapMemoryUsage().getMax() / 1024 / 1024);
        long pct = usedMb * 100 / maxMb;
        String tone = pct > 95 ? "bad" : pct > 85 ? "warn" : "ok";
        return row(
                "后台服务 JVM",
                tone,
                "已运行 " + ManagementFactory.getRuntimeMXBean().getUptime() / 1000 + " 秒 · 堆 " + usedMb + "/" + maxMb + "MB",
                "JVM RuntimeMXBean",
                false);
    }

    private Map<String, Object> probe(String name, String source, Supplier<HealthMetric> supplier) {
        try {
            HealthMetric metric = supplier.get();
            return row(name, metric.tone(), metric.metric(), source, false);
        } catch (RuntimeException ex) {
            return row(name, "bad", "读取失败 · 不推测正常状态", source, true);
        }
    }

    private Map<String, Object> row(String name, String tone, String metric, String source, boolean stale) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("tone", tone);
        row.put("metric", metric);
        row.put("source", source);
        row.put("observedAt", LocalDateTime.now().toString());
        row.put("stale", stale);
        return row;
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private record HealthMetric(String tone, String metric) {
    }
}
