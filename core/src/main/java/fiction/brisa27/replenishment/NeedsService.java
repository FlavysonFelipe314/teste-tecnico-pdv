package fiction.brisa27.replenishment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fiction.brisa27.common.BusinessException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class NeedsService {
  private final RequestRepository repository;
  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final Duration ttl;
  private final String namespace;
  private final AtomicBoolean enabled;
  private final LongAdder hits = new LongAdder(), misses = new LongAdder(), errors = new LongAdder();
  private final LongAdder needsQueries = new LongAdder(), versionQueries = new LongAdder();

  public NeedsService(RequestRepository repository, StringRedisTemplate redis, ObjectMapper json,
      @Value("${lab.needs.ttl-seconds:60}") long ttlSeconds,
      @Value("${lab.needs.cache-enabled:true}") boolean enabled,
      @Value("${lab.needs.namespace:rota-clara:needs:v1}") String namespace) {
    if (ttlSeconds < 1) throw new IllegalArgumentException("TTL inválido");
    this.repository = repository; this.redis = redis; this.json = json;
    if (!namespace.matches("[A-Za-z0-9:_-]+")) throw new IllegalArgumentException("Namespace inválido");
    this.namespace = namespace;
    this.ttl = Duration.ofSeconds(ttlSeconds); this.enabled = new AtomicBoolean(enabled);
  }

  public List<Map<String, Object>> needs(String store) {
    if (!enabled.get()) return fresh(store);
    versionQueries.increment();
    var versions = repository.jdbc().queryForList("SELECT version FROM inventory_versions WHERE location_id=?", store);
    if (versions.isEmpty()) throw new BusinessException(404, "Loja não encontrada");
    long version = ((Number) versions.getFirst().get("version")).longValue();
    String key = namespace + ":" + store + ":" + version;
    try {
      String cached = redis.opsForValue().get(key);
      if (cached != null) {
        var rows = json.readValue(cached, new TypeReference<List<Map<String, Object>>>() {});
        if (rows == null || rows.stream().anyMatch(row -> row == null
            || !(row.get("product_id") instanceof String)
            || !(row.get("stock") instanceof Number)
            || !(row.get("target") instanceof Number)
            || !(row.get("need") instanceof Number)))
          throw new IllegalStateException("Entrada de cache inválida");
        hits.increment();
        return rows;
      }
    } catch (Exception e) {
      errors.increment();
      // Do not attempt a second Redis command in this request while it is unavailable.
      return fresh(store);
    }
    misses.increment();
    var rows = fresh(store);
    try { redis.opsForValue().set(key, json.writeValueAsString(rows), ttl); }
    catch (Exception e) { errors.increment(); }
    // A concurrent writer may have advanced the version; this old key is now unreachable.
    return rows;
  }

  /** Always used by order creation: cache never authorizes business writes. */
  public List<Map<String, Object>> fresh(String store) {
    needsQueries.increment();
    var rows = repository.jdbc().queryForList(
        "SELECT i.product_id,p.name product_name,i.stock,i.target FROM inventory i JOIN"
            + " products p ON p.id=i.product_id WHERE i.location_id=? AND i.target IS NOT NULL"
            + " ORDER BY product_id", store);
    if (rows.isEmpty()) throw new BusinessException(404, "Loja não encontrada");
    var result = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      int need = NeedCalculator.calculate(((Number) row.get("stock")).intValue(),
          ((Number) row.get("target")).intValue());
      if (need > 0) { row.put("need", need); result.add(row); }
    }
    return result;
  }

  public Map<String, Object> metrics() {
    return Map.of("cache_enabled", enabled.get(), "cache_hits", hits.sum(), "cache_misses", misses.sum(),
        "cache_errors", errors.sum(), "needs_db_queries", needsQueries.sum(),
        "version_db_queries", versionQueries.sum(), "ttl_seconds", ttl.toSeconds());
  }

  public void setEnabled(boolean value) { enabled.set(value); }
}
