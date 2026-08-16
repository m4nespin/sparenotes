package app.trailsafe;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.util.HashSet;
import java.util.Set;

public final class TrailSafeApplication extends Application {
    private final Set<Network> wifiNetworks = new HashSet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        CliRunner.prepare(this);
        watchWifi();
    }

    private void watchWifi() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        Network active = manager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
        if (capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            wifiNetworks.add(active);
        } else {
            BackupJobService.scheduleOnNextWifi(this);
        }

        NetworkRequest wifi = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        manager.registerNetworkCallback(wifi, new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                synchronized (wifiNetworks) {
                    wifiNetworks.add(network);
                }
            }

            @Override
            public void onLost(Network network) {
                synchronized (wifiNetworks) {
                    if (wifiNetworks.remove(network) && wifiNetworks.isEmpty()) {
                        BackupJobService.scheduleOnNextWifi(TrailSafeApplication.this);
                    }
                }
            }
        });
    }
}
