package fiction.brisa27.replenishment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import fiction.brisa27.admin.AdminController;
import fiction.brisa27.common.*;
import fiction.brisa27.documents.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.client.ResourceAccessException;

@SpringJUnitConfig(H04DocumentRecoveryTest.Config.class)
class H04DocumentRecoveryTest {
  static class MutableClock extends Clock {
    private Instant instant = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plus(Duration.ofDays(1));
    // Stay ahead of SQL CURRENT_TIMESTAMP without making the tests expire on a fixed date.
    void reset() { instant = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plus(Duration.ofDays(1)); }
    void advance(long seconds) { instant = instant.plusSeconds(seconds); }
    @Override public Instant instant() { return instant; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
  }

  @Configuration @EnableTransactionManagement
  static class Config {
    @Bean(destroyMethod = "") DataSource dataSource() { return new ReplenishmentTestConfig().dataSource(); }
    @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
    @Bean DataSourceTransactionManager transactionManager(DataSource source) {
      return new DataSourceTransactionManager(source);
    }
    @Bean RequestRepository repository(JdbcTemplate jdbc) { return new RequestRepository(jdbc); }
    @Bean MutableClock clock() { return new MutableClock(); }
    @Bean ObjectMapper json() { return new ObjectMapper(); }
    @Bean DocumentGateway gateway() { return mock(DocumentGateway.class); }
    @Bean DocumentQueue queue(RequestRepository r, ObjectMapper json, MutableClock clock) {
      return new DocumentQueue(r, json, clock, 3, 10, 2);
    }
    @Bean DocumentService documents(RequestRepository r, DocumentQueue q, DocumentGateway g) {
      return new DocumentService(r, q, g);
    }
    @Bean DocumentProcessor processor(DocumentQueue q, DocumentGateway g) { return new DocumentProcessor(q, g); }
    @Bean NeedsService needs(RequestRepository r, ObjectMapper json) {
      return new NeedsService(r, mock(StringRedisTemplate.class), json, 60, false, "rota-clara:needs:v1");
    }
    @Bean RequestService service(RequestRepository r, DocumentService d, NeedsService n) {
      return new RequestService(r, d, n);
    }
  }

  @Autowired DataSource source;
  @Autowired JdbcTemplate jdbc;
  @Autowired RequestRepository repository;
  @Autowired RequestService service;
  @Autowired DocumentService documents;
  @Autowired DocumentGateway gateway;
  @Autowired DocumentQueue queue;
  @Autowired DocumentProcessor processor;
  @Autowired MutableClock clock;
  @Autowired NeedsService needs;
  private MockMvc api;
  private String id;

  @BeforeEach void setup() {
    ReplenishmentTestConfig.cleanBase(source);
    reset(gateway); clock.reset(); needs.setEnabled(false);
    id = (String) service.create(new Inputs.Create("loja-aurora", List.of(
        new Inputs.Item("agua-500", 6), new Inputs.Item("cafe-250", 2))), "MANAGER").get("id");
    api = MockMvcBuilders.standaloneSetup(new AdminController(new LabIdentity(), repository, service, documents))
        .setControllerAdvice(new Errors()).build();
  }

  private void confirm() { service.confirm(id, new Inputs.Confirm(List.of(
      new Inputs.Received("agua-500", 4), new Inputs.Received("cafe-250", 0))), "OPERATOR"); }
  private Map<String, Object> authorizedResponse() {
    return Map.of("document_key", "doc-" + id, "status", "AUTHORIZED_SIMULATION", "protocol", "SIM-" + id);
  }
  private void providerSuccess() {
    doReturn(null).when(gateway).lookup(anyString());
    when(gateway.authorize(anyMap())).thenReturn(authorizedResponse());
    when(gateway.xml(anyString())).thenReturn("<Transferencia ambiente=\"SIMULACAO\"/>".getBytes());
    when(gateway.download(anyString())).thenReturn("SIMULACAO".getBytes());
  }
  private void assertStockUnchanged(Map<String, Object> request, List<Map<String, Object>> stock) {
    assertEquals(request, repository.detail(id)); assertEquals(stock, repository.inventory());
  }
  private String status() { return (String) repository.document(id).get("status"); }

  @Test void h04ConfirmationPersistsIntentWithoutCallingExternalDependencies() throws Exception {
    confirm();
    assertEquals("PENDING", status()); verifyNoInteractions(gateway);
    assertEquals("CONFIRMED", repository.detail(id).get("status"));
    var payload = new ObjectMapper().readTree((String) repository.document(id).get("payload"));
    assertEquals(4, payload.at("/items/0/quantity").asInt());
    assertEquals(0, payload.at("/items/1/quantity").asInt());
    assertEquals("doc-" + id, payload.get("document_key").asText());
    assertEquals(0, repository.document(id).get("attempts"));
    confirm();
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM documents", Integer.class));
    assertEquals(1L, jdbc.queryForObject("SELECT version FROM inventory_versions WHERE location_id='loja-aurora'", Long.class));
  }

  @Test void h04FailureToPersistIntentRollsBackReceiptStockAndVersion() {
    jdbc.execute("ALTER TABLE documents ADD CONSTRAINT h04_fail CHECK(status <> 'PENDING')");
    var stock = repository.inventory(); var request = repository.detail(id);
    assertThrows(RuntimeException.class, this::confirm);
    assertStockUnchanged(request, stock);
    assertEquals(0L, jdbc.queryForObject("SELECT version FROM inventory_versions WHERE location_id='loja-aurora'", Long.class));
    assertEquals("NOT_REQUESTED", status());
  }

  @Test void h04UnavailableProviderRecoversWithoutAnotherReceipt() {
    confirm(); var stock = repository.inventory(); var request = repository.detail(id);
    when(gateway.lookup(anyString())).thenThrow(new ResourceAccessException("offline"));
    assertTrue(processor.processOne()); assertEquals("RETRY_WAIT", status());
    assertFalse(processor.processOne());
    clock.advance(2); providerSuccess();
    assertTrue(processor.processOne()); assertEquals("AUTHORIZED_SIMULATION", status());
    assertStockUnchanged(request, stock);
    assertEquals(2, repository.document(id).get("attempts"));
    assertFalse(processor.processOne()); verify(gateway, times(1)).authorize(anyMap());
  }

  @Test void h04LostResponseReconcilesSameKeyWithoutAuthorizingAgain() {
    confirm(); var issued = new AtomicReference<Map<String, Object>>();
    when(gateway.lookup("doc-" + id)).thenAnswer(i -> issued.get());
    when(gateway.authorize(anyMap())).thenAnswer(i -> {
      assertEquals("doc-" + id, ((Map<?, ?>) i.getArgument(0)).get("document_key"));
      issued.set(authorizedResponse()); throw new ResourceAccessException("response lost");
    });
    when(gateway.xml(anyString())).thenReturn("SIMULACAO".getBytes());
    processor.processOne(); assertEquals("RETRY_WAIT", status());
    clock.advance(2); processor.processOne();
    assertEquals("AUTHORIZED_SIMULATION", status());
    verify(gateway, times(1)).authorize(anyMap());
    verify(gateway).store(eq("doc-" + id + ".xml"), any(byte[].class));
  }

  @Test void h04RejectionIsTerminalAndPreservesReason() {
    confirm(); doReturn(null).when(gateway).lookup(anyString());
    when(gateway.authorize(anyMap())).thenReturn(Map.of("document_key", "doc-" + id,
        "status", "REJECTED_SIMULATION", "reason", "Dados rejeitados"));
    processor.processOne(); assertEquals("REJECTED_SIMULATION", status());
    assertEquals("Dados rejeitados", repository.document(id).get("reason"));
    clock.advance(10000); assertFalse(processor.processOne());
    verify(gateway, times(1)).authorize(anyMap());
    assertThrows(BusinessException.class, () -> documents.reprocess(id));
  }

  @Test void h04MinioFailureAfterAuthorizationReusesProtocolAndObjectKey() {
    confirm(); providerSuccess();
    when(gateway.lookup(anyString())).thenReturn(null, authorizedResponse());
    doThrow(new IllegalStateException("minio offline")).doNothing().when(gateway).store(anyString(), any(byte[].class));
    processor.processOne(); assertEquals("RETRY_WAIT", status());
    assertEquals("SIM-" + id, repository.document(id).get("protocol"));
    clock.advance(2); processor.processOne();
    assertEquals("AUTHORIZED_SIMULATION", status());
    verify(gateway, times(1)).authorize(anyMap());
    verify(gateway, times(2)).store(eq("doc-" + id + ".xml"), any(byte[].class));
  }

  @Test void h04RestartRecoversAbandonedLeaseAndOldWorkerCannotOverwriteResult() {
    confirm(); providerSuccess();
    var old = queue.claim(); assertNotNull(old); assertEquals("PROCESSING", status());
    var restarted = new DocumentProcessor(queue, gateway);
    assertFalse(restarted.processOne());
    clock.advance(11); assertTrue(restarted.processOne());
    assertEquals("AUTHORIZED_SIMULATION", status());
    queue.permanentFailure(old, "stale worker");
    queue.authorized(old, "wrong", "wrong.xml");
    assertEquals("SIM-" + id, repository.document(id).get("protocol"));
    assertEquals("doc-" + id + ".xml", repository.document(id).get("object_key"));
    assertEquals(2, repository.document(id).get("attempts"));
    verify(gateway, times(1)).authorize(anyMap());
  }

  @Test void h04AttemptsAreBoundedAndManagerCanReprocessWithoutMovingStock() throws Exception {
    confirm(); var request = repository.detail(id); var stock = repository.inventory();
    when(gateway.lookup(anyString())).thenThrow(new ResourceAccessException("offline"));
    processor.processOne(); clock.advance(2); processor.processOne(); clock.advance(4); processor.processOne();
    assertEquals("FAILED", status()); clock.advance(10000); assertFalse(processor.processOne());
    assertEquals(3, repository.document(id).get("attempts"));
    api.perform(post("/admin/requests/{id}/document/reprocess", id)
        .header("Authorization", "Bearer operador-local"))
        .andExpect(jsonPath("$.status").value(403));
    api.perform(post("/admin/requests/{id}/document/reprocess", id)
        .header("Authorization", "Bearer gestor-local"))
        .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.status").value("PENDING"));
    providerSuccess(); processor.processOne(); assertEquals("AUTHORIZED_SIMULATION", status());
    assertEquals(4, repository.document(id).get("attempts"));
    assertStockUnchanged(request, stock);
    api.perform(get("/admin/requests/{id}/document/xml", id))
        .andExpect(jsonPath("$.status").value(401));
    api.perform(get("/admin/requests/{id}/document/xml", id).header("Authorization", "Bearer operador-local"))
        .andExpect(content().string("SIMULACAO"));
  }

  @Test void h04AbandonedFinalAttemptBecomesVisibleFailure() {
    confirm();
    assertNotNull(queue.claim()); clock.advance(11);
    assertNotNull(queue.claim()); clock.advance(11);
    assertNotNull(queue.claim()); clock.advance(11);
    assertNull(queue.claim()); assertEquals("FAILED", status());
    assertEquals(3, repository.document(id).get("attempts"));
  }

  @Test void h04TwoWorkersClaimOneTaskAndProduceOneEffect() throws Exception {
    confirm(); providerSuccess(); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> task = () -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return processor.processOne(); };
      var a = pool.submit(task); var b = pool.submit(task);
      assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
      assertNotEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
      assertEquals("AUTHORIZED_SIMULATION", status());
      verify(gateway, times(1)).authorize(anyMap()); verify(gateway, times(1)).store(anyString(), any(byte[].class));
      assertEquals(1, repository.document(id).get("attempts"));
    } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
  }

  @Test void h04V2MigrationRecoversLegacyFailedDocumentsWithoutInventingRequested() {
    // Rebuild the V1 database to exercise upgrade of an already existing installation.
    for (String table : new String[] {"documents", "request_items", "replenishment_requests", "inventory_versions", "inventory", "products", "locations"})
      jdbc.execute("DROP TABLE IF EXISTS " + table);
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__initial.sql")).execute(source);
    jdbc.update("INSERT INTO replenishment_requests(id,destination,origin,status) VALUES (?,'loja-aurora','centro-horizonte','CONFIRMED')", id);
    jdbc.update("INSERT INTO request_items(request_id,product_id,requested,received) VALUES (?,'agua-500',4,4)", id);
    jdbc.update("INSERT INTO documents(request_id,document_key,status,attempts) VALUES (?,?,'FAILED',2)", id, "doc-" + id);
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__durable_documents_and_inventory_versions.sql")).execute(source);
    assertEquals("PENDING", status());
    providerSuccess(); assertTrue(processor.processOne()); assertEquals("AUTHORIZED_SIMULATION", status());
    assertEquals(4, repository.items(id).getFirst().get("requested"));
    assertEquals(3, repository.document(id).get("attempts"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"malformed", "null", "wrong_key", "empty_items"})
  void h04CorruptSnapshotFailsWithoutCallingProviderOrBlockingNextTask(String scenario) throws Exception {
    confirm();
    var json = new ObjectMapper();
    var snapshot = json.readTree((String) repository.document(id).get("payload"));
    String corrupt = switch (scenario) {
      case "malformed" -> "{invalid";
      case "null" -> "null";
      case "wrong_key" -> json.writeValueAsString(((com.fasterxml.jackson.databind.node.ObjectNode) snapshot)
          .put("document_key", "wrong-key"));
      default -> json.writeValueAsString(((com.fasterxml.jackson.databind.node.ObjectNode) snapshot)
          .set("items", json.createArrayNode()));
    };
    jdbc.update("UPDATE documents SET payload=? WHERE request_id=?", corrupt, id);
    assertFalse(processor.processOne());
    assertEquals("FAILED", status());
    assertEquals(0, repository.document(id).get("attempts"));
    verifyNoInteractions(gateway);
    String next = (String) service.create(new Inputs.Create("loja-aurora",
        List.of(new Inputs.Item("agua-500", 2))), "MANAGER").get("id");
    service.confirm(next, new Inputs.Confirm(List.of(new Inputs.Received("agua-500", 1))), "OPERATOR");
    assertEquals(next, queue.claim().requestId());
  }
}
