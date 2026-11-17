package org.webrtc;

/** Owns only a successfully registered callback; rejected requests have nothing to release. */
final class PassiveNetworkRegistration {
  private final Runnable register;
  private final Runnable unregister;
  private boolean attempted;
  private boolean registered;
  private boolean closed;

  PassiveNetworkRegistration(Runnable register, Runnable unregister) {
    this.register = register;
    this.unregister = unregister;
  }

  static boolean useOn(String manufacturer, int sdk) {
    return sdk >= 23 && sdk <= 29 && ("HUAWEI".equalsIgnoreCase(manufacturer)
        || "HONOR".equalsIgnoreCase(manufacturer));
  }

  synchronized boolean acquire() {
    if (attempted || closed) return registered;
    attempted = true;
    try {
      register.run();
      registered = true;
    } catch (RuntimeException error) {
      // TooManyRequestsException is hidden on some Android SDKs. Match its exact
      // platform class, not an error message or every RuntimeException.
      if (!(error instanceof SecurityException)
          && !error.getClass().getName().equals(
              "android.net.ConnectivityManager$TooManyRequestsException")) throw error;
    }
    return registered;
  }

  synchronized boolean isRegistered() { return registered; }

  synchronized void close() {
    if (closed) return;
    if (registered) {
      unregister.run();
      registered = false;
    }
    closed = true;
  }
}
