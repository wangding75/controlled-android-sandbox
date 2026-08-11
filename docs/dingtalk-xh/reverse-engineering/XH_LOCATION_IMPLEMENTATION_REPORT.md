# XH location implementation report

## Evidence

**SOURCE:** `FackLocService` registers a GPS test provider, enables it, and injects a `Location` on a one-second loop. The restore sets latitude, longitude, accuracy, altitude, current wall time and elapsed realtime nanos. `LocationSettingsActivity` persists the selected point and starts/stops the service.

**DECOMPILED:** SX `LocationHook.createFakeLocation` sets configured latitude/longitude (with optional random micro-drift), altitude, accuracy, current time and `SystemClock.elapsedRealtimeNanos`. Hooks cover Location getters, `isFromMockProvider`, API-31 `isMock`, and `LocationManager.getLastKnownLocation`.

The recovered SX hook does not prove a complete guest `ILocationManager` callback, PendingIntent, provider-state, GNSS or test-provider projection.

## Controlled path

```text
VirtualLocationProfileSnapshot
  -> instance/user persistence and JSON/Parcelable codec
  -> Guest LocationManager / ILocationManager proxy
       -> standard Location result factory
       -> immediate + bounded periodic listener callbacks
       -> provider state / last-known / current-location projection
```

The profile supports fixed/static and route points, walking/cycling/driving/route labels, interpolation, speed/bearing/altitude/accuracy, timestamp policy and elapsed-realtime policy. Callback futures are daemon, bounded to one scheduler, and canceled by capability lease cleanup.

## Boundary and evidence

PendingIntent delivery, geofence and test-provider mutation remain explicit unsupported operations. The Guest path is independent of Host location. A future fixture must record latitude, longitude, altitude, accuracy, speed, timestamp and elapsed nanos from the standard API. DingTalk business-page location was not reached in the saved SX trace.

