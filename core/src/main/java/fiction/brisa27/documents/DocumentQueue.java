package fiction.brisa27.documents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fiction.brisa27.common.BusinessException;
import fiction.brisa27.replenishment.RequestRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentQueue {
  public record Task(String requestId, String key, String token, int attempt,
                     Map<String, Object> payload) {}
  private final RequestRepository repository;
  private final ObjectMapper json;
  private final Clock clock;
  private final int maxAttempts;
  private final long leaseSeconds;
  private final long retrySeconds;

  public DocumentQueue(RequestRepository repository, ObjectMapper json, Clock clock,
      @Value("${lab.documents.max-attempts:5}") int maxAttempts,
      @Value("${lab.documents.lease-seconds:30}") long leaseSeconds,
      @Value("${lab.documents.retry-seconds:2}") long retrySeconds) {
    if (maxAttempts < 1 || leaseSeconds < 1 || retrySeconds < 1)
      throw new IllegalArgumentException("Configuração documental inválida");
    this.repository = repository; this.json = json; this.clock = clock;
    this.maxAttempts = maxAttempts; this.leaseSeconds = leaseSeconds; this.retrySeconds = retrySeconds;
  }

  private Timestamp now() { return Timestamp.from(clock.instant()); }

  private Map<String, Object> payload(String id, String key) {
    var request = repository.detail(id);
    var items = repository.items(id).stream()
        .map(i -> Map.of("product_id", i.get("product_id"), "quantity", i.get("received"))).toList();
    return Map.of("document_key", key, "delivery_id", id, "origin_id", request.get("origin"),
        "destination_id", request.get("destination"), "items", items);
  }

  private String encode(Map<String, Object> payload) {
    try { return json.writeValueAsString(payload); }
    catch (Exception e) { throw new IllegalStateException("Não foi possível preservar a emissão", e); }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(String id) {
    String key = "doc-" + id;
    repository.jdbc().update("INSERT INTO documents(request_id,document_key,status,payload,next_attempt_at)"
        + " VALUES (?,?,'PENDING',?,?)", id, key, encode(payload(id, key)), now());
  }

  @Transactional
  public Task claim() {
    var rows = repository.jdbc().queryForList("SELECT * FROM documents WHERE"
        + " ((status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=? AND cycle_attempts<?)"
        + " OR (status='PROCESSING' AND lease_until<=?))"
        + " ORDER BY next_attempt_at,request_id LIMIT 1 FOR UPDATE SKIP LOCKED",
        now(), maxAttempts, now());
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    String id = (String) row.get("request_id"), key = (String) row.get("document_key");
    if (((Number) row.get("cycle_attempts")).intValue() >= maxAttempts) {
      repository.jdbc().update("UPDATE documents SET status='FAILED',reason=?,lease_token=NULL,lease_until=NULL"
          + " WHERE request_id=?", "Limite de tentativas atingido após interrupção; reprocessamento disponível", id);
      return null;
    }
    String token = UUID.randomUUID().toString();
    Map<String, Object> snapshot;
    try {
      // Migration compatibility: reconstruct only the received payload, never the lost requested value.
      snapshot = row.get("payload") == null ? payload(id, key)
          : json.readValue((String) row.get("payload"), new TypeReference<>() {});
      if (snapshot == null || !key.equals(snapshot.get("document_key"))
          || !id.equals(snapshot.get("delivery_id"))
          || !(snapshot.get("items") instanceof List<?> items) || items.isEmpty())
        throw new IllegalStateException("Snapshot documental inconsistente");
    } catch (Exception e) {
      repository.jdbc().update("UPDATE documents SET status='FAILED',reason=?,lease_token=NULL,lease_until=NULL"
          + " WHERE request_id=?", "Payload documental inválido; revisar antes de reprocessar", id);
      return null;
    }
    int attempt = ((Number) row.get("cycle_attempts")).intValue() + 1;
    repository.jdbc().update("UPDATE documents SET status='PROCESSING',payload=?,attempts=attempts+1,"
        + " cycle_attempts=?,lease_token=?,lease_until=?,updated_at=? WHERE request_id=?",
        encode(snapshot), attempt, token, Timestamp.from(clock.instant().plusSeconds(leaseSeconds)), now(), id);
    return new Task(id, key, token, attempt, snapshot);
  }

  public boolean authorization(Task task, String protocol) {
    return repository.jdbc().update("UPDATE documents SET protocol=?,updated_at=?"
        + " WHERE request_id=? AND status='PROCESSING' AND lease_token=?",
        protocol, now(), task.requestId(), task.token()) == 1;
  }

  public void authorized(Task task, String protocol, String objectKey) {
    repository.jdbc().update("UPDATE documents SET status='AUTHORIZED_SIMULATION',protocol=?,object_key=?,"
        + " reason=NULL,lease_until=NULL,lease_token=NULL,next_attempt_at=NULL,updated_at=?"
        + " WHERE request_id=? AND status='PROCESSING' AND lease_token=?",
        protocol, objectKey, now(), task.requestId(), task.token());
  }

  public void rejected(Task task, String reason) { finish(task, "REJECTED_SIMULATION", reason, null); }
  public void permanentFailure(Task task, String reason) { finish(task, "FAILED", reason, null); }

  public void retry(Task task, String reason) {
    if (task.attempt() >= maxAttempts) {
      finish(task, "FAILED", "Limite de tentativas atingido: " + reason, null);
    } else {
      long delay = Math.min(60, retrySeconds * (1L << Math.min(task.attempt() - 1, 10)));
      finish(task, "RETRY_WAIT", reason, Timestamp.from(clock.instant().plus(Duration.ofSeconds(delay))));
    }
  }

  private void finish(Task task, String status, String reason, Timestamp next) {
    repository.jdbc().update("UPDATE documents SET status=?,reason=?,next_attempt_at=?,"
        + " lease_until=NULL,lease_token=NULL,updated_at=?"
        + " WHERE request_id=? AND status='PROCESSING' AND lease_token=?",
        status, reason == null ? null : reason.substring(0, Math.min(255, reason.length())),
        next, now(), task.requestId(), task.token());
  }

  @Transactional
  public void reprocess(String id) {
    repository.detail(id);
    int changed = repository.jdbc().update("UPDATE documents SET status='PENDING',cycle_attempts=0,"
        + " next_attempt_at=?,reason=NULL,updated_at=? WHERE request_id=? AND status IN ('FAILED','RETRY_WAIT')",
        now(), now(), id);
    if (changed == 0) throw new BusinessException(409, "Documento não está disponível para reprocessamento");
  }
}
