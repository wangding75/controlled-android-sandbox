package com.warden.controlledsandbox.fixture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.CellInfo;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Package-neutral C2-T06 device/network/media callback and lifecycle fixture. */
@SuppressLint({"MissingPermission", "NewApi", "WrongConstant"})
public final class C2T06DeviceNetworkMediaActivity extends Activity {
    private static final String TAG = "CS_C2_T06";
    private static final int DEFAULT_LOOPS = 20;
    private static final int MAX_LOOPS = 100;
    private final String session = UUID.randomUUID().toString();
    private volatile boolean destroyed;
    private TelephonyManager telephony;
    private TelephonyProbeCallback telephonyCallback;
    private boolean telephonyRegistered;
    private ConnectivityManager connectivity;
    private ProbeNetworkCallback networkCallback;
    private boolean networkRegistered;
    private SensorManager sensors;
    private Sensor sensor;
    private ProbeSensorListener sensorListener;
    private boolean sensorRegistered;
    private Object audio;
    private Object audioFocusListener;
    private boolean focusHeld;
    private int networkCallbackCount;
    private int sensorEventCount;
    private int telephonyCallbackCount;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new android.view.View(this));
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                this::runCampaign, 200L);
    }

    private void runCampaign() {
        Bundle extras = getIntent() == null ? null : getIntent().getExtras();
        if (extras != null && extras.getBundle("intentExtras") != null) {
            extras = extras.getBundle("intentExtras");
        }
        String mode = extras == null ? "full" : extras.getString("c2t06Mode", "full");
        int loops = extras == null ? DEFAULT_LOOPS
                : Math.max(1, Math.min(MAX_LOOPS, extras.getInt("c2t06Loops", DEFAULT_LOOPS)));
        try {
            String profileHash = runIdentityAndTelephony(mode);
            runWifi();
            runBluetooth();
            runMediaAndAudio();
            runDnsAndVpn();
            for (int loop = 1; loop <= loops && !destroyed; loop++) {
                runConnectivity(loop);
                runSensor(loop);
                if ((loop == 1) || (loop % 5 == 0)) {
                    Log.i(TAG, "C2_T06_LOOP_PASS loop=" + loop + " profileHash=" + profileHash
                            + " networkCallbacks=" + networkCallbackCount
                            + " sensorEvents=" + sensorEventCount
                            + " telephonyCallbacks=" + telephonyCallbackCount
                            + " session=" + session);
                }
            }
            if ("negative".equalsIgnoreCase(mode)) runPermissionNegative();
            Log.i(TAG, "C2_T06_CAMPAIGN_PASS loops=" + loops + " profileHash=" + profileHash
                    + " networkCallbacks=" + networkCallbackCount
                    + " sensorEvents=" + sensorEventCount
                    + " telephonyCallbacks=" + telephonyCallbackCount
                    + " session=" + session);
        } catch (Throwable error) {
            Log.e(TAG, "C2_T06_CAMPAIGN_FAIL session=" + session, error);
        } finally {
            cleanup("campaign");
        }
    }

    private String runIdentityAndTelephony(String mode) throws Exception {
        String androidId = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ANDROID_ID);
        String serial;
        try { serial = Build.getSerial(); }
        catch (Throwable error) { serial = "ERROR:" + error.getClass().getSimpleName(); }
        String brand = String.valueOf(Build.BRAND);
        String model = String.valueOf(Build.MODEL);
        String identityHash = digest(androidId + "|" + serial + "|" + brand + "|" + model);
        Log.i(TAG, "C2_T06_IDENTITY_RETURN profileHash=" + identityHash
                + " androidIdHash=" + digest(androidId) + " serialHash=" + digest(serial)
                + " brandHash=" + digest(brand) + " modelHash=" + digest(model)
                + " mode=" + mode + " session=" + session);
        telephony = (TelephonyManager) getSystemService("phone");
        if (telephony == null) {
            Log.w(TAG, "C2_T06_TELEPHONY_UNSUPPORTED reason=service-null session=" + session);
            return identityHash;
        }
        int phoneCount = safeInt(invokeNoArg(telephony, "getPhoneCount"), -1);
        String imei = text(invokeNoArg(telephony, "getImei"));
        String meid = text(invokeNoArg(telephony, "getMeid"));
        String imsi = text(invokeNoArg(telephony, "getSubscriberId"));
        String iccid = text(invokeNoArg(telephony, "getSimSerialNumber"));
        String operator = text(invokeNoArg(telephony, "getNetworkOperator"));
        Object cellValue = invokeNoArg(telephony, "getAllCellInfo");
        int cellCount = cellValue instanceof List<?> ? ((List<?>) cellValue).size() : 0;
        Object subscriptions = invokeNoArg(getSystemService("telephony_subscription_service"),
                "getActiveSubscriptionInfoList");
        int subscriptionCount = subscriptions instanceof List<?> ? ((List<?>) subscriptions).size() : 0;
        Log.i(TAG, "C2_T06_TELEPHONY_RETURN phoneCount=" + phoneCount
                + " cellCount=" + cellCount + " subscriptionCount=" + subscriptionCount
                + " imeiHash=" + digest(imei) + " meidHash=" + digest(meid)
                + " imsiHash=" + digest(imsi) + " iccidHash=" + digest(iccid)
                + " operatorHash=" + digest(operator) + " session=" + session);
        if (Build.VERSION.SDK_INT >= 31) {
            telephonyCallback = new TelephonyProbeCallback();
            try {
                invoke(telephony, "registerTelephonyCallback",
                        new Class<?>[]{Executor.class, TelephonyCallback.class},
                        (Executor) command -> command.run(), telephonyCallback);
                telephonyRegistered = true;
                Log.i(TAG, "C2_T06_TELEPHONY_REGISTER_RETURN registered=true session=" + session);
            } catch (Throwable error) {
                Log.w(TAG, "C2_T06_TELEPHONY_CALLBACK_UNSUPPORTED error="
                        + rootCause(error).getClass().getSimpleName() + " session=" + session);
            }
        }
        return identityHash;
    }

    @SuppressLint("MissingPermission")
    private void runWifi() throws Exception {
        WifiManager wifi = (WifiManager) getSystemService("wifi");
        if (wifi == null) {
            Log.w(TAG, "C2_T06_WIFI_UNSUPPORTED reason=service-null session=" + session);
            return;
        }
        WifiInfo info = wifi.getConnectionInfo();
        String ssid = info == null ? "" : text(info.getSSID());
        String bssid = info == null ? "" : text(info.getBSSID());
        String mac = info == null ? "" : text(info.getMacAddress());
        List<?> scans = wifi.getScanResults();
        Object startScan = invokeNoArg(wifi, "startScan");
        Log.i(TAG, "C2_T06_WIFI_RETURN ssidHash=" + digest(ssid)
                + " bssidHash=" + digest(bssid) + " macHash=" + digest(mac)
                + " scanCount=" + (scans == null ? 0 : scans.size())
                + " startScan=" + String.valueOf(startScan) + " session=" + session);
    }

    private void runConnectivity(int loop) throws Exception {
        if (connectivity == null) connectivity = (ConnectivityManager) getSystemService("connectivity");
        if (connectivity == null) {
            if (loop == 1) Log.w(TAG, "C2_T06_CONNECTIVITY_UNSUPPORTED reason=service-null session=" + session);
            return;
        }
        Network active = (Network) invokeNoArg(connectivity, "getActiveNetwork");
        Object capabilities = invoke(connectivity, "getNetworkCapabilities",
                new Class<?>[]{Network.class}, active);
        Object links = invoke(connectivity, "getLinkProperties",
                new Class<?>[]{Network.class}, active);
        if (loop == 1) {
            Log.i(TAG, "C2_T06_CONNECTIVITY_RETURN active=" + (active != null)
                    + " capabilities=" + (capabilities != null)
                    + " linkProperties=" + (links != null) + " session=" + session);
        }
        networkCallback = new ProbeNetworkCallback();
        try {
            invoke(connectivity, "registerDefaultNetworkCallback",
                    new Class<?>[]{ConnectivityManager.NetworkCallback.class}, networkCallback);
            networkRegistered = true;
            Thread.sleep(80L);
            Log.i(TAG, "C2_T06_NETWORK_CALLBACK_RETURN loop=" + loop
                    + " events=" + networkCallbackCount + " session=" + session);
        } finally {
            unregisterNetwork();
        }
        Log.i(TAG, "C2_T06_NETWORK_CALLBACK_CLEANUP loop=" + loop
                + " registered=" + networkRegistered + " session=" + session);
    }

    private void runSensor(int loop) throws Exception {
        if (sensors == null) sensors = (SensorManager) getSystemService("sensor");
        if (sensors == null) {
            if (loop == 1) Log.w(TAG, "C2_T06_SENSOR_UNSUPPORTED reason=service-null session=" + session);
            return;
        }
        List<Sensor> available = sensors.getSensorList(SensorManager.SENSOR_ALL);
        sensor = available == null || available.isEmpty() ? sensors.getDefaultSensor(1) : available.get(0);
        if (sensor == null) {
            Log.w(TAG, "C2_T06_SENSOR_UNSUPPORTED reason=empty-profile session=" + session);
            return;
        }
        sensorListener = new ProbeSensorListener();
        boolean registered = sensors.registerListener(sensorListener, sensor, 2);
        sensorRegistered = registered;
        if (registered) Thread.sleep(90L);
        boolean flushed = false;
        if (registered) flushed = sensors.flush(sensorListener);
        unregisterSensor();
        Log.i(TAG, "C2_T06_SENSOR_RETURN loop=" + loop + " registered=" + registered
                + " flushed=" + flushed + " events=" + sensorEventCount
                + " cleanupRegistered=" + sensorRegistered + " session=" + session);
        if (!registered || sensorEventCount < loop) {
            throw new IllegalStateException("SENSOR_EVENT_CONTRACT_FAILED loop=" + loop
                    + " registered=" + registered + " events=" + sensorEventCount);
        }
    }

    private void runBluetooth() {
        try {
            Class<?> type = Class.forName("android.bluetooth.BluetoothAdapter");
            Object adapter = type.getMethod("getDefaultAdapter").invoke(null);
            if (adapter == null) {
                Log.i(TAG, "C2_T06_BLUETOOTH_UNSUPPORTED reason=adapter-null session=" + session);
                return;
            }
            Object bonded = invokeNoArg(adapter, "getBondedDevices");
            Object discovering = invokeNoArg(adapter, "isDiscovering");
            Object start = invokeNoArg(adapter, "startDiscovery");
            Object cancel = invokeNoArg(adapter, "cancelDiscovery");
            Log.i(TAG, "C2_T06_BLUETOOTH_RETURN state=" + value(adapter, "getState")
                    + " nameHash=" + digest(text(value(adapter, "getName")))
                    + " addressHash=" + digest(text(value(adapter, "getAddress")))
                    + " bondedCount=" + (bonded instanceof Set<?> ? ((Set<?>) bonded).size() : 0)
                    + " discovering=" + String.valueOf(discovering)
                    + " startDiscovery=" + String.valueOf(start)
                    + " cancelDiscovery=" + String.valueOf(cancel) + " session=" + session);
            if (Boolean.FALSE.equals(start)) {
                Log.i(TAG, "C2_T06_BLUETOOTH_DISCOVERY_BOUNDARY status=NOT_SUPPORTED session=" + session);
            }
        } catch (Throwable error) {
            Log.i(TAG, "C2_T06_BLUETOOTH_UNSUPPORTED error="
                    + rootCause(error).getClass().getSimpleName() + " session=" + session);
        }
    }

    private void runMediaAndAudio() {
        try {
            audio = getSystemService("audio");
            if (audio == null) {
                Log.w(TAG, "C2_T06_AUDIO_UNSUPPORTED reason=service-null session=" + session);
            } else {
                Log.i(TAG, "C2_T06_AUDIO_RETURN mode=" + value(audio, "getMode")
                        + " ringerMode=" + value(audio, "getRingerMode")
                        + " speaker=" + value(audio, "isSpeakerphoneOn")
                        + " volume=" + value(audio, "getStreamVolume", 3)
                        + " maxVolume=" + value(audio, "getStreamMaxVolume", 3)
                        + " session=" + session);
                requestAudioFocus();
            }
            try {
                Object mediaSession = getSystemService("media_session");
                Object mediaRouter = getSystemService("media_router");
                Log.i(TAG, "C2_T06_MEDIA_RETURN sessionService="
                        + (mediaSession == null ? "null" : mediaSession.getClass().getName())
                        + " routerService=" + (mediaRouter == null ? "null" : mediaRouter.getClass().getName())
                        + " session=" + session);
            } catch (Throwable error) {
                Log.i(TAG, "C2_T06_MEDIA_RETURN boundary=NOT_SUPPORTED error="
                        + rootCause(error).getClass().getSimpleName() + " session=" + session);
            }
        } catch (Throwable error) {
            Log.w(TAG, "C2_T06_AUDIO_MEDIA_UNSUPPORTED error="
                    + rootCause(error).getClass().getSimpleName() + " session=" + session);
        } finally {
            abandonAudioFocus();
        }
    }

    private void requestAudioFocus() throws Exception {
        if (audio == null) return;
        for (Method method : audio.getClass().getMethods()) {
            if (!method.getName().equals("requestAudioFocus") || method.getParameterCount() != 3) continue;
            Class<?> listenerType = method.getParameterTypes()[0];
            if (!listenerType.isInterface()) continue;
            InvocationHandler handler = (proxy, called, args) -> null;
            audioFocusListener = Proxy.newProxyInstance(listenerType.getClassLoader(),
                    new Class<?>[]{listenerType}, handler);
            method.setAccessible(true);
            Object result = method.invoke(audio, audioFocusListener, 3, 1);
            focusHeld = true;
            Log.i(TAG, "C2_T06_AUDIO_FOCUS_RETURN result=" + String.valueOf(result)
                    + " session=" + session);
            return;
        }
        Log.i(TAG, "C2_T06_AUDIO_FOCUS_BOUNDARY status=NOT_SUPPORTED session=" + session);
    }

    private void abandonAudioFocus() {
        if (!focusHeld || audio == null || audioFocusListener == null) return;
        try {
            for (Method method : audio.getClass().getMethods()) {
                if (!method.getName().equals("abandonAudioFocus") || method.getParameterCount() != 1) continue;
                method.setAccessible(true);
                method.invoke(audio, audioFocusListener);
                break;
            }
        } catch (Throwable error) {
            Log.w(TAG, "C2_T06_AUDIO_FOCUS_RELEASE_ERROR", error);
        } finally {
            focusHeld = false;
            audioFocusListener = null;
        }
    }

    private void runDnsAndVpn() {
        runDns();
        Object vpn = getSystemService("vpn_management");
        if (vpn == null) vpn = getSystemService("vpn");
        if (vpn == null) {
            Log.i(TAG, "C2_T06_VPN_BOUNDARY status=NOT_SUPPORTED reason=service-null session=" + session);
        } else {
            Log.i(TAG, "C2_T06_VPN_RETURN service=" + vpn.getClass().getName()
                    + " state=" + value(vpn, "getState") + " session=" + session);
        }
    }

    private void runDns() {
        try {
            Class<?> resolverType = Class.forName("android.net.DnsResolver");
            Object resolver = resolverType.getMethod("getInstance").invoke(null);
            Method query = null;
            for (Method candidate : resolverType.getMethods()) {
                if (candidate.getName().equals("query") && candidate.getParameterCount() >= 5) {
                    query = candidate;
                    break;
                }
            }
            if (query == null) throw new NoSuchMethodException("DnsResolver.query");
            Object[] arguments = new Object[query.getParameterCount()];
            final int[] callbacks = {0};
            for (int index = 0; index < arguments.length; index++) {
                Class<?> type = query.getParameterTypes()[index];
                if (type == String.class) arguments[index] = "sandbox.invalid";
                else if (type == int.class || type == Integer.class) arguments[index] = 0;
                else if (Executor.class.isAssignableFrom(type)) arguments[index] = (Executor) Runnable::run;
                else if (type.isInterface() && type.getName().toLowerCase().contains("callback")) {
                    arguments[index] = Proxy.newProxyInstance(type.getClassLoader(),
                            new Class<?>[]{type}, (proxy, method, values) -> {
                                callbacks[0]++;
                                Log.i(TAG, "C2_T06_DNS_CALLBACK method=" + method.getName()
                                        + " count=" + callbacks[0] + " session=" + session);
                                return null;
                            });
                } else arguments[index] = null;
            }
            query.setAccessible(true);
            query.invoke(resolver, arguments);
            Log.i(TAG, "C2_T06_DNS_RETURN callbacks=" + callbacks[0] + " session=" + session);
        } catch (Throwable error) {
            Log.i(TAG, "C2_T06_DNS_BOUNDARY status=NOT_SUPPORTED error="
                    + rootCause(error).getClass().getSimpleName() + " session=" + session);
        }
    }

    private void runPermissionNegative() {
        int result = checkSelfPermission("android.permission.READ_PHONE_STATE");
        Log.i(TAG, "C2_T06_PERMISSION_NEGATIVE permission=READ_PHONE_STATE check=" + result
                + " expected=" + android.content.pm.PackageManager.PERMISSION_DENIED
                + " session=" + session);
    }

    private void cleanup(String reason) {
        unregisterNetwork();
        unregisterSensor();
        if (telephonyRegistered && telephony != null && telephonyCallback != null) {
            try { invoke(telephony, "unregisterTelephonyCallback",
                    new Class<?>[]{TelephonyCallback.class}, telephonyCallback); }
            catch (Throwable ignored) { }
        }
        telephonyRegistered = false;
        abandonAudioFocus();
        Log.i(TAG, "C2_T06_CLEANUP reason=" + reason + " networkRegistered=" + networkRegistered
                + " sensorRegistered=" + sensorRegistered + " telephonyRegistered="
                + telephonyRegistered + " focusHeld=" + focusHeld + " session=" + session);
    }

    private void unregisterNetwork() {
        if (!networkRegistered || connectivity == null || networkCallback == null) return;
        try { invoke(connectivity, "unregisterNetworkCallback",
                new Class<?>[]{ConnectivityManager.NetworkCallback.class}, networkCallback); }
        catch (Throwable ignored) { }
        finally { networkRegistered = false; }
    }

    private void unregisterSensor() {
        if (!sensorRegistered || sensors == null || sensorListener == null) return;
        try { invoke(sensors, "unregisterListener",
                new Class<?>[]{SensorEventListener.class}, sensorListener); }
        catch (Throwable ignored) { }
        finally { sensorRegistered = false; }
    }

    private static Object value(Object target, String method, Object... arguments) {
        if (target == null) return null;
        try {
            for (Method candidate : target.getClass().getMethods()) {
                if (candidate.getName().equals(method)
                        && candidate.getParameterCount() == arguments.length) {
                    candidate.setAccessible(true);
                    return candidate.invoke(target, arguments);
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object invokeNoArg(Object target, String method) {
        if (target == null) return null;
        try { return invoke(target, method, new Class<?>[0]); }
        catch (Throwable ignored) { return null; }
    }

    private static Object invoke(Object target, String method, Class<?>[] types, Object... arguments)
            throws Exception {
        if (target == null) throw new IllegalStateException("target-null:" + method);
        Method candidate = target.getClass().getMethod(method, types);
        candidate.setAccessible(true);
        try { return candidate.invoke(target, arguments); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static int safeInt(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(text(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (Exception error) { return "digest-error"; }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        cleanup("activity-destroy");
        super.onDestroy();
    }

    public final class TelephonyProbeCallback extends TelephonyCallback
            implements TelephonyCallback.CellInfoListener {
        @Override public void onCellInfoChanged(List<CellInfo> cells) {
            telephonyCallbackCount++;
            Log.i(TAG, "C2_T06_TELEPHONY_CALLBACK event=CELL_INFO count="
                    + (cells == null ? 0 : cells.size()) + " session=" + session);
        }
    }

    public final class ProbeNetworkCallback extends ConnectivityManager.NetworkCallback {
        @Override public void onAvailable(Network network) {
            networkCallbackCount++;
            Log.i(TAG, "C2_T06_NETWORK_CALLBACK event=AVAILABLE count="
                    + networkCallbackCount + " session=" + session);
        }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            networkCallbackCount++;
            Log.i(TAG, "C2_T06_NETWORK_CALLBACK event=CAPABILITIES count="
                    + networkCallbackCount + " session=" + session);
        }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            networkCallbackCount++;
            Log.i(TAG, "C2_T06_NETWORK_CALLBACK event=LINK_PROPERTIES count="
                    + networkCallbackCount + " session=" + session);
        }
        @Override public void onBlockedStatusChanged(Network network, boolean blocked) {
            networkCallbackCount++;
            Log.i(TAG, "C2_T06_NETWORK_CALLBACK event=BLOCKED:" + blocked + " count="
                    + networkCallbackCount + " session=" + session);
        }
    }

    public final class ProbeSensorListener implements SensorEventListener {
        public void onSensorChanged(SensorEvent event) {
            sensorEventCount++;
            Log.i(TAG, "C2_T06_SENSOR_CALLBACK event=CHANGED count=" + sensorEventCount
                    + " valueCount=" + (event == null || event.values == null ? 0 : event.values.length)
                    + " session=" + session);
        }
        public void onAccuracyChanged(Sensor changed, int accuracy) { }
        public void onFlushCompleted(Sensor changed) {
            Log.i(TAG, "C2_T06_SENSOR_CALLBACK event=FLUSH_COMPLETED session=" + session);
        }
    }
}
