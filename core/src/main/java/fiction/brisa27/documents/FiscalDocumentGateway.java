package fiction.brisa27.documents;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
public class FiscalDocumentGateway implements DocumentGateway {
  private final RestClient fiscal;
  private final S3Client s3;
  private final String bucket;

  public FiscalDocumentGateway(@Value("${lab.fiscal-url}") String url,
      @Value("${lab.s3-endpoint}") String endpoint, @Value("${lab.s3-user}") String user,
      @Value("${lab.s3-password}") String password, @Value("${lab.s3-bucket}") String bucket) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(3));
    fiscal = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    this.bucket = bucket;
    s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
        .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(user, password)))
        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(5))
            .apiCallAttemptTimeout(Duration.ofSeconds(3))).build();
  }

  @Override @SuppressWarnings("unchecked")
  public Map<String, Object> lookup(String key) {
    try { return fiscal.get().uri("/documents/{key}", key).retrieve().body(Map.class); }
    catch (HttpClientErrorException.NotFound e) { return null; }
  }

  @Override @SuppressWarnings("unchecked")
  public Map<String, Object> authorize(Map<String, Object> payload) {
    return fiscal.post().uri("/documents").body(payload).retrieve().body(Map.class);
  }

  @Override public byte[] xml(String key) {
    return fiscal.get().uri("/documents/{key}/xml", key).retrieve().body(byte[].class);
  }

  @Override public void store(String objectKey, byte[] xml) {
    try { s3.headBucket(b -> b.bucket(bucket)); }
    catch (S3Exception e) {
      if (e.statusCode() != 404) throw e;
      try { s3.createBucket(b -> b.bucket(bucket)); }
      catch (S3Exception race) {
        if (!"BucketAlreadyOwnedByYou".equals(race.awsErrorDetails().errorCode())) throw race;
      }
    }
    s3.putObject(b -> b.bucket(bucket).key(objectKey).contentType("application/xml"), RequestBody.fromBytes(xml));
  }

  @Override public byte[] download(String objectKey) {
    return s3.getObjectAsBytes(b -> b.bucket(bucket).key(objectKey)).asByteArray();
  }

  @Override public Object mode(String mode) {
    return fiscal.put().uri("/admin/mode").body(Map.of("mode", mode)).retrieve().body(Map.class);
  }

  @jakarta.annotation.PreDestroy public void close() { s3.close(); }
}
