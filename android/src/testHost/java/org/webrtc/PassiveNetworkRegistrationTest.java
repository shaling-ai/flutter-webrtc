package org.webrtc;

public final class PassiveNetworkRegistrationTest {
  private static void check(boolean value) { if (!value) throw new AssertionError(); }
  public static void main(String[] args) {
    int[] counts = {0, 0};
    PassiveNetworkRegistration normal = new PassiveNetworkRegistration(
        () -> counts[0]++, () -> counts[1]++);
    check(normal.acquire() && normal.acquire() && counts[0] == 1);
    normal.close(); normal.close();
    check(counts[1] == 1 && !normal.isRegistered() && !normal.acquire());

    for (RuntimeException rejection : new RuntimeException[] {
        new SecurityException(), new android.net.ConnectivityManager.TooManyRequestsException()}) {
      int[] rejected = {0, 0};
      PassiveNetworkRegistration fallback = new PassiveNetworkRegistration(
          () -> { rejected[0]++; throw rejection; }, () -> rejected[1]++);
      check(!fallback.acquire() && !fallback.acquire());
      fallback.close(); fallback.close();
      check(rejected[0] == 1 && rejected[1] == 0);
    }
    IllegalStateException unrelated = new IllegalStateException("not a quota rejection");
    try {
      new PassiveNetworkRegistration(() -> { throw unrelated; }, () -> {}).acquire();
      throw new AssertionError("Unrelated registration errors must propagate");
    } catch (IllegalStateException actual) { check(actual == unrelated); }

    int[] release = {0};
    PassiveNetworkRegistration failedRelease = new PassiveNetworkRegistration(() -> {}, () -> {
      if (release[0]++ == 0) throw unrelated;
    });
    check(failedRelease.acquire());
    try { failedRelease.close(); throw new AssertionError(); }
    catch (IllegalStateException actual) { check(actual == unrelated); }
    check(failedRelease.isRegistered());
    failedRelease.close(); check(!failedRelease.isRegistered() && release[0] == 2);

    check(PassiveNetworkRegistration.useOn("HUAWEI", 29));
    check(PassiveNetworkRegistration.useOn("Honor", 28));
    check(!PassiveNetworkRegistration.useOn("HUAWEI", 30));
    check(!PassiveNetworkRegistration.useOn("HUAWEI", 22));
    check(!PassiveNetworkRegistration.useOn("samsung", 29));
    check(!PassiveNetworkRegistration.useOn(null, 29));
    System.out.println("PASS: successful ownership, idempotent close, quota fallback, security fallback, unrelated error, failed release ownership, OEM/API selection");
  }
}
