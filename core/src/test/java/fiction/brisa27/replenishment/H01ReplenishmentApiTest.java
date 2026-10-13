package fiction.brisa27.replenishment;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import fiction.brisa27.admin.AdminController;
import fiction.brisa27.common.Errors;
import fiction.brisa27.common.LabIdentity;
import fiction.brisa27.documents.DocumentService;
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

/** H01: real controller, service, transactions and SQL; only documents are mocked. */
@SpringJUnitConfig(ReplenishmentTestConfig.class)
class H01ReplenishmentApiTest {
  @Autowired DataSource source;
  @Autowired JdbcTemplate jdbc;
  @Autowired RequestRepository repository;
  @Autowired RequestService service;
  @Autowired DocumentService documents;
  private MockMvc api;

  @BeforeEach
  void cleanBase() {
    ReplenishmentTestConfig.cleanBase(source);
    api = MockMvcBuilders.standaloneSetup(
        new AdminController(new LabIdentity(), repository, service, documents))
        .setControllerAdvice(new Errors()).build();
  }

  @Test
  void needsReflectShortageAndExcludeStockAtOrAboveTarget() throws Exception {
    api.perform(get("/admin/needs").param("store", "loja-aurora")
            .header("Authorization", "Bearer gestor-local"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data", hasSize(2)))
        .andExpect(jsonPath("$.data[?(@.product_id == 'agua-500')].need", contains(6)))
        .andExpect(jsonPath("$.data[?(@.product_id == 'arroz-1000')]", empty()));
    api.perform(get("/admin/needs").param("store", "loja-lagoa")
            .header("Authorization", "Bearer gestor-local"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data", hasSize(2)))
        .andExpect(jsonPath("$.data[?(@.product_id == 'cafe-250')]", empty()));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "loja-lagoa,cafe-250,1,Produto sem necessidade de reposição",
      "loja-aurora,arroz-1000,1,Produto sem necessidade de reposição",
      "loja-aurora,agua-500,7,Quantidade acima da necessidade"
  })
  void directApiRejectsInvalidItemWithoutPartialRequestOrStockChange(String scenario)
      throws Exception {
    var values = scenario.split(",");
    var inventoryBefore = repository.inventory();
    // A valid item comes first so the assertions also catch premature partial writes.
    String validProduct = values[0].equals("loja-lagoa") ? "agua-500" : "cafe-250";
    String body = """
        {"destination":"%s","items":[
          {"product_id":"%s","requested":1},
          {"product_id":"%s","requested":%s}]}
        """.formatted(values[0], validProduct, values[1], values[2]);
    api.perform(post("/admin/requests").header("Authorization", "Bearer gestor-local")
            .contentType("application/json").content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").value(values[3]));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM replenishment_requests", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM request_items", Integer.class));
    assertEquals(inventoryBefore, repository.inventory());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 6})
  void acceptsPositiveQuantityUpToActualShortageWithoutMovingStock(int quantity) throws Exception {
    var inventoryBefore = repository.inventory();
    api.perform(post("/admin/requests").header("Authorization", "Bearer gestor-local")
            .contentType("application/json").content("""
                {"destination":"loja-aurora","items":[
                  {"product_id":"agua-500","requested":%d}]}
                """.formatted(quantity)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.status").value("OPEN"))
        .andExpect(jsonPath("$.data.items[0].requested").value(quantity));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM replenishment_requests", Integer.class));
    assertEquals(quantity, jdbc.queryForObject("SELECT requested FROM request_items", Integer.class));
    assertEquals(inventoryBefore, repository.inventory());
  }
}
