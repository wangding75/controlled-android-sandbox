package com.warden.controlledsandbox.fixture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import org.json.JSONObject;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Package-neutral Location campaign.  The fixture records observations only; the host-side
 * runner validates coordinates, callback ordering, cleanup and explicit negative branches.
 */
@SuppressLint({"MissingPermission", "NewApi", "WrongConstant"})
public final class LocationCampaignActivity extends Activity {
    private static final String TAG = "CS_C2_T03_LOCATION";
    private static final String PROVIDER = LocationManager.GPS_PROVIDER;
    private static final String MARKER_PROBE = "C2_T03_LOCATION_PROBE";
    private static final String MARKER_CALLBACK = "C2_T03_LOCATION_CALLBACK";
    private static final String MARKER_NMEA = "C2_T03_LOCATION_NMEA";
    private static final String MARKER_GNSS = "C2_T03_LOCATION_GNSS";
    private static final String MARKER_NEGATIVE = "C2_T03_LOCATION_NEGATIVE";

    private final String session = UUID.randomUUID().toString();
    private final AtomicInteger callbackCount = new AtomicInteger();
    private final AtomicInteger currentCount = new AtomicInteger();
    private final AtomicInteger nmeaCount = new AtomicInteger();
    private final AtomicLong lastCallbackAt = new AtomicLong();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor direct = Runnable::run;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private GnssStatus.Callback gnssCallback;
    private OnNmeaMessageListener nmeaListener;
    private boolean campaignStarted;
    private boolean registrationsActive;
    private boolean foreground;
    private boolean destroyed;
    private boolean providerAvailable;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new View(this));
        foreground = true;
        log("LIFECYCLE", json().put("event", "onCreate").put("session", session));
        main.postDelayed(this::startCampaign, 250L);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        log("LIFECYCLE", json().put("event", "onResume").put("session", session));
        if (campaignStarted && !registrationsActive) registerCallbacks("resume");
    }

    @Override protected void onPause() {
        foreground = false;
        unregisterCallbacks("pause");
        log("LIFECYCLE", json().put("event", "onPause").put("session", session)
                .put("callbacks", callbackCount.get()).put("nmea", nmeaCount.get()));
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        unregisterCallbacks("destroy");
        log("LIFECYCLE", json().put("event", "onDestroy").put("session", session)
                .put("callbacks", callbackCount.get()).put("nmea", nmeaCount.get()));
        super.onDestroy();
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    private void startCampaign() {
        if (destroyed || !foreground || campaignStarted) return;
        campaignStarted = true;
        locationManager = getSystemService(LocationManager.class);
        if (locationManager == null) {
            fail("LOCATION_MANAGER_NULL", json().put("session", session));
            return;
        }
        try {
            List<String> all = locationManager.getAllProviders();
            List<String> enabled = locationManager.getProviders(true);
            JSONObject probe = json().put("session", session)
                    .put("provider", PROVIDER)
                    .put("allProviders", String.valueOf(all))
                    .put("enabledProviders", String.valueOf(enabled))
                    .put("locationEnabled", locationManager.isLocationEnabled())
                    .put("providerEnabled", locationManager.isProviderEnabled(PROVIDER))
                    .put("hasProvider", locationManager.hasProvider(PROVIDER))
                    .put("bestProvider", String.valueOf(locationManager.getBestProvider(
                            new Criteria(), true)));
            providerAvailable = locationManager.isProviderEnabled(PROVIDER);
            Location last = locationManager.getLastKnownLocation(PROVIDER);
            if (last != null) putLocation(probe, "last", last);
            log("PROBE", probe);
        } catch (Throwable error) {
            fail("PROBE_ERROR", json().put("session", session)
                    .put("error", error.getClass().getSimpleName()));
        }
        try {
            CancellationSignal signal = new CancellationSignal();
            locationManager.getCurrentLocation(PROVIDER, signal, direct, value -> {
                if (value == null) {
                    log("CURRENT_NULL", json().put("session", session)
                            .put("providerAvailable", providerAvailable));
                    if (providerAvailable) {
                        fail("CURRENT_NULL_WHILE_ENABLED", json().put("session", session));
                    }
                    return;
                }
                currentCount.incrementAndGet();
                log("CURRENT", locationJson(value).put("session", session)
                        .put("sequence", currentCount.get()));
            });
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                LocationRequest request = new LocationRequest.Builder(1000L)
                        .setMinUpdateIntervalMillis(250L).build();
                locationManager.getCurrentLocation(PROVIDER, request, null, direct, value -> {
                    if (value == null) {
                        log("CURRENT_REQUEST_NULL", json().put("session", session)
                                .put("providerAvailable", providerAvailable));
                        if (providerAvailable) {
                            fail("CURRENT_REQUEST_NULL_WHILE_ENABLED", json().put("session", session));
                        }
                        return;
                    }
                    currentCount.incrementAndGet();
                    log("CURRENT_REQUEST", locationJson(value).put("session", session)
                            .put("sequence", currentCount.get()));
                });
            }
        } catch (Throwable error) {
            fail("CURRENT_ERROR", json().put("session", session)
                    .put("error", error.getClass().getSimpleName()));
        }
        registerCallbacks("initial");
        negativeBranches();
    }

    @SuppressLint("MissingPermission")
    private void registerCallbacks(String reason) {
        if (destroyed || locationManager == null || registrationsActive) return;
        registrationsActive = true;
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location value) {
                long now = System.currentTimeMillis();
                long previous = lastCallbackAt.getAndSet(now);
                int sequence = callbackCount.incrementAndGet();
                JSONObject event = locationJson(value).put("session", session)
                        .put("reason", reason).put("sequence", sequence)
                        .put("callbackAtMs", now)
                        .put("ordered", previous == 0L || now >= previous)
                        .put("foreground", foreground);
                log("CALLBACK", event);
            }

            @Override public void onProviderEnabled(String provider) {
                log("PROVIDER", json().put("session", session).put("event", "enabled")
                        .put("provider", provider));
            }

            @Override public void onProviderDisabled(String provider) {
                log("PROVIDER", json().put("session", session).put("event", "disabled")
                        .put("provider", provider));
            }
        };
        try {
            locationManager.requestLocationUpdates(PROVIDER, 1000L, 0f, direct, locationListener);
            log("REGISTER", json().put("session", session).put("kind", "location")
                    .put("reason", reason).put("available", providerAvailable));
        } catch (Throwable error) {
            fail("LOCATION_REGISTER_ERROR", json().put("session", session)
                    .put("error", error.getClass().getSimpleName()));
        }
        if (Build.VERSION.SDK_INT >= 24) {
            nmeaListener = (sentence, timestamp) -> {
                int sequence = nmeaCount.incrementAndGet();
                log("NMEA", json().put("session", session).put("sequence", sequence)
                        .put("timestamp", timestamp).put("sentence", sentence));
            };
            try {
                boolean registered = locationManager.addNmeaListener(direct, nmeaListener);
                log("REGISTER", json().put("session", session).put("kind", "nmea")
                        .put("registered", registered).put("reason", reason));
            } catch (Throwable error) {
                fail("NMEA_REGISTER_ERROR", json().put("session", session)
                        .put("error", error.getClass().getSimpleName()));
            }
        }
        if (Build.VERSION.SDK_INT >= 24) {
            gnssCallback = new GnssStatus.Callback() {
                @Override public void onStarted() {
                    log("GNSS", json().put("session", session).put("event", "started"));
                }

                @Override public void onFirstFix(int ttffMillis) {
                    log("GNSS", json().put("session", session).put("event", "firstFix")
                            .put("ttffMillis", ttffMillis));
                }

                @Override public void onStopped() {
                    log("GNSS", json().put("session", session).put("event", "stopped"));
                }
            };
            try {
                boolean registered = locationManager.registerGnssStatusCallback(direct, gnssCallback);
                log("REGISTER", json().put("session", session).put("kind", "gnss")
                        .put("registered", registered).put("reason", reason));
            } catch (Throwable error) {
                fail("GNSS_REGISTER_ERROR", json().put("session", session)
                        .put("error", error.getClass().getSimpleName()));
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void unregisterCallbacks(String reason) {
        if (!registrationsActive || locationManager == null) return;
        LocationListener location = locationListener;
        OnNmeaMessageListener nmea = nmeaListener;
        GnssStatus.Callback gnss = gnssCallback;
        registrationsActive = false;
        locationListener = null;
        nmeaListener = null;
        gnssCallback = null;
        try { if (location != null) locationManager.removeUpdates(location); } catch (Throwable error) {
            fail("LOCATION_UNREGISTER_ERROR", json().put("session", session)
                    .put("error", error.getClass().getSimpleName()));
        }
        if (nmea != null && Build.VERSION.SDK_INT >= 24) {
            try { locationManager.removeNmeaListener(nmea); } catch (Throwable error) {
                fail("NMEA_UNREGISTER_ERROR", json().put("session", session)
                        .put("error", error.getClass().getSimpleName()));
            }
        }
        if (gnss != null && Build.VERSION.SDK_INT >= 24) {
            try { locationManager.unregisterGnssStatusCallback(gnss); } catch (Throwable error) {
                fail("GNSS_UNREGISTER_ERROR", json().put("session", session)
                        .put("error", error.getClass().getSimpleName()));
            }
        }
        log("UNREGISTER", json().put("session", session).put("reason", reason)
                .put("callbackCount", callbackCount.get()).put("nmeaCount", nmeaCount.get()));
    }

    @SuppressLint("MissingPermission")
    private void negativeBranches() {
        try {
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 303,
                    new Intent(this, LocationCampaignActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            locationManager.requestLocationUpdates(PROVIDER, 1000L, 0f, pendingIntent);
            fail("PENDING_INTENT_ACCEPTED", json().put("session", session));
        } catch (UnsupportedOperationException expected) {
            log("NEGATIVE", json().put("session", session).put("kind", "pendingIntent")
                    .put("outcome", "EXPLICIT_UNSUPPORTED").put("message", expected.getMessage()));
        } catch (Throwable error) {
            fail("PENDING_INTENT_WRONG_FAILURE", json().put("session", session)
                    .put("error", error.getClass().getSimpleName()));
        }
        try {
            locationManager.addTestProvider("c2-t03-test", false, false, false,
                    false, false, false, false, 1, 1);
            fail("TEST_PROVIDER_ACCEPTED", json().put("session", session));
        } catch (UnsupportedOperationException expected) {
            log("NEGATIVE", json().put("session", session).put("kind", "testProvider")
                    .put("outcome", "EXPLICIT_UNSUPPORTED").put("message", expected.getMessage()));
        } catch (Throwable error) {
            fail("TEST_PROVIDER_WRONG_FAILURE", json().put("session", session)
                    .put("error", error.getClass().getSimpleName()));
        }
    }

    private Event locationJson(Location value) {
        Event result = json();
        putLocation(result, "location", value);
        return result;
    }

    private static Event json() {
        return new Event();
    }

    private void log(String kind, JSONObject value) {
        Log.i(TAG, "C2_T03_LOCATION_" + kind + " " + value);
    }

    private void fail(String kind, JSONObject value) {
        Log.e(TAG, "C2_T03_LOCATION_FAIL " + kind + " " + value);
    }

    private static void putLocation(JSONObject output, String prefix, Location value) {
        if (value == null) return;
        try {
            output.put(prefix + "Provider", value.getProvider())
                    .put(prefix + "Latitude", value.getLatitude())
                    .put(prefix + "Longitude", value.getLongitude())
                    .put(prefix + "Altitude", value.getAltitude())
                    .put(prefix + "Accuracy", value.getAccuracy())
                    .put(prefix + "Speed", value.getSpeed())
                    .put(prefix + "Bearing", value.getBearing())
                    .put(prefix + "Time", value.getTime())
                    .put(prefix + "ElapsedRealtimeNanos", value.getElapsedRealtimeNanos());
        } catch (Exception ignored) { }
    }

    /** Campaign markers must remain best-effort even when a platform JSON implementation differs. */
    private static final class Event extends JSONObject {
        @Override public Event put(String name, Object value) {
            try { super.put(name, value); } catch (Exception ignored) { }
            return this;
        }
        public Event put(String name, boolean value) {
            try { super.put(name, value); } catch (Exception ignored) { }
            return this;
        }
        public Event put(String name, double value) {
            try { super.put(name, value); } catch (Exception ignored) { }
            return this;
        }
        public Event put(String name, int value) {
            try { super.put(name, value); } catch (Exception ignored) { }
            return this;
        }
        public Event put(String name, long value) {
            try { super.put(name, value); } catch (Exception ignored) { }
            return this;
        }
    }
}
