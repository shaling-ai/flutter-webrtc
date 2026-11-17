package org.webrtc;

import static org.junit.Assert.*;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkInfo;
import org.robolectric.util.ReflectionHelpers;

/** Android boundary failures with the real pinned WebRTC delegate and NetworkMonitor. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE, shadows = HuaweiNetworkChangeDetectorTest.RejectingConnectivity.class)
public class HuaweiNetworkChangeDetectorTest {
  @Implements(ConnectivityManager.class)
  public static class RejectingConnectivity extends ShadowConnectivityManager {
    RuntimeException reject;
    RuntimeException releaseFailure;
    int registrations;
    int releases;
    int forcedRequests;
    @Implementation protected void registerNetworkCallback(
        NetworkRequest request, ConnectivityManager.NetworkCallback callback) {
      registrations++;
      if (reject != null) throw reject;
      super.registerNetworkCallback(request, callback);
    }
    @Implementation protected void requestNetwork(
        NetworkRequest request, ConnectivityManager.NetworkCallback callback) {
      forcedRequests++;
      super.requestNetwork(request, callback);
    }
    @Implementation protected void unregisterNetworkCallback(ConnectivityManager.NetworkCallback callback) {
      releases++;
      if (releaseFailure != null) throw releaseFailure;
      super.unregisterNetworkCallback(callback);
    }
  }

  private static class RecordingContext extends ContextWrapper {
    int receiverAdds;
    int receiverRemoves;
    RuntimeException unregisterFailure;
    RecordingContext(Context context) { super(context); }
    @Override public Context getApplicationContext() { return this; }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
      receiverAdds++;
      return super.registerReceiver(receiver, filter);
    }
    @Override public void unregisterReceiver(BroadcastReceiver receiver) {
      receiverRemoves++;
      if (unregisterFailure != null) throw unregisterFailure;
      super.unregisterReceiver(receiver);
    }
  }
  private static class Observer extends NetworkChangeDetector.Observer {
    final List<Long> connected = new ArrayList<>();
    final List<Long> disconnected = new ArrayList<>();
    final List<NetworkChangeDetector.ConnectionType> types = new ArrayList<>();
    @Override public void onConnectionTypeChanged(NetworkChangeDetector.ConnectionType type) { types.add(type); }
    @Override public void onNetworkConnect(NetworkChangeDetector.NetworkInformation info) { connected.add(info.handle); }
    @Override public void onNetworkDisconnect(long handle) { disconnected.add(handle); }
    @Override public void onNetworkPreference(List<NetworkChangeDetector.ConnectionType> types, int preference) {}
    @Override public String getFieldTrialsString() { return "getAllNetworksFromCache:true"; }
  }
  private RecordingContext context;
  private RejectingConnectivity connectivity;
  private Observer observer;
  private Network network;

  @Before public void setup() {
    context = new RecordingContext(RuntimeEnvironment.getApplication());
    connectivity = Shadow.extract(context.getSystemService(Context.CONNECTIVITY_SERVICE));
    connectivity.clearAllNetworks();
    observer = new Observer();
    network = ShadowNetwork.newInstance(123);
    NetworkInfo wifi = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
        ConnectivityManager.TYPE_WIFI, 0, true, true);
    connectivity.setActiveNetworkInfo(wifi);
    connectivity.addNetwork(network, wifi);
    LinkProperties properties = new LinkProperties();
    properties.setInterfaceName("wlan0");
    connectivity.setLinkProperties(network, properties);
  }

  private RuntimeException quota() throws Exception {
    return (RuntimeException) Class.forName("android.net.ConnectivityManager$TooManyRequestsException")
        .getDeclaredConstructor().newInstance();
  }

  @Test public void normalRegistrationNeverForcesCellularAndClosesOnce() {
    HuaweiNetworkChangeDetector detector = new HuaweiNetworkChangeDetector(observer, context);
    assertTrue(detector.supportNetworkCallback());
    assertEquals(1, connectivity.registrations);
    assertEquals(0, connectivity.forcedRequests);
    assertFalse(detector.getActiveNetworkList().isEmpty());
    ConnectivityManager.NetworkCallback callback = connectivity.getNetworkCallbacks().iterator().next();
    callback.onAvailable(network);
    assertTrue(observer.connected.contains(network.getNetworkHandle()));
    detector.destroy();
    detector.destroy();
    assertEquals(1, connectivity.releases);
    assertEquals(1, context.receiverRemoves);
    assertTrue(connectivity.getNetworkCallbacks().isEmpty());
    int events = observer.connected.size() + observer.disconnected.size();
    callback.onAvailable(network);
    callback.onLost(network);
    detector.onReceive(context, new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    assertEquals(events, observer.connected.size() + observer.disconnected.size());
  }

  @Test public void quotaRejectionKeepsBroadcastAndEnumeratesWithoutCallbackCache() throws Exception {
    connectivity.reject = quota();
    HuaweiNetworkChangeDetector detector = new HuaweiNetworkChangeDetector(observer, context);
    assertFalse(detector.supportNetworkCallback());
    assertFalse(detector.getActiveNetworkList().isEmpty());
    detector.onReceive(context, new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    assertTrue(observer.connected.contains(network.getNetworkHandle()));
    connectivity.clearAllNetworks();
    connectivity.setActiveNetworkInfo(null);
    detector.onReceive(context, new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    assertTrue(observer.disconnected.contains(network.getNetworkHandle()));
    assertTrue(observer.types.contains(NetworkChangeDetector.ConnectionType.CONNECTION_NONE));
    detector.destroy();
    assertEquals(1, connectivity.registrations);
    assertEquals(0, connectivity.releases);
    assertEquals(0, connectivity.forcedRequests);
    assertEquals(1, context.receiverRemoves);
  }

  @Test public void permissionRejectionUsesSameFallback() {
    connectivity.reject = new SecurityException();
    HuaweiNetworkChangeDetector detector = new HuaweiNetworkChangeDetector(observer, context);
    assertFalse(detector.supportNetworkCallback());
    detector.destroy();
    assertEquals(0, connectivity.releases);
    assertEquals(1, context.receiverRemoves);
  }

  @Test public void unrelatedFailureRollsBackReceiverAndPreservesCause() {
    connectivity.reject = new IllegalStateException("unexpected registration failure");
    try {
      new HuaweiNetworkChangeDetector(observer, context);
      fail("unrelated failure must remain visible");
    } catch (IllegalStateException actual) {
      assertSame(connectivity.reject, actual);
    }
    assertEquals(1, context.receiverRemoves);
    assertEquals(0, connectivity.releases);
  }

  @Test public void rollbackFailureDoesNotReplaceRegistrationFailure() {
    connectivity.reject = new IllegalStateException("registration failure");
    context.unregisterFailure = new IllegalArgumentException("rollback failure");
    try {
      new HuaweiNetworkChangeDetector(observer, context);
      fail();
    } catch (RuntimeException actual) {
      assertSame(connectivity.reject, actual);
      assertEquals(1, actual.getSuppressed().length);
      assertSame(context.unregisterFailure, actual.getSuppressed()[0]);
    }
  }

  @Test public void failedCallbackReleaseStillUnregistersReceiverAndAllowsRetry() {
    HuaweiNetworkChangeDetector detector = new HuaweiNetworkChangeDetector(observer, context);
    ConnectivityManager.NetworkCallback callback = connectivity.getNetworkCallbacks().iterator().next();
    connectivity.releaseFailure = new IllegalStateException("release failed");
    try { detector.destroy(); fail(); }
    catch (IllegalStateException actual) { assertSame(connectivity.releaseFailure, actual); }
    assertEquals(1, context.receiverRemoves);
    assertFalse(detector.supportNetworkCallback());
    callback.onAvailable(network);
    assertTrue(observer.connected.isEmpty());
    connectivity.releaseFailure = null;
    detector.destroy();
    assertTrue(connectivity.getNetworkCallbacks().isEmpty());
    assertEquals(1, context.receiverRemoves);
  }

  @Test public void initialNetworkDisappearanceIsReportedInFallback() throws Exception {
    connectivity.reject = quota();
    HuaweiNetworkChangeDetector detector = new HuaweiNetworkChangeDetector(observer, context);
    assertFalse(detector.getActiveNetworkList().isEmpty());
    connectivity.clearAllNetworks();
    connectivity.setActiveNetworkInfo(null);
    detector.onReceive(context, new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
    assertTrue(observer.disconnected.contains(network.getNetworkHandle()));
    detector.destroy();
  }

  @Test public void factoryWorksThroughRealNetworkMonitorAndRetainsActiveDetector() throws Exception {
    ReflectionHelpers.setStaticField(Build.class, "MANUFACTURER", "HUAWEI");
    connectivity.reject = quota();
    HuaweiNetworkChangeDetector.installIfNeeded();
    NetworkMonitor monitor = NetworkMonitor.getInstance();
    monitor.startMonitoring(context, "getAllNetworksFromCache:true");
    NetworkChangeDetector detector = monitor.getNetworkChangeDetector();
    assertTrue(detector instanceof HuaweiNetworkChangeDetector);
    assertEquals(1, monitor.getNumObservers());
    HuaweiNetworkChangeDetector.installIfNeeded();
    assertSame(detector, monitor.getNetworkChangeDetector());
    monitor.startMonitoring(context, "");
    assertEquals(1, connectivity.registrations);
    monitor.stopMonitoring();
    assertEquals(0, context.receiverRemoves);
    monitor.stopMonitoring();
    assertEquals(0, monitor.getNumObservers());
    assertNull(monitor.getNetworkChangeDetector());
    assertEquals(1, context.receiverRemoves);
  }
}
