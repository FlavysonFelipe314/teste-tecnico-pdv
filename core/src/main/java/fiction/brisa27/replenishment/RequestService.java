package fiction.brisa27.replenishment;

import fiction.brisa27.common.BusinessException;
import fiction.brisa27.documents.DocumentService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RequestService {
  private final RequestRepository repository;
  private final DocumentService documents;
  private final NeedsService needs;

  public RequestService(RequestRepository r, DocumentService d, NeedsService n) {
    repository = r;
    documents = d;
    needs = n;
  }

  public List<Map<String, Object>> needs(String store) { return needs.needs(store); }

  public Map<String, Object> cacheMetrics() { return needs.metrics(); }
  public void cacheEnabled(boolean enabled) { needs.setEnabled(enabled); }

  @Transactional
  public Map<String, Object> create(Inputs.Create input, String actor) {
    if (input == null || input.items() == null || input.items().isEmpty())
      throw new BusinessException(400, "Selecionar pelo menos um item");
    var available = needs.fresh(input.destination());
    var seen = new HashSet<String>();
    for (var item : input.items()) {
      if (item == null || item.product_id() == null || item.requested() == null || item.requested() <= 0 || !seen.add(item.product_id()))
        throw new BusinessException(400, "Quantidade inválida ou produto repetido");
      var row =
          available.stream()
              .filter(r -> Objects.equals(r.get("product_id"), item.product_id()))
              .findFirst()
              .orElseThrow(
                  () -> new BusinessException(400, "Produto sem necessidade de reposição"));
      if (item.requested() > ((Number) row.get("need")).intValue())
        throw new BusinessException(400, "Quantidade acima da necessidade");
    }
    String id = UUID.randomUUID().toString();
    repository.jdbc().update(
        "INSERT INTO replenishment_requests(id,destination,origin,status,created_by) VALUES"
            + " (?,?,?,'OPEN',?)",
        id,
        input.destination(),
        "centro-horizonte",
        actor);
    for (var item : input.items())
      repository.jdbc().update(
          "INSERT INTO request_items(request_id,product_id,requested) VALUES (?,?,?)",
          id,
          item.product_id(),
          item.requested());
    return repository.detail(id);
  }

  @Transactional
  public Map<String, Object> confirm(String id, Inputs.Confirm input, String actor) {
    // Serialize confirmations of this request before taking any consistent-read snapshot.
    var request = repository.lockRequest(id);
    var items = repository.items(id);
    if (input == null || input.items() == null || input.items().size() != items.size())
      throw new BusinessException(400, "Informar todos os itens");
    var received = new TreeMap<String, Integer>();
    for (var item : input.items()) {
      if (item == null
          || item.product_id() == null
          || item.received() == null
          || item.received() < 0
          || received.put(item.product_id(), item.received()) != null)
        throw new BusinessException(400, "Recebimento inválido");
    }
    for (var item : items) {
      if (!received.containsKey((String) item.get("product_id")))
        throw new BusinessException(400, "Informar todos os itens do pedido");
    }
    if ("CONFIRMED".equals(request.get("status"))) {
      boolean identical = items.stream().allMatch(item ->
          Objects.equals(received.get((String) item.get("product_id")), item.get("received")));
      if (!identical)
        throw new BusinessException(409, "Pedido já confirmado com recebimento diferente");
      return repository.detail(id);
    }
    if (!"OPEN".equals(request.get("status")))
      throw new BusinessException(409, "Pedido não está aberto para confirmação");
    if (received.values().stream().noneMatch(amount -> amount > 0))
      throw new BusinessException(400, "Entrega totalmente zerada");
    for (var item : items) {
      String product = (String) item.get("product_id");
      Integer amount = received.get(product);
      if (amount == null || amount > ((Number) item.get("requested")).intValue())
        throw new BusinessException(400, "Quantidade acima da solicitação");
      int stock =
          repository.jdbc().queryForObject(
              "SELECT stock FROM inventory WHERE location_id=? AND product_id=? FOR UPDATE",
              Integer.class,
              request.get("origin"),
              product);
      if (stock < amount) throw new BusinessException(400, "Saldo insuficiente no centro");
    }
    for (var item : items) {
      String product = (String) item.get("product_id");
      int amount = received.get(product);
      repository.jdbc().update(
          "UPDATE inventory SET stock=stock-? WHERE location_id=? AND product_id=?",
          amount,
          request.get("origin"),
          product);
      repository.jdbc().update(
          "UPDATE inventory SET stock=stock+? WHERE location_id=? AND product_id=?",
          amount,
          request.get("destination"),
          product);
      repository.jdbc().update(
          "UPDATE request_items SET received=? WHERE request_id=? AND product_id=?",
          amount,
          id,
          product);
    }
    repository.jdbc().update(
        "UPDATE replenishment_requests SET"
            + " status='CONFIRMED',confirmed_by=?,confirmed_at=CURRENT_TIMESTAMP WHERE id=?",
        actor,
        id);
    // Version changes commit atomically with stock. Redis is never required for a write.
    for (String location : new TreeSet<>(List.of((String) request.get("origin"),
        (String) request.get("destination")))) {
      int updated = repository.jdbc().update(
          "UPDATE inventory_versions SET version=version+1 WHERE location_id=?", location);
      if (updated != 1) throw new IllegalStateException("Versão de estoque ausente");
    }
    documents.enqueue(id);
    return repository.detail(id);
  }
}
