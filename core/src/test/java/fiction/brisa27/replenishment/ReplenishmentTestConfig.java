package fiction.brisa27.replenishment;

import static org.mockito.Mockito.mock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import fiction.brisa27.documents.DocumentService;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
class ReplenishmentTestConfig {
  @Bean(destroyMethod = "")
  DataSource dataSource() {
    String mysqlUrl = System.getenv("REPLENISHMENT_TEST_MYSQL_URL");
    if (mysqlUrl != null) {
      // Test fixture drops tables; never accept the application's operational database.
      if (!mysqlUrl.matches("jdbc:mysql://[^/]+/rota_clara_test(?:\\?.*)?"))
        throw new IllegalArgumentException("Use um banco isolado chamado rota_clara_test");
      return new DriverManagerDataSource(mysqlUrl,
          System.getenv("REPLENISHMENT_TEST_MYSQL_USER"),
          System.getenv("REPLENISHMENT_TEST_MYSQL_PASSWORD"));
    }
    return new EmbeddedDatabaseBuilder().setName("replenishment;DATABASE_TO_LOWER=TRUE")
        .setType(EmbeddedDatabaseType.H2).build();
  }

  static void cleanBase(DataSource source) {
    var jdbc = new JdbcTemplate(source);
    for (String table : new String[] {"documents", "request_items", "replenishment_requests",
        "inventory_versions", "inventory", "products", "locations"}) {
      jdbc.execute("DROP TABLE IF EXISTS " + table);
    }
    new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__initial.sql"),
        new ClassPathResource("db/migration/V2__durable_documents_and_inventory_versions.sql"))
        .execute(source);
  }

  @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }

  @Bean DataSourceTransactionManager transactionManager(DataSource source) {
    return new DataSourceTransactionManager(source);
  }

  @Bean RequestRepository repository(JdbcTemplate jdbc) { return new RequestRepository(jdbc); }
  @Bean DocumentService documents() { return mock(DocumentService.class); }
  @Bean NeedsService needs(RequestRepository repository) {
    return new NeedsService(repository, mock(StringRedisTemplate.class), new ObjectMapper(), 60, false, "rota-clara:needs:v1");
  }
  @Bean RequestService service(RequestRepository repository, DocumentService documents, NeedsService needs) {
    return new RequestService(repository, documents, needs);
  }
}
