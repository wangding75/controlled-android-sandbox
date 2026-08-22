package android.location;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.location.provider.ProviderProperties;

import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Guest-owned LocationManager boundary for API images whose hidden listener transport is only a
 * Binder marker. It deliberately never delegates location reads or callbacks to the Host.
 */
public final class ControlledLocationManager extends LocationManager implements AutoCloseable {
    private final Supplier<VirtualLocationProfileSnapshot> profileSupplier;
    private final BooleanSupplier locationPermission;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
            runnable -> {
                Thread thread = new Thread(runnable, "controlled-sandbox-location-manager");
                thread.setDaemon(true);
                return thread;
            });
    private final Map<LocationListener, LocationRegistration> listeners = new IdentityHashMap<>();
    private final Map<Object, NmeaRegistration> nmeaListeners = new IdentityHashMap<>();
    private final Map<GnssStatus.Callback, GnssRegistration> gnssCallbacks = new IdentityHashMap<>();
    private volatile boolean closed;

    public ControlledLocationManager(Supplier<VirtualLocationProfileSnapshot> profileSupplier) {
        this(profileSupplier, () -> true);
    }

    public ControlledLocationManager(Supplier<VirtualLocationProfileSnapshot> profileSupplier,
            BooleanSupplier locationPermission) {
        super();
        this.profileSupplier = Objects.requireNonNull(profileSupplier, "profileSupplier");
        this.locationPermission = Objects.requireNonNull(locationPermission, "locationPermission");
    }

    @Override public boolean isLocationEnabled() {
        return locationPermission.getAsBoolean() && profile().providerEnabled() && !blocked();
    }

    @Override public boolean isProviderEnabled(String provider) {
        return locationPermission.getAsBoolean()
                && accepts(provider) && profile().providerEnabled() && !blocked();
    }

    @Override public Location getLastKnownLocation(String provider) {
        VirtualLocationProfileSnapshot current = profile();
        if (!locationPermission.getAsBoolean() || !accepts(current, provider) || !current.providerEnabled()
                || VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(current.mode())) return null;
        return location(current);
    }

    @SuppressLint("MissingPermission")
    @Override public void getCurrentLocation(String provider, CancellationSignal cancellationSignal,
            Executor executor, Consumer<Location> consumer) {
        getCurrentLocation(provider, null, cancellationSignal, executor, consumer);
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    @Override public void getCurrentLocation(String provider, LocationRequest request,
            CancellationSignal cancellationSignal, Executor executor, Consumer<Location> consumer) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(consumer, "consumer");
        if (cancellationSignal != null && cancellationSignal.isCanceled()) return;
        executor.execute(() -> {
            if (cancellationSignal == null || !cancellationSignal.isCanceled()) {
                consumer.accept(getLastKnownLocation(provider));
            }
        });
    }

    @SuppressLint("MissingPermission")
    @Override public void requestLocationUpdates(String provider, long minTimeMs,
            float minDistanceM, LocationListener listener) {
        requestLocationUpdates(provider, minTimeMs, minDistanceM, listener,
                Looper.getMainLooper());
    }

    @SuppressLint("MissingPermission")
    @Override public void requestLocationUpdates(String provider, long minTimeMs,
            float minDistanceM, LocationListener listener, Looper looper) {
        Handler handler = new Handler(looper == null ? Looper.getMainLooper() : looper);
        register(provider, minTimeMs, listener, handler::post);
    }

    @SuppressLint("MissingPermission")
    @Override public void requestLocationUpdates(String provider, long minTimeMs,
            float minDistanceM, Executor executor, LocationListener listener) {
        register(provider, minTimeMs, listener, executor);
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    @Override public void requestLocationUpdates(String provider, LocationRequest request,
            Executor executor, LocationListener listener) {
        long interval = request == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                ? profile().minimumUpdateIntervalMs() : request.getIntervalMillis();
        register(provider, interval, listener, executor);
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    @Override public void requestLocationUpdates(String provider, LocationRequest request,
            PendingIntent intent) {
        throw unsupported("VIRTUAL_LOCATION_PENDING_INTENT_UNSUPPORTED");
    }

    @SuppressLint("MissingPermission")
    @Override public void requestLocationUpdates(long minTimeMs, float minDistanceM,
            Criteria criteria, LocationListener listener, Looper looper) {
        requestLocationUpdates(profile().provider(), minTimeMs, minDistanceM, listener, looper);
    }

    @SuppressLint("MissingPermission")
    @Override public void requestLocationUpdates(long minTimeMs, float minDistanceM,
            Criteria criteria, Executor executor, LocationListener listener) {
        requestLocationUpdates(profile().provider(), minTimeMs, minDistanceM, executor, listener);
    }

    @Override public void requestSingleUpdate(String provider, LocationListener listener,
            Looper looper) {
        Handler handler = new Handler(looper == null ? Looper.getMainLooper() : looper);
        Objects.requireNonNull(listener, "listener");
        LocationListener oneShot = new LocationListener() {
            @Override public void onLocationChanged(Location value) {
                removeUpdates(this);
                listener.onLocationChanged(value);
            }
            @Override public void onProviderEnabled(String value) {
                listener.onProviderEnabled(value);
            }
            @Override public void onProviderDisabled(String value) {
                listener.onProviderDisabled(value);
            }
        };
        register(provider, 0L, oneShot, handler::post);
    }

    @SuppressLint("MissingPermission")
    @Override public void requestSingleUpdate(Criteria criteria, LocationListener listener,
            Looper looper) {
        requestSingleUpdate(profile().provider(), listener, looper);
    }

    @Override public void requestSingleUpdate(String provider, PendingIntent intent) {
        throw unsupported("VIRTUAL_LOCATION_PENDING_INTENT_UNSUPPORTED");
    }

    @Override public void requestSingleUpdate(Criteria criteria, PendingIntent intent) {
        throw unsupported("VIRTUAL_LOCATION_PENDING_INTENT_UNSUPPORTED");
    }

    @Override public void removeUpdates(LocationListener listener) {
        if (listener == null) return;
        LocationRegistration registration;
        synchronized (listeners) {
            registration = listeners.remove(listener);
            if (registration != null) registration.active = false;
        }
        cancel(registration == null ? null : registration.future);
    }

    @Override public boolean hasProvider(String provider) {
        return accepts(provider);
    }

    @Override public List<String> getAllProviders() {
        if (!locationPermission.getAsBoolean() || blocked()) return Collections.emptyList();
        return Collections.singletonList(profile().provider());
    }

    @Override public List<String> getProviders(boolean enabledOnly) {
        if (!locationPermission.getAsBoolean() || blocked()
                || (enabledOnly && !profile().providerEnabled())) {
            return Collections.emptyList();
        }
        return Collections.singletonList(profile().provider());
    }

    @Override public List<String> getProviders(Criteria criteria, boolean enabledOnly) {
        return getProviders(enabledOnly);
    }

    @Override public String getBestProvider(Criteria criteria, boolean enabledOnly) {
        return getProviders(enabledOnly).isEmpty() ? null : profile().provider();
    }

    @Override public void requestLocationUpdates(String provider, long minTimeMs,
            float minDistanceM, PendingIntent intent) {
        throw unsupported("VIRTUAL_LOCATION_PENDING_INTENT_UNSUPPORTED");
    }

    @Override public void requestLocationUpdates(long minTimeMs, float minDistanceM,
            Criteria criteria, PendingIntent intent) {
        throw unsupported("VIRTUAL_LOCATION_PENDING_INTENT_UNSUPPORTED");
    }

    @Override public void removeUpdates(PendingIntent intent) { }

    @SuppressLint({"MissingPermission", "NewApi"})
    @Override public boolean addNmeaListener(GpsStatus.NmeaListener listener) {
        return registerNmea(listener, Runnable::run);
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    @Override public boolean addNmeaListener(OnNmeaMessageListener listener) {
        return registerNmea(listener, Runnable::run);
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    @Override public boolean addNmeaListener(OnNmeaMessageListener listener, Handler handler) {
        Handler target = handler == null ? new Handler(Looper.getMainLooper()) : handler;
        return registerNmea(listener, target::post);
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    @Override public boolean addNmeaListener(Executor executor, OnNmeaMessageListener listener) {
        return registerNmea(listener, executor);
    }

    @Override public void removeNmeaListener(GpsStatus.NmeaListener listener) {
        removeNmea(listener);
    }

    @Override public void removeNmeaListener(OnNmeaMessageListener listener) {
        removeNmea(listener);
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    @Override public boolean registerGnssStatusCallback(GnssStatus.Callback callback) {
        return registerGnss(callback, Runnable::run);
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    @Override public boolean registerGnssStatusCallback(GnssStatus.Callback callback, Handler handler) {
        Handler target = handler == null ? new Handler(Looper.getMainLooper()) : handler;
        return registerGnss(callback, target::post);
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    @Override public boolean registerGnssStatusCallback(Executor executor, GnssStatus.Callback callback) {
        return registerGnss(callback, executor);
    }

    @Override public void unregisterGnssStatusCallback(GnssStatus.Callback callback) {
        if (callback == null) return;
        GnssRegistration registration;
        synchronized (gnssCallbacks) {
            registration = gnssCallbacks.remove(callback);
            if (registration != null) registration.active = false;
        }
        if (registration != null) {
            cancel(registration.future);
            if (registration.started) {
                dispatch(registration.executor, () -> safeStopped(registration.callback));
            }
        }
    }

    @Override public void addTestProvider(String provider, ProviderProperties properties) {
        throw unsupported("VIRTUAL_LOCATION_TEST_PROVIDER_UNSUPPORTED");
    }

    @SuppressLint("NewApi")
    @Override public void addTestProvider(String provider, ProviderProperties properties,
            Set<String> extraAttributionTags) {
        throw unsupported("VIRTUAL_LOCATION_TEST_PROVIDER_UNSUPPORTED");
    }

    @Override public void addTestProvider(String provider, boolean requiresNetwork,
            boolean requiresSatellite, boolean requiresCell, boolean hasMonetaryCost,
            boolean supportsAltitude, boolean supportsSpeed, boolean supportsBearing,
            int powerRequirement, int accuracy) {
        throw unsupported("VIRTUAL_LOCATION_TEST_PROVIDER_UNSUPPORTED");
    }

    @Override public void removeTestProvider(String provider) {
        throw unsupported("VIRTUAL_LOCATION_TEST_PROVIDER_UNSUPPORTED");
    }

    @Override public void setTestProviderEnabled(String provider, boolean enabled) {
        throw unsupported("VIRTUAL_LOCATION_TEST_PROVIDER_UNSUPPORTED");
    }

    @Override public void setTestProviderLocation(String provider, Location location) {
        throw unsupported("VIRTUAL_LOCATION_TEST_PROVIDER_UNSUPPORTED");
    }

    @Override public void setTestProviderStatus(String provider, int status, android.os.Bundle extras,
            long updateTime) {
        throw unsupported("VIRTUAL_LOCATION_TEST_PROVIDER_UNSUPPORTED");
    }

    @Override public void clearTestProviderEnabled(String provider) {
        throw unsupported("VIRTUAL_LOCATION_TEST_PROVIDER_UNSUPPORTED");
    }

    @Override public void clearTestProviderLocation(String provider) {
        throw unsupported("VIRTUAL_LOCATION_TEST_PROVIDER_UNSUPPORTED");
    }

    @Override public void clearTestProviderStatus(String provider) {
        throw unsupported("VIRTUAL_LOCATION_TEST_PROVIDER_UNSUPPORTED");
    }

    @SuppressLint("MissingPermission")
    private void register(String provider, long interval, LocationListener listener,
            Executor callbackExecutor) {
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(callbackExecutor, "executor");
        removeUpdates(listener);
        if (closed || !available(provider)) return;
        long delay = cadence(interval);
        LocationRegistration registration = new LocationRegistration(listener, callbackExecutor);
        synchronized (listeners) {
            if (closed) return;
            listeners.put(listener, registration);
        }
        ScheduledFuture<?> future;
        try {
            future = scheduler.scheduleAtFixedRate(() -> tick(provider, registration),
                    0L, delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException error) {
            removeUpdates(listener);
            throw error;
        }
        synchronized (listeners) {
            if (listeners.get(listener) == registration && registration.active && !closed) {
                registration.future = future;
            } else {
                future.cancel(false);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void tick(String provider, LocationRegistration registration) {
        if (!isActive(registration)) return;
        if (!available(provider)) {
            removeUpdates(registration.listener);
            return;
        }
        Location value = getLastKnownLocation(provider);
        if (value == null || !isActive(registration)) return;
        try {
            registration.executor.execute(() -> {
                if (isActive(registration)) registration.listener.onLocationChanged(value);
            });
        } catch (RuntimeException ignored) {
            removeUpdates(registration.listener);
        }
    }

    private boolean registerNmea(Object listener, Executor executor) {
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(executor, "executor");
        removeNmea(listener);
        if (closed || !gnssAvailable()) return false;
        NmeaRegistration registration = new NmeaRegistration(listener, executor);
        synchronized (nmeaListeners) {
            if (closed) return false;
            nmeaListeners.put(listener, registration);
        }
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> tickNmea(registration), 0L, cadence(0L), TimeUnit.MILLISECONDS);
        synchronized (nmeaListeners) {
            if (nmeaListeners.get(listener) == registration && registration.active && !closed) {
                registration.future = future;
            } else {
                future.cancel(false);
            }
        }
        return true;
    }

    private void tickNmea(NmeaRegistration registration) {
        if (!isActive(registration)) return;
        if (!gnssAvailable()) {
            removeNmea(registration.listener);
            return;
        }
        VirtualLocationProfileSnapshot value = profile();
        if (value.nmeaSentence().isEmpty()) return;
        long timestamp = value.timeMs() > 0L ? value.timeMs() : System.currentTimeMillis();
        dispatch(registration.executor, () -> {
            if (!isActive(registration)) return;
            if (registration.listener instanceof OnNmeaMessageListener listener) {
                listener.onNmeaMessage(value.nmeaSentence(), timestamp);
            } else if (registration.listener instanceof GpsStatus.NmeaListener listener) {
                listener.onNmeaReceived(timestamp, value.nmeaSentence());
            }
        });
    }

    private void removeNmea(Object listener) {
        if (listener == null) return;
        NmeaRegistration registration;
        synchronized (nmeaListeners) {
            registration = nmeaListeners.remove(listener);
            if (registration != null) registration.active = false;
        }
        if (registration != null) cancel(registration.future);
    }

    private boolean registerGnss(GnssStatus.Callback callback, Executor executor) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(executor, "executor");
        unregisterGnssStatusCallback(callback);
        if (closed || !gnssAvailable()) return false;
        GnssRegistration registration = new GnssRegistration(callback, executor);
        synchronized (gnssCallbacks) {
            if (closed) return false;
            gnssCallbacks.put(callback, registration);
        }
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> tickGnss(registration), 0L, cadence(0L), TimeUnit.MILLISECONDS);
        synchronized (gnssCallbacks) {
            if (gnssCallbacks.get(callback) == registration && registration.active && !closed) {
                registration.future = future;
            } else {
                future.cancel(false);
            }
        }
        return true;
    }

    private void tickGnss(GnssRegistration registration) {
        if (!isActive(registration)) return;
        if (!gnssAvailable()) {
            unregisterGnssStatusCallback(registration.callback);
            return;
        }
        if (!registration.started) {
            registration.started = true;
            dispatch(registration.executor, () -> {
                if (!isActive(registration)) return;
                registration.callback.onStarted();
                registration.callback.onFirstFix(0);
            });
        }
    }

    private boolean isActive(LocationRegistration registration) {
        synchronized (listeners) {
            return !closed && registration.active && listeners.get(registration.listener) == registration;
        }
    }

    private boolean isActive(NmeaRegistration registration) {
        synchronized (nmeaListeners) {
            return !closed && registration.active && nmeaListeners.get(registration.listener) == registration;
        }
    }

    private boolean isActive(GnssRegistration registration) {
        synchronized (gnssCallbacks) {
            return !closed && registration.active && gnssCallbacks.get(registration.callback) == registration;
        }
    }

    private boolean available(String provider) {
        VirtualLocationProfileSnapshot value = profile();
        return locationPermission.getAsBoolean() && accepts(value, provider) && value.providerEnabled()
                && !VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(value.mode());
    }

    private boolean gnssAvailable() {
        VirtualLocationProfileSnapshot value = profile();
        return value.gnssEnabled() && available(value.provider());
    }

    private static void dispatch(Executor executor, Runnable action) {
        try { executor.execute(action); } catch (RuntimeException ignored) { }
    }

    private static void safeStopped(GnssStatus.Callback callback) {
        try { callback.onStopped(); } catch (RuntimeException ignored) { }
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) future.cancel(false);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        List<LocationRegistration> locationRegistrations;
        synchronized (listeners) {
            locationRegistrations = new ArrayList<>(listeners.values());
            listeners.clear();
            for (LocationRegistration registration : locationRegistrations) registration.active = false;
        }
        for (LocationRegistration registration : locationRegistrations) cancel(registration.future);
        List<NmeaRegistration> nmeaRegistrations;
        synchronized (nmeaListeners) {
            nmeaRegistrations = new ArrayList<>(nmeaListeners.values());
            nmeaListeners.clear();
            for (NmeaRegistration registration : nmeaRegistrations) registration.active = false;
        }
        for (NmeaRegistration registration : nmeaRegistrations) cancel(registration.future);
        List<GnssRegistration> gnssRegistrations;
        synchronized (gnssCallbacks) {
            gnssRegistrations = new ArrayList<>(gnssCallbacks.values());
            gnssCallbacks.clear();
            for (GnssRegistration registration : gnssRegistrations) registration.active = false;
        }
        for (GnssRegistration registration : gnssRegistrations) {
            cancel(registration.future);
            if (registration.started) dispatch(registration.executor,
                    () -> safeStopped(registration.callback));
        }
        scheduler.shutdownNow();
    }

    private static UnsupportedOperationException unsupported(String code) {
        return new UnsupportedOperationException(code);
    }

    private static final class LocationRegistration {
        final LocationListener listener;
        final Executor executor;
        volatile ScheduledFuture<?> future;
        volatile boolean active = true;
        LocationRegistration(LocationListener listener, Executor executor) {
            this.listener = listener;
            this.executor = executor;
        }
    }

    private static final class NmeaRegistration {
        final Object listener;
        final Executor executor;
        volatile ScheduledFuture<?> future;
        volatile boolean active = true;
        NmeaRegistration(Object listener, Executor executor) {
            this.listener = listener;
            this.executor = executor;
        }
    }

    private static final class GnssRegistration {
        final GnssStatus.Callback callback;
        final Executor executor;
        volatile ScheduledFuture<?> future;
        volatile boolean active = true;
        volatile boolean started;
        GnssRegistration(GnssStatus.Callback callback, Executor executor) {
            this.callback = callback;
            this.executor = executor;
        }
    }

    private VirtualLocationProfileSnapshot profile() {
        VirtualLocationProfileSnapshot value = profileSupplier.get();
        if (value == null) throw new IllegalStateException("VIRTUAL_LOCATION_PROFILE_UNAVAILABLE");
        return value.sampleAt(System.currentTimeMillis(), System.nanoTime());
    }

    private static Location location(VirtualLocationProfileSnapshot value) {
        Location result = new Location(value.provider());
        result.setLatitude(value.latitude());
        result.setLongitude(value.longitude());
        result.setAltitude(value.altitudeMeters());
        result.setAccuracy(value.accuracyMeters());
        result.setSpeed(value.speedMetersPerSecond());
        result.setBearing(value.bearingDegrees());
        result.setTime(value.timeMs() > 0L ? value.timeMs() : System.currentTimeMillis());
        result.setElapsedRealtimeNanos(value.elapsedRealtimeNanos() > 0L
                ? value.elapsedRealtimeNanos() : System.nanoTime());
        return result;
    }

    private boolean accepts(String provider) { return accepts(profile(), provider); }

    private static boolean accepts(VirtualLocationProfileSnapshot value, String provider) {
        return provider == null || provider.isEmpty() || value.provider().equals(provider)
                || (LocationManager.FUSED_PROVIDER.equals(provider)
                && LocationManager.NETWORK_PROVIDER.equals(value.provider()));
    }

    private boolean blocked() {
        return VirtualLocationProfileSnapshot.MODE_BLOCKED.equals(profile().mode());
    }

    private long cadence(long requested) {
        long configured = profile().minimumUpdateIntervalMs();
        long value = requested > 0L ? requested : configured;
        return Math.max(1L, Math.min(value <= 0L ? 1_000L : value, 24L * 60L * 60L * 1_000L));
    }
}
