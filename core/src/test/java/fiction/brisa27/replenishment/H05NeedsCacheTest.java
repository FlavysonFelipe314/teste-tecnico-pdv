package fiction.brisa27.replenishment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import fiction.brisa27.common.BusinessException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(H04DocumentRecoveryTest.Config.class)
class H05NeedsCacheTest {
  @Autowired DataSource source;
  @Autowired RequestRepository repository;
  @Autowired RequestService service;
  @Autowired JdbcTemplate jdbc;
  private NeedsService cache;
  private StringRedisTemplate redis;
  private ValueOperations<String, String> values;
  private ConcurrentHashMap<String, String> entries;

  @BeforeEach @SuppressWarnings("unchecked") void setup() {
    ReplenishmentTestConfig.cleanBase(source);
    redis = mock(StringRedisTemplate.class); values = mock(ValueOperations.class);
    entries = new ConcurrentHashMap<>(); when(redis.opsForValue()).thenReturn(values);
    doAnswer(i -> entries.get(i.getArgument(0))).when(values).get(anyString());
    doAnswer(i -> { entries.put(i.getArgument(0), i.getArgument(1)); return null; })
        .when(values).set(anyString(), anyString(), any(Duration.class));
    cache = new NeedsService(repository, redis, new ObjectMapper(), 60, true, "rota-clara:needs:v1");
  }
  private long metric(String key) { return ((Number) cache.metrics().get(key)).longValue(); }
  private int water(String store) {
    return ((Number) cache.needs(store).stream().filter(r -> "agua-500".equals(r.get("product_id")))
        .findFirst().orElseThrow().get("need")).intValue();
  }
  private String create() {
    return (String) service.create(new Inputs.Create("loja-aurora", List.of(
        new Inputs.Item("agua-500", 6), new Inputs.Item("cafe-250", 2))), "MANAGER").get("id");
  }
  private void confirm(String id) {
    service.confirm(id, new Inputs.Confirm(List.of(new Inputs.Received("agua-500", 4),
        new Inputs.Received("cafe-250", 0))), "OPERATOR");
  }

  @Test void h05SameRouteReducesNeedsQueriesFromThreeToOneAndReportsVersionCost() {
    cache.setEnabled(false);
    for (int i = 0; i < 3; i++) assertEquals(6, water("loja-aurora"));
    assertEquals(3, metric("needs_db_queries")); assertEquals(0, metric("version_db_queries"));
    cache.setEnabled(true);
    for (int i = 0; i < 3; i++) assertEquals(6, water("loja-aurora"));
    assertEquals(4, metric("needs_db_queries")); assertEquals(3, metric("version_db_queries"));
    assertEquals(2, metric("cache_hits")); assertEquals(1, metric("cache_misses"));
    verify(values).set(eq("rota-clara:needs:v1:loja-aurora:0"), anyString(), eq(Duration.ofSeconds(60)));
    assertTrue(entries.values().stream().noneMatch(s -> s.contains("MANAGER") || s.contains("gestor-local")));
  }

  @Test void h05StoresUseSeparateKeys() {
    assertEquals(6, water("loja-aurora")); assertEquals(6, water("loja-lagoa"));
    assertEquals(6, water("loja-aurora")); assertEquals(2, metric("needs_db_queries"));
    assertEquals(2, entries.size());
    assertFalse(cache.needs("loja-lagoa").stream().anyMatch(r -> "cafe-250".equals(r.get("product_id"))));
  }

  @Test void h05ConfirmationImmediatelyChangesVersionAndRetryDoesNotChangeItAgain() {
    assertEquals(6, water("loja-aurora")); cache.needs("loja-lagoa");
    String id = create(); confirm(id);
    assertEquals(2, water("loja-aurora"));
    long before = metric("needs_db_queries"); cache.needs("loja-lagoa");
    assertEquals(before, metric("needs_db_queries"));
    confirm(id); assertEquals(2, water("loja-aurora"));
    assertEquals(1L, jdbc.queryForObject("SELECT version FROM inventory_versions WHERE location_id='loja-aurora'", Long.class));
    assertTrue(entries.containsKey("rota-clara:needs:v1:loja-aurora:1"));
  }

  @Test void h05RedisUnavailableAllowsReadsAndConfirmationThenCannotReviveOldCache() {
    assertEquals(6, water("loja-aurora")); String id = create();
    when(values.get(anyString())).thenThrow(new IllegalStateException("redis offline"));
    assertEquals(6, water("loja-aurora")); confirm(id); assertEquals(2, water("loja-aurora"));
    assertEquals(2, metric("cache_errors"));
    doAnswer(i -> entries.get(i.getArgument(0))).when(values).get(anyString());
    assertEquals(2, water("loja-aurora")); assertEquals(2, water("loja-aurora"));
    assertTrue(entries.containsKey("rota-clara:needs:v1:loja-aurora:0"));
  }

  @Test void h05ConcurrentOldReaderCannotRepublishOldStockIntoCurrentVersion() throws Exception {
    String id = create(); var readCompleted = new CountDownLatch(1); var release = new CountDownLatch(1);
    doAnswer(i -> {
      readCompleted.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS));
      entries.put(i.getArgument(0), i.getArgument(1)); return null;
    }).when(values).set(eq("rota-clara:needs:v1:loja-aurora:0"), anyString(), any(Duration.class));
    var pool = Executors.newSingleThreadExecutor();
    try {
      var old = pool.submit(() -> water("loja-aurora"));
      assertTrue(readCompleted.await(10, TimeUnit.SECONDS));
      confirm(id); assertEquals(2, water("loja-aurora"));
      release.countDown(); assertEquals(6, old.get(10, TimeUnit.SECONDS));
      assertEquals(2, water("loja-aurora"));
      assertEquals(2, metric("needs_db_queries"));
    } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
  }

  @Test void h05RedisWriteFailureOrCorruptEntryNeverBlocksDatabaseResult() {
    doThrow(new IllegalStateException("write failed")).when(values).set(anyString(), anyString(), any(Duration.class));
    assertEquals(6, water("loja-aurora")); assertEquals(1, metric("cache_errors"));
    entries.put("rota-clara:needs:v1:loja-aurora:0", "invalid json");
    assertEquals(6, water("loja-aurora")); assertEquals(2, metric("cache_errors"));
  }

  @Test void h05CachedEmptyListIsReusedAtTarget() {
    jdbc.update("UPDATE inventory SET stock=target WHERE location_id='loja-aurora'");
    assertTrue(cache.needs("loja-aurora").isEmpty()); assertTrue(cache.needs("loja-aurora").isEmpty());
    assertEquals(1, metric("needs_db_queries")); assertEquals(1, metric("cache_hits"));
  }

  @Test void h05CreationUsesFreshDatabaseValidationAndUnknownStoreIsNotCached() {
    String id = create(); assertEquals(6, water("loja-aurora")); confirm(id);
    var failure = assertThrows(BusinessException.class, () -> service.create(new Inputs.Create("loja-aurora",
        List.of(new Inputs.Item("agua-500", 3))), "MANAGER"));
    assertEquals(400, failure.status);
    assertThrows(BusinessException.class, () -> cache.needs("unknown"));
    assertFalse(entries.keySet().stream().anyMatch(key -> key.contains("unknown")));
  }

  @Test void h05NullOrIncompleteCachedRowsFallBackInsteadOfBreakingPanel() {
    for (String corrupt : List.of("null", "[null]", "[{}]")) {
      entries.put("rota-clara:needs:v1:loja-aurora:0", corrupt);
      assertEquals(6, water("loja-aurora"));
    }
    assertEquals(3, metric("cache_errors"));
    assertEquals(0, metric("cache_hits"));
  }
}
