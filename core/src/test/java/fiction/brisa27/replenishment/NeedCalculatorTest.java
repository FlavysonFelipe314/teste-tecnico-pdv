package fiction.brisa27.replenishment;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NeedCalculatorTest {
  @Test
  void calculatesShortage() {
    assertEquals(6, NeedCalculator.calculate(4, 10));
  }

  @Test
  void zeroAtTarget() {
    assertEquals(0, NeedCalculator.calculate(10, 10));
  }

  @Test
  void h01ZeroAboveTarget() {
    assertEquals(0, NeedCalculator.calculate(7, 5));
  }
}
