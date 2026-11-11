package android.net;

// Models the exact hidden platform exception name without loading Android/JNI.
public final class ConnectivityManager {
  public static final class TooManyRequestsException extends RuntimeException {}
}
