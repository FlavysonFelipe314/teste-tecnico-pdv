package fiction.brisa27.documents;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

@Service
public class DocumentProcessor {
  private final DocumentQueue queue;
  private final DocumentGateway gateway;

  public DocumentProcessor(DocumentQueue queue, DocumentGateway gateway) {
    this.queue = queue; this.gateway = gateway;
  }

  public boolean processOne() {
    var task = queue.claim();
    if (task == null) return false;
    String stage = "consulta ao provedor";
    try {
      // Reconcile first: a lost response or a restart never changes the provider key.
      var response = gateway.lookup(task.key());
      if (response == null) {
        stage = "emissão no provedor";
        response = gateway.authorize(task.payload());
      }
      if (response == null || !Objects.equals(task.key(), response.get("document_key")))
        throw new IllegalStateException("Resposta documental inconsistente");
      if ("REJECTED_SIMULATION".equals(response.get("status"))) {
        queue.rejected(task, Objects.toString(response.get("reason"), "Rejeitado pelo provedor"));
      } else if ("AUTHORIZED_SIMULATION".equals(response.get("status"))
          && response.get("protocol") instanceof String protocol && !protocol.isBlank()) {
        if (queue.authorization(task, protocol)) {
          String objectKey = task.key() + ".xml";
          stage = "consulta do XML no provedor";
          byte[] xml = gateway.xml(task.key());
          if (xml == null || xml.length == 0) throw new IllegalStateException("XML vazio");
          stage = "armazenamento do XML";
          gateway.store(objectKey, xml);
          queue.authorized(task, protocol, objectKey);
        }
      } else throw new IllegalStateException("Estado documental desconhecido");
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().value() == 429) queue.retry(task, "Provedor limitou as requisições");
      else queue.permanentFailure(task, "Provedor recusou a integração: HTTP " + e.getStatusCode().value());
    } catch (Exception e) {
      queue.retry(task, "Falha temporária em " + stage + ": " + e.getClass().getSimpleName());
    }
    return true;
  }
}
