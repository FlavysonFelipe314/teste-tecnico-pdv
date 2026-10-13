package fiction.brisa27.documents;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "lab.documents.worker-enabled", havingValue = "true", matchIfMissing = true)
public class DocumentWorker {
  private final DocumentProcessor processor;
  public DocumentWorker(DocumentProcessor processor) { this.processor = processor; }

  @Scheduled(fixedDelayString = "${lab.documents.poll-ms:1000}")
  public void poll() {
    try { for (int i = 0; i < 5 && processor.processOne(); i++) { } }
    catch (Exception e) { LoggerFactory.getLogger(getClass()).warn("Fila documental indisponível", e); }
  }
}
