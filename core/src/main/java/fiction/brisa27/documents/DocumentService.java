package fiction.brisa27.documents;

import fiction.brisa27.common.BusinessException;
import fiction.brisa27.replenishment.RequestRepository;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DocumentService {
  private final RequestRepository repository;
  private final DocumentQueue queue;
  private final DocumentGateway gateway;

  public DocumentService(RequestRepository repository, DocumentQueue queue, DocumentGateway gateway) {
    this.repository = repository; this.queue = queue; this.gateway = gateway;
  }

  public void enqueue(String id) { queue.enqueue(id); }
  public void reprocess(String id) { queue.reprocess(id); }

  public byte[] xml(String id) {
    var document = repository.document(id);
    if (!"AUTHORIZED_SIMULATION".equals(document.get("status")) || document.get("object_key") == null)
      throw new BusinessException(409, "XML ainda não disponível");
    try { return gateway.download((String) document.get("object_key")); }
    catch (Exception e) { throw new BusinessException(502, "Armazenamento documental indisponível"); }
  }

  public Object mode(String mode) {
    if (mode == null || !Set.of("sucesso", "rejeicao", "indisponivel", "resposta-perdida").contains(mode))
      throw new BusinessException(400, "Modo desconhecido");
    return gateway.mode(mode);
  }
}
