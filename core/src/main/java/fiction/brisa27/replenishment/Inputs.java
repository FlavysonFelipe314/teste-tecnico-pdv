package fiction.brisa27.replenishment;

import java.util.List;

public final class Inputs {
  public record Item(String product_id, Integer requested) {}

  public record Create(String destination, List<Item> items) {}

  public record Received(String product_id, Integer received) {}

  public record Confirm(List<Received> items) {}
}
