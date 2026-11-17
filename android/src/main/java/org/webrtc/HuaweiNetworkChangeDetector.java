package org.webrtc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Passive monitoring for Huawei Android 10 and earlier. Network callback
 * registration can be rejected when the system's per-UID quota is exhausted.
 * Never request an additional cellular network just to observe it.
 *
 * Uses the network conversion delegate from the pinned WebRTC 144.7559.09 API.
 * Other devices keep the upstream detector and its behavior unchanged.
 */
public final class HuaweiNetworkChangeDetector extends BroadcastReceiver
    implements NetworkChangeDetector {
  private static final String TAG = "HuaweiNetworkDetector";
  private final Context context;
  private final Observer observer;
  private final NetworkMonitorAutoDetect.ConnectivityManagerDelegate delegate;
  private final PassiveNetworkRegistration registration;
  private final Map<Long, NetworkInformation> knownNetworks = new HashMap<>();
  private ConnectionType connectionType;
  private boolean receiverRegistered;
  private boolean destroyed;

  public static void installIfNeeded() {
    if (!PassiveNetworkRegistration.useOn(Build.MANUFACTURER, Build.VERSION.SDK_INT)) return;
    NetworkMonitor monitor = NetworkMonitor.getInstance();
    if (monitor.getNumObservers() != 0) {
      Logging.w(TAG, "Existing network monitor retained; cannot replace an active detector");
      return;
    }
    monitor.setNetworkChangeDetectorFactory(HuaweiNetworkChangeDetector::new);
    Logging.d(TAG, "Using passive Huawei network monitoring");
  }

  HuaweiNetworkChangeDetector(Observer observer, Context suppliedContext) {
    this.context = suppliedContext.getApplicationContext();
    this.observer = observer;
    // A rejected callback cannot populate WebRTC's callback-only network cache.
    // Enumerate actual connected networks both normally and in broadcast fallback.
    String trials = observer.getFieldTrialsString();
    if (trials == null) trials = "";
    trials = trials.replace("getAllNetworksFromCache:true", "getAllNetworksFromCache:false")
        + ",getAllNetworksFromCache:false";
    delegate = new NetworkMonitorAutoDetect.ConnectivityManagerDelegate(
        context, new HashSet<>(), trials);
    connectionType = getCurrentConnectionType();
    ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
      @Override public void onAvailable(Network network) { refresh(); }
      @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
        refresh();
      }
      @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
        refresh();
      }
      @Override public void onLost(Network network) {
        synchronized (HuaweiNetworkChangeDetector.this) {
          if (destroyed) return;
          if (knownNetworks.remove(network.getNetworkHandle()) != null) {
            observer.onNetworkDisconnect(network.getNetworkHandle());
          }
          updateConnectionType();
        }
      }
    };
    registration = new PassiveNetworkRegistration(
        () -> delegate.registerNetworkCallback(callback), () -> delegate.releaseCallback(callback));
    // Shaling AI fork patch: include the initial native network snapshot when
    // detecting a loss before the first callback or connectivity broadcast.
    for (NetworkInformation network : getActiveNetworkList()) {
      knownNetworks.put(network.handle, network);
    }
    context.registerReceiver(this, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    receiverRegistered = true;
    try {
      if (!registration.acquire()) {
        Logging.w(TAG, "Network callback unavailable; retaining connectivity broadcast fallback");
      }
    } catch (RuntimeException error) {
      // Shaling AI fork patch: preserve the registration failure if rollback
      // also fails, and prevent a retained receiver from sending notifications.
      destroyed = true;
      try {
        context.unregisterReceiver(this);
        receiverRegistered = false;
      } catch (RuntimeException cleanupError) {
        if (cleanupError != error) error.addSuppressed(cleanupError);
      }
      throw error;
    }
  }

  @Override public synchronized ConnectionType getCurrentConnectionType() {
    return NetworkMonitorAutoDetect.getConnectionType(delegate.getNetworkState());
  }

  @Override public synchronized boolean supportNetworkCallback() {
    return !destroyed && registration.isRegistered();
  }

  @Override public synchronized List<NetworkInformation> getActiveNetworkList() {
    if (destroyed) return Collections.emptyList();
    List<NetworkInformation> networks = delegate.getActiveNetworkList();
    return networks == null ? Collections.emptyList() : new ArrayList<>(networks);
  }

  @Override public void onReceive(Context context, Intent intent) {
    if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) refresh();
  }

  private synchronized void refresh() {
    if (destroyed) return;
    Map<Long, NetworkInformation> current = new HashMap<>();
    for (NetworkInformation network : getActiveNetworkList()) current.put(network.handle, network);
    for (Long handle : new ArrayList<>(knownNetworks.keySet())) {
      if (!current.containsKey(handle)) observer.onNetworkDisconnect(handle);
    }
    // Refresh addresses and transport type as well as newly appearing handles.
    for (NetworkInformation network : current.values()) observer.onNetworkConnect(network);
    knownNetworks.clear();
    knownNetworks.putAll(current);
    updateConnectionType();
  }

  private void updateConnectionType() {
    ConnectionType current = getCurrentConnectionType();
    if (current != connectionType) {
      connectionType = current;
      observer.onConnectionTypeChanged(current);
    }
  }

  @Override public synchronized void destroy() {
    // Shaling AI fork patch: stop notifications first and attempt both releases.
    // Retain ownership of a failed release so a subsequent destroy can retry it.
    destroyed = true;
    RuntimeException failure = null;
    try {
      registration.close();
    } catch (RuntimeException error) {
      failure = error;
    }
    if (receiverRegistered) {
      try {
        context.unregisterReceiver(this);
        receiverRegistered = false;
      } catch (RuntimeException error) {
        if (failure == null) failure = error;
        else if (failure != error) failure.addSuppressed(error);
      }
    }
    knownNetworks.clear();
    if (failure != null) throw failure;
  }
}
