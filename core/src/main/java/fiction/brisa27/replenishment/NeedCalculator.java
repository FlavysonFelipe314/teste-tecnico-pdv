package fiction.brisa27.replenishment;

public final class NeedCalculator {
  private NeedCalculator() {}

  public static int calculate(int stock, int target) {
    return Math.max(0, target - stock);
  }
}
