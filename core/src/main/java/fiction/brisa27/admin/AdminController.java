package fiction.brisa27.admin;

import fiction.brisa27.common.*;
import fiction.brisa27.documents.DocumentService;
import fiction.brisa27.replenishment.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
public class AdminController {
  private final LabIdentity identity;
  private final RequestRepository repository;
  private final RequestService service;
  private final DocumentService documents;

  public AdminController(LabIdentity i, RequestRepository r, RequestService s, DocumentService d) {
    identity = i;
    repository = r;
    service = s;
    documents = d;
  }

  @GetMapping("/session")
  public ApiResponse session(HttpServletRequest r) {
    return ApiResponse.ok(Map.of("role", identity.role(r)));
  }

  @GetMapping("/inventory")
  public ApiResponse inventory(HttpServletRequest r) {
    identity.role(r);
    return ApiResponse.ok(repository.inventory());
  }

  @GetMapping("/needs")
  public ApiResponse needs(@RequestParam String store, HttpServletRequest r) {
    identity.role(r);
    return ApiResponse.ok(service.needs(store));
  }

  @GetMapping("/requests")
  public ApiResponse requests(HttpServletRequest r) {
    identity.role(r);
    return ApiResponse.ok(repository.requests());
  }

  @GetMapping("/requests/{id}")
  public ApiResponse detail(@PathVariable String id, HttpServletRequest r) {
    identity.role(r);
    return ApiResponse.ok(repository.detail(id));
  }

  @PostMapping("/requests")
  public ApiResponse create(@RequestBody Inputs.Create input, HttpServletRequest r) {
    return ApiResponse.ok(service.create(input, identity.require(r, "MANAGER")));
  }

  @PostMapping("/requests/{id}/confirm")
  public ApiResponse confirm(
      @PathVariable String id, @RequestBody Inputs.Confirm input, HttpServletRequest r) {
    return ApiResponse.ok(service.confirm(id, input, identity.require(r, "OPERATOR")));
  }

  @GetMapping("/requests/{id}/document")
  public ApiResponse document(@PathVariable String id, HttpServletRequest r) {
    identity.role(r);
    return ApiResponse.ok(repository.document(id));
  }

  @GetMapping("/requests/{id}/document/xml")
  public ResponseEntity<byte[]> xml(@PathVariable String id, HttpServletRequest r) {
    identity.role(r);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(documents.xml(id));
  }

  @PostMapping("/requests/{id}/document/reprocess")
  public ApiResponse reprocess(@PathVariable String id, HttpServletRequest r) {
    identity.require(r, "MANAGER");
    documents.reprocess(id);
    return ApiResponse.ok(repository.document(id));
  }

  @GetMapping("/lab/cache-metrics")
  public ApiResponse cacheMetrics(HttpServletRequest r) {
    identity.require(r, "MANAGER");
    return ApiResponse.ok(service.cacheMetrics());
  }

  @PutMapping("/lab/cache-enabled")
  public ApiResponse cacheEnabled(@RequestBody Map<String, Boolean> input, HttpServletRequest r) {
    identity.require(r, "MANAGER");
    if (input.get("enabled") == null) throw new BusinessException(400, "Informar enabled");
    service.cacheEnabled(input.get("enabled"));
    return ApiResponse.ok(service.cacheMetrics());
  }

  @PutMapping("/lab/fiscal-mode")
  public ApiResponse mode(@RequestBody Map<String, String> input, HttpServletRequest r) {
    identity.require(r, "MANAGER");
    return ApiResponse.ok(documents.mode(input.get("mode")));
  }
}
