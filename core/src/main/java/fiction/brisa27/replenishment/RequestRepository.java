package fiction.brisa27.replenishment;

import fiction.brisa27.common.BusinessException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RequestRepository {
  private final JdbcTemplate jdbc;

  public RequestRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public JdbcTemplate jdbc() { return jdbc; }

  public List<Map<String, Object>> inventory() {
    return jdbc.queryForList(
        "SELECT i.*,p.name product_name,l.name location_name FROM inventory i JOIN products p ON"
            + " p.id=i.product_id JOIN locations l ON l.id=i.location_id ORDER BY"
            + " location_id,product_id");
  }

  public Map<String, Object> detail(String id) {
    var rows = jdbc.queryForList("SELECT * FROM replenishment_requests WHERE id=?", id);
    if (rows.isEmpty()) throw new BusinessException(404, "Solicitação não encontrada");
    var result = new LinkedHashMap<String, Object>(rows.getFirst());
    result.put("items", items(id));
    return result;
  }

  /** Must be called inside a transaction, before reading items or checking status. */
  public Map<String, Object> lockRequest(String id) {
    var rows = jdbc.queryForList("SELECT * FROM replenishment_requests WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw new BusinessException(404, "Solicitação não encontrada");
    return rows.getFirst();
  }

  public List<Map<String, Object>> items(String id) {
    return jdbc.queryForList(
        "SELECT ri.*,p.name product_name,requested-COALESCE(received,0) missing FROM request_items"
            + " ri JOIN products p ON p.id=ri.product_id WHERE request_id=? ORDER BY product_id",
        id);
  }

  public List<Map<String, Object>> requests() {
    return jdbc.queryForList(
        "SELECT r.*,d.status document_status FROM replenishment_requests r LEFT JOIN documents d ON"
            + " d.request_id=r.id ORDER BY r.created_at DESC,r.id");
  }

  public Map<String, Object> document(String id) {
    detail(id);
    var rows = jdbc.queryForList("SELECT * FROM documents WHERE request_id=?", id);
    return rows.isEmpty() ? Map.of("status", "NOT_REQUESTED") : rows.getFirst();
  }
}
