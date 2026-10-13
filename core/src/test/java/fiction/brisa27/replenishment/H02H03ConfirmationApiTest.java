package fiction.brisa27.replenishment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fiction.brisa27.admin.AdminController;
import fiction.brisa27.common.Errors;
import fiction.brisa27.common.LabIdentity;
import fiction.brisa27.documents.DocumentService;
import java.util.List;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringJUnitConfig(ReplenishmentTestConfig.class)
class H02H03ConfirmationApiTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  @Autowired DataSource source;
  @Autowired JdbcTemplate jdbc;
  @Autowired RequestRepository repository;
  @Autowired RequestService service;
  @Autowired DocumentService documents;
  private MockMvc api;
  private String id;

  @BeforeEach
  void cleanBaseAndCreateRequest() throws Exception {
    ReplenishmentTestConfig.cleanBase(source);
    reset(documents);
    // Record a local document to assert that retries preserve attempts and protocol.
    doAnswer(invocation -> {
      jdbc.update("INSERT INTO documents(request_id,document_key,status,protocol,attempts)"
          + " VALUES (?,?,'AUTHORIZED_SIMULATION','test-protocol',1)",
          invocation.getArgument(0), "doc-" + invocation.getArgument(0));
      return null;
    }).when(documents).enqueue(anyString());
    api = MockMvcBuilders.standaloneSetup(
        new AdminController(new LabIdentity(), repository, service, documents))
        .setControllerAdvice(new Errors()).build();
    var created = create("gestor-local");
    assertTrue(created.get("success").asBoolean());
    id = created.at("/data/id").asText();
  }

  private JsonNode create(String token) throws Exception {
    return JSON.readTree(api.perform(post("/admin/requests")
        .header("Authorization", "Bearer " + token).contentType("application/json")
        .content("""
            {"destination":"loja-aurora","items":[
              {"product_id":"agua-500","requested":6},
              {"product_id":"cafe-250","requested":2}]}
            """)).andReturn().getResponse().getContentAsString());
  }

  private static String received(int water, int coffee) {
    return """
        {"items":[{"product_id":"agua-500","received":%d},
                  {"product_id":"cafe-250","received":%d}]}
        """.formatted(water, coffee);
  }

  private JsonNode confirm(String body, String token) throws Exception {
    return JSON.readTree(api.perform(post("/admin/requests/{id}/confirm", id)
        .header("Authorization", "Bearer " + token)
        .contentType("application/json").content(body))
        .andReturn().getResponse().getContentAsString());
  }

  private JsonNode confirm(int water, int coffee) throws Exception {
    return confirm(received(water, coffee), "operador-local");
  }

  private void stock(String location, String product, int expected) {
    assertEquals(expected, jdbc.queryForObject(
        "SELECT stock FROM inventory WHERE location_id=? AND product_id=?",
        Integer.class, location, product));
  }

  private void assertConfirmed(int water, int coffee) {
    stock("centro-horizonte", "agua-500", 100 - water);
    stock("loja-aurora", "agua-500", 4 + water);
    stock("centro-horizonte", "cafe-250", 50 - coffee);
    stock("loja-aurora", "cafe-250", 3 + coffee);
    assertEquals("CONFIRMED", repository.detail(id).get("status"));
    var items = repository.items(id);
    assertEquals(6, items.get(0).get("requested"));
    assertEquals(water, items.get(0).get("received"));
    assertEquals(6 - water, ((Number) items.get(0).get("missing")).intValue());
    assertEquals(2, items.get(1).get("requested"));
    assertEquals(coffee, items.get(1).get("received"));
    assertEquals(2 - coffee, ((Number) items.get(1).get("missing")).intValue());
    verify(documents, times(1)).enqueue(id);
    assertEquals(1, repository.document(id).get("attempts"));
  }

  @Test
  void h03PartialDeliveryPreservesRequestedAndFullShortageOfZeroItem() throws Exception {
    var result = confirm(4, 0);
    assertTrue(result.get("success").asBoolean());
    assertEquals(6, result.at("/data/items/0/requested").asInt());
    assertEquals(4, result.at("/data/items/0/received").asInt());
    assertEquals(2, result.at("/data/items/0/missing").asInt());
    assertEquals(2, result.at("/data/items/1/requested").asInt());
    assertEquals(0, result.at("/data/items/1/received").asInt());
    assertEquals(2, result.at("/data/items/1/missing").asInt());
    assertConfirmed(4, 0);
    var detail = JSON.readTree(api.perform(get("/admin/requests/{id}", id)
        .header("Authorization", "Bearer gestor-local"))
        .andReturn().getResponse().getContentAsString());
    assertEquals(result, detail);
  }

  @Test
  void h02IdenticalRetryInDifferentItemOrderReturnsSameResultWithoutSideEffects() throws Exception {
    var first = confirm(4, 0);
    var documentBefore = repository.document(id);
    var replay = confirm("""
        {"items":[{"product_id":"cafe-250","received":0},
                  {"product_id":"agua-500","received":4}]}
        """, "operador-local");
    assertTrue(first.get("success").asBoolean());
    assertEquals(first, replay);
    assertEquals(documentBefore, repository.document(id));
    assertConfirmed(4, 0);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 3, 5, 7})
  void h02DivergentRetryIsConflictAndPreservesAllConfirmedData(int amount) throws Exception {
    assertTrue(confirm(4, 0).get("success").asBoolean());
    var before = repository.detail(id);
    var result = confirm(amount, 0);
    assertFalse(result.get("success").asBoolean());
    assertEquals(409, result.get("status").asInt());
    assertEquals(before, repository.detail(id));
    assertConfirmed(4, 0);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"items\":[{\"product_id\":\"agua-500\",\"received\":-1},{\"product_id\":\"cafe-250\",\"received\":0}]}",
      "{\"items\":[{\"product_id\":\"agua-500\",\"received\":7},{\"product_id\":\"cafe-250\",\"received\":0}]}",
      "{\"items\":[{\"product_id\":\"agua-500\",\"received\":4},{\"product_id\":\"cafe-250\",\"received\":3}]}",
      "{\"items\":[{\"product_id\":\"agua-500\",\"received\":0},{\"product_id\":\"cafe-250\",\"received\":0}]}",
      "{\"items\":[{\"product_id\":\"agua-500\",\"received\":4}]}",
      "{\"items\":[{\"product_id\":\"agua-500\",\"received\":4},{\"product_id\":\"agua-500\",\"received\":1}]}",
      "{\"items\":[{\"product_id\":\"agua-500\",\"received\":4},{\"product_id\":\"unknown\",\"received\":1}]}",
      "{\"items\":[null,{\"product_id\":\"cafe-250\",\"received\":1}]}",
      "{\"items\":[{\"product_id\":\"agua-500\",\"received\":null},{\"product_id\":\"cafe-250\",\"received\":1}]}",
      "{\"items\":null}"
  })
  void h02H03InvalidDeliveryLeavesNoPartialMovement(String body) throws Exception {
    var inventoryBefore = repository.inventory();
    var requestBefore = repository.detail(id);
    var result = confirm(body, "operador-local");
    assertFalse(result.get("success").asBoolean());
    assertEquals(400, result.get("status").asInt());
    assertEquals(inventoryBefore, repository.inventory());
    assertEquals(requestBefore, repository.detail(id));
    verifyNoInteractions(documents);
    assertEquals("NOT_REQUESTED", repository.document(id).get("status"));
  }

  @Test
  void h02InsufficientStockOnSecondItemRejectsWholeDelivery() throws Exception {
    jdbc.update("UPDATE inventory SET stock=1 WHERE location_id='centro-horizonte' AND product_id='cafe-250'");
    var before = repository.inventory();
    var requestBefore = repository.detail(id);
    var result = confirm(4, 2);
    assertFalse(result.get("success").asBoolean());
    assertEquals(400, result.get("status").asInt());
    assertEquals("Saldo insuficiente no centro", result.get("message").asText());
    assertEquals(before, repository.inventory());
    assertEquals(requestBefore, repository.detail(id));
    verifyNoInteractions(documents);
  }

  @Test
  void h02DatabaseFailureAfterFirstWriteRollsBackStockAndRequest() {
    jdbc.execute("ALTER TABLE inventory ADD CONSTRAINT h02_fail CHECK"
        + " (location_id <> 'loja-aurora' OR product_id <> 'agua-500' OR stock <= 4)");
    var before = repository.inventory();
    var requestBefore = repository.detail(id);
    assertThrows(RuntimeException.class, () -> service.confirm(id,
        new Inputs.Confirm(List.of(new Inputs.Received("agua-500", 4),
            new Inputs.Received("cafe-250", 0))), "OPERATOR"));
    assertEquals(before, repository.inventory());
    assertEquals(requestBefore, repository.detail(id));
    verifyNoInteractions(documents);
  }

  @Test
  void h02AuthorizationStillEnforcedIncludingRetries() throws Exception {
    assertEquals(403, create("operador-local").get("status").asInt());
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM replenishment_requests", Integer.class));
    assertEquals(403, confirm(received(4, 0), "gestor-local").get("status").asInt());
    assertEquals("OPEN", repository.detail(id).get("status"));
    assertTrue(confirm(4, 0).get("success").asBoolean());
    assertEquals(403, confirm(received(4, 0), "gestor-local").get("status").asInt());
    assertConfirmed(4, 0);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void h02ConcurrentIdenticalCallsMoveStockAndProcessDocumentOnce(int iteration) throws Exception {
    var results = concurrent(4, 4);
    assertTrue(results.get(0).get("success").asBoolean());
    assertEquals(results.get(0), results.get(1));
    assertConfirmed(4, 0);
  }

  @Test
  void h02ConcurrentDivergentCallsConfirmOnlyOneAndRejectOther() throws Exception {
    var results = concurrent(4, 3);
    assertNotEquals(results.get(0).get("success").asBoolean(), results.get(1).get("success").asBoolean());
    var success = results.stream().filter(r -> r.get("success").asBoolean()).findFirst().orElseThrow();
    var conflict = results.stream().filter(r -> !r.get("success").asBoolean()).findFirst().orElseThrow();
    assertEquals(409, conflict.get("status").asInt());
    assertConfirmed(success.at("/data/items/0/received").asInt(), 0);
  }

  private List<JsonNode> concurrent(int firstAmount, int secondAmount) throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> { ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS)); return confirm(firstAmount, 0); });
      var second = pool.submit(() -> { ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS)); return confirm(secondAmount, 0); });
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
    }
  }
}
