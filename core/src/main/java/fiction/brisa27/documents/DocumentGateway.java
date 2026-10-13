package fiction.brisa27.documents;

import java.util.Map;

public interface DocumentGateway {
  Map<String, Object> lookup(String key);
  Map<String, Object> authorize(Map<String, Object> payload);
  byte[] xml(String key);
  void store(String objectKey, byte[] xml);
  byte[] download(String objectKey);
  Object mode(String mode);
}
