package android.net;

/** Minimal API 21+ callback shape used by the package-neutral fixture compiler. */
public class ConnectivityManager {
    public static class NetworkCallback {
        public void onAvailable(Network network) { }
        public void onLost(Network network) { }
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { }
        public void onLinkPropertiesChanged(Network network, LinkProperties properties) { }
        public void onBlockedStatusChanged(Network network, boolean blocked) { }
    }
}
