package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.runtime.guest.GuestRuntimeEnvironment;
import com.warden.controlledsandbox.runtime.guest.GuestActivityInstrumentation;
import com.warden.controlledsandbox.contract.VirtualComponentSnapshot;
import com.warden.controlledsandbox.framework.identity.VirtualPackageMetadata;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Version-gated, audited and rollback-safe bridge for the remaining private Activity fields. */
public final class ActivityFieldBridge {
    private static final int MIN_API = 26;
    private static final int MAX_AUDITED_API = 36;
    private static final List<String> HOST_FIELDS = List.of(
            "mToken", "mMainThread", "mInstrumentation", "mActivityInfo",
            "mFragments");
    private static final List<String> OPTIONAL_HOST_FIELDS = List.of("mCurrentConfig");

    private ActivityFieldBridge() { }

    static IBinder hostToken(Activity activity) {
        try {
            Field field = requireField(activity.getClass(), "mToken");
            field.setAccessible(true);
            Object value = field.get(activity);
            if (!(value instanceof IBinder token)) {
                throw new IllegalStateException("ACTIVITY_FRAMEWORK_TOKEN_INVALID");
            }
            return token;
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_FRAMEWORK_TOKEN_READ_FAILED", error);
        }
    }

    static BridgeReport install(Activity host, Activity guest,
                                GuestRuntimeEnvironment.Session session, String componentClass,
                                int callerTaskId) {
        int api = Build.VERSION.SDK_INT;
        if (api < MIN_API || api > MAX_AUDITED_API) {
            throw new IllegalStateException("UNSUPPORTED_ACTIVITY_BRIDGE_API:" + api);
        }
        LinkedHashMap<String, Object> direct = new LinkedHashMap<>();
        // Full Activity.attach() temporarily uses the attached Stub as framework transport.
        // Replace ContextWrapper.mBase before Guest code observes the Activity so identity and
        // storage calls terminate at the Guest Context rather than at the Host Stub.
        direct.put("mBase", session.context());
        // Activity.attach() constructs ContextThemeWrapper's cached Theme while the Stub is
        // still the temporary base. Replace that cache as well; otherwise AndroidX resolves
        // Host drawable/style IDs through Guest Resources during onCreate.
        direct.put("mTheme", session.context().getTheme());
        // ContextThemeWrapper also caches Resources independently of mBase. Full attach() has
        // initialized that cache from the temporary Stub, so project it together with mTheme;
        // otherwise Guest resource IDs (for example AppCompat vector drawables) are looked up
        // in the Host APK and fail with Resources.NotFoundException.
        direct.put("mResources", session.context().getResources());
        direct.put("mApplication", session.application());
        direct.put("mInstrumentation", new GuestActivityInstrumentation(session.context(), callerTaskId));
        Intent guestIntent = new Intent(host.getIntent());
        guestIntent.setComponent(new ComponentName(session.spec().packageName, componentClass));
        direct.put("mIntent", guestIntent);
        direct.put("mComponent", new ComponentName(session.spec().packageName, componentClass));
        direct.put("mTitle", componentClass.substring(componentClass.lastIndexOf('.') + 1));
        ActivityInfo projectedInfo = guestActivityInfo(host, session, componentClass);
        direct.put("mActivityInfo", projectedInfo);
        BridgeReport report = installFields(host, guest, HOST_FIELDS, OPTIONAL_HOST_FIELDS, direct, api);
        applyGuestTheme(guest, session, componentClass);
        return report;
    }

    /**
     * Projects Guest identity onto an Activity that was instantiated by the real
     * ActivityThread/Instrumentation path.  Unlike the legacy host-trampoline bridge this
     * method does not copy a host Activity's token, window or Fragment controller: ActivityThread
     * has already attached those framework-owned objects to the Guest instance.
     */
    public static BridgeReport installGuest(Activity guest,
                                            GuestRuntimeEnvironment.Session session,
                                            String componentClass, Intent intent,
                                            int callerTaskId) {
        if (guest == null) throw new IllegalArgumentException("guest is required");
        if (session == null) throw new IllegalArgumentException("session is required");
        if (componentClass == null || componentClass.trim().isEmpty()) {
            throw new IllegalArgumentException("componentClass is required");
        }
        int api = Build.VERSION.SDK_INT;
        if (api < MIN_API || api > MAX_AUDITED_API) {
            throw new IllegalStateException("UNSUPPORTED_ACTIVITY_BRIDGE_API:" + api);
        }
        Intent guestIntent = intent == null ? new Intent() : new Intent(intent);
        guestIntent.setComponent(new ComponentName(session.spec().packageName, componentClass));
        LinkedHashMap<String, Object> direct = new LinkedHashMap<>();
        direct.put("mBase", session.context());
        direct.put("mTheme", session.context().getTheme());
        direct.put("mResources", session.context().getResources());
        direct.put("mApplication", session.application());
        direct.put("mIntent", guestIntent);
        direct.put("mComponent", new ComponentName(session.spec().packageName, componentClass));
        direct.put("mTitle", componentClass.substring(componentClass.lastIndexOf('.') + 1));
        direct.put("mActivityInfo", guestActivityInfo(session, componentClass));
        BridgeReport report = installFields(guest, guest, List.of(), OPTIONAL_HOST_FIELDS,
                direct, api);
        applyGuestTheme(guest, session, componentClass);
        return report;
    }

    /**
     * Projects a Guest launch at the last client-side transaction boundary before
     * ActivityThread creates its ActivityClientRecord.  On Android 9+ the launch is delivered
     * as a ClientTransaction through ActivityThread.H (message 159).  At that point the
     * LaunchActivityItem already contains the host Stub ActivityInfo, but mActivities is still
     * empty; trying to patch the record from Instrumentation.newActivity() is therefore too
     * late for persistableMode and too early for the record to exist.  Rewriting the transaction
     * item is the same ownership boundary used by mature virtualizers: Android still performs
     * performLaunchActivity(), attach(), callback selection and task bookkeeping, while CAS
     * supplies the virtual package contract consumed by those framework paths.
     */
    public static boolean projectFrameworkLaunchTransaction(Object activityThread,
                                                             android.os.Message message,
                                                             GuestRuntimeEnvironment.Session session) {
        if (message == null || message.what != 159 || session == null || message.obj == null) {
            return false;
        }
        try {
            Iterable<?> callbacks = transactionCallbacks(message.obj);
            for (Object callback : callbacks) {
                if (callback == null || !callback.getClass().getName().endsWith("LaunchActivityItem")) {
                    continue;
                }
                Field intentField = findField(callback.getClass(), "mIntent");
                Field infoField = findField(callback.getClass(), "mInfo");
                if (intentField == null || infoField == null) {
                    throw new IllegalStateException("FRAMEWORK_LAUNCH_ITEM_FIELDS_UNAVAILABLE");
                }
                intentField.setAccessible(true);
                infoField.setAccessible(true);
                Object rawIntent = intentField.get(callback);
                if (!(rawIntent instanceof Intent hostIntent)) continue;
                String routeToken = hostIntent.getStringExtra(RuntimeKeys.ROUTE_TOKEN);
                String sessionId = hostIntent.getStringExtra(RuntimeKeys.SESSION_ID);
                long generation = hostIntent.getLongExtra(RuntimeKeys.GENERATION, 0L);
                if (routeToken == null || routeToken.trim().isEmpty()
                        || !session.spec().sessionId.equals(sessionId)
                        || session.spec().generation != generation) {
                    continue;
                }
                String componentClass = hostIntent.getStringExtra(RuntimeKeys.COMPONENT_CLASS);
                if (componentClass == null || componentClass.trim().isEmpty()) {
                    throw new IllegalStateException("FRAMEWORK_LAUNCH_COMPONENT_MISSING");
                }
                ActivityInfo projected = guestActivityInfo(session, componentClass);
                infoField.set(callback, projected);
                // Keep route authority and all encoded Guest extras, but make the transaction's
                // component agree with the ActivityInfo that ActivityThread is about to consume.
                Intent guestIntent = new Intent(hostIntent);
                guestIntent.setComponent(new ComponentName(session.spec().packageName,
                        componentClass));
                intentField.set(callback, guestIntent);
                projectLaunchingRecord(activityThread, message.obj, hostIntent, guestIntent,
                        projected, session);
                int persistableMode = -1;
                Field persistableField = findField(projected.getClass(), "persistableMode");
                if (persistableField != null) {
                    persistableField.setAccessible(true);
                    Object value = persistableField.get(projected);
                    if (value instanceof Number number) persistableMode = number.intValue();
                }
                android.util.Log.i("CS_FRAMEWORK_ACTIVITY",
                        "PRELAUNCH_TRANSACTION_PROJECTED route=" + routeToken
                                + " component=" + componentClass);
                android.util.Log.i("CS_FRAMEWORK_ACTIVITY",
                        "PRELAUNCH_TRANSACTION_INFO component=" + componentClass
                                + " persistableMode=" + persistableMode
                                + " infoIdentity=" + System.identityHashCode(projected)
                                + " infoClass=" + projected.getClass().getName());
                return true;
            }
            return false;
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("FRAMEWORK_LAUNCH_TRANSACTION_PROJECTION_FAILED", error);
        }
    }

    /**
     * On Android 9–14, LaunchActivityItem.preExecute() creates the ActivityClientRecord before
     * EXECUTE_TRANSACTION reaches the main Handler and stores it in mLaunchingActivities.
     * ActivityThread then consumes mLaunchingActivities[token], not the item fields again.
     * Keep both objects coherent so the platform's own ActivityClientRecord receives the Guest
     * metadata and Guest LoadedApk before performLaunchActivity().
     *
     * <p>On Android 15 (API 35+), mLaunchingActivities was removed from ActivityThread.  The
     * pre-launch record no longer exists; only the LaunchActivityItem fields patched in
     * projectFrameworkLaunchTransaction() carry the Guest contract to performLaunchActivity().
     * This method returns early without error in that case.
     */
    private static void projectLaunchingRecord(Object activityThread, Object transaction,
                                               Intent hostIntent, Intent guestIntent,
                                               ActivityInfo projected,
                                               GuestRuntimeEnvironment.Session session)
            throws Exception {
        if (activityThread == null) {
            throw new IllegalStateException("ACTIVITY_THREAD_UNAVAILABLE");
        }
        Object token = null;
        Method tokenMethod = findMethod(transaction.getClass(), "getActivityToken");
        if (tokenMethod != null) token = tokenMethod.invoke(transaction);
        if (token == null) {
            Field tokenField = findField(transaction.getClass(), "mActivityToken");
            if (tokenField != null) {
                tokenField.setAccessible(true);
                token = tokenField.get(transaction);
            }
        }
        if (token == null) throw new IllegalStateException("FRAMEWORK_LAUNCH_TOKEN_NULL");

        Object record = null;
        Field launchingField = findField(activityThread.getClass(), "mLaunchingActivities");
        if (launchingField != null) {
            launchingField.setAccessible(true);
            Object launching = launchingField.get(activityThread);
            if (launching != null) {
                Method get = findMethod(launching.getClass(), "get", Object.class);
                if (get != null) record = get.invoke(launching, token);
            }
        }
        if (record == null) {
            Method getLaunching = findMethod(activityThread.getClass(), "getLaunchingActivity",
                    IBinder.class);
            if (getLaunching != null && token instanceof IBinder binder) {
                record = getLaunching.invoke(activityThread, binder);
            }
        }
        if (record == null && launchingField != null) {
            // A few OEM ActivityThread variants do not expose the launching map through the
            // public ClientTransactionHandler method.  Route identity is still authoritative,
            // so scan its values before failing closed.
            launchingField.setAccessible(true);
            Object launching = launchingField.get(activityThread);
            if (launching instanceof Map<?, ?> map) {
                for (Object candidate : map.values()) {
                    Field intentField = candidate == null ? null
                            : findField(candidate.getClass(), "intent");
                    if (intentField == null) continue;
                    intentField.setAccessible(true);
                    Object value = intentField.get(candidate);
                    if (value instanceof Intent intent
                            && hostIntent.getStringExtra(RuntimeKeys.ROUTE_TOKEN)
                            .equals(intent.getStringExtra(RuntimeKeys.ROUTE_TOKEN))) {
                        record = candidate;
                        break;
                    }
                }
            }
        }
        if (record == null) {
            if (launchingField == null) {
                // Android 15 (API 35+) removed ActivityThread.mLaunchingActivities and its
                // companion getLaunchingActivity() accessor.  The framework no longer pre-creates
                // an ActivityClientRecord before EXECUTE_TRANSACTION; the LaunchActivityItem
                // fields already patched above carry the Guest contract into performLaunchActivity.
                android.util.Log.i("CS_FRAMEWORK_ACTIVITY",
                        "PRELAUNCH_RECORD_SKIPPED api=" + Build.VERSION.SDK_INT
                                + " token=" + token);
                return;
            }
            throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_LAUNCHING_NOT_FOUND");
        }
        Field infoField = findField(record.getClass(), "activityInfo");
        Field intentField = findField(record.getClass(), "intent");
        if (infoField == null || intentField == null) {
            throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_FIELDS_UNAVAILABLE");
        }
        infoField.setAccessible(true);
        intentField.setAccessible(true);
        infoField.set(record, projected);
        intentField.set(record, new Intent(guestIntent));
        if (session.loadedApkProjection() != null) {
            Field packageInfoField = findField(record.getClass(), "packageInfo");
            if (packageInfoField != null) {
                packageInfoField.setAccessible(true);
                packageInfoField.set(record, session.loadedApkProjection());
            }
        }
        android.util.Log.i("CS_FRAMEWORK_ACTIVITY",
                "PRELAUNCH_RECORD_PROJECTED token=" + token
                        + " infoIdentity=" + System.identityHashCode(projected));
    }

    private static Iterable<?> transactionCallbacks(Object transaction) throws Exception {
        ArrayList<Object> items = new ArrayList<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        appendTransactionItems(items, seen, transaction, "getTransactionItems");
        appendTransactionItems(items, seen, transaction, "getCallbacks");
        appendTransactionField(items, seen, transaction, "mTransactionItems");
        appendTransactionField(items, seen, transaction, "mActivityCallbacks");
        return items;
    }

    private static void appendTransactionItems(ArrayList<Object> target,
                                               IdentityHashMap<Object, Boolean> seen,
                                               Object transaction, String methodName)
            throws Exception {
        Method method = findMethod(transaction.getClass(), methodName);
        if (method == null) return;
        Object value = method.invoke(transaction);
        appendTransactionValue(target, seen, value);
    }

    private static void appendTransactionField(ArrayList<Object> target,
                                               IdentityHashMap<Object, Boolean> seen,
                                               Object transaction, String fieldName)
            throws IllegalAccessException {
        Field field = findField(transaction.getClass(), fieldName);
        if (field == null) return;
        field.setAccessible(true);
        appendTransactionValue(target, seen, field.get(transaction));
    }

    private static void appendTransactionValue(ArrayList<Object> target,
                                               IdentityHashMap<Object, Boolean> seen,
                                               Object value) {
        if (!(value instanceof Iterable<?> iterable)) return;
        for (Object item : iterable) {
            if (item != null && seen.put(item, Boolean.TRUE) == null) target.add(item);
        }
    }

    /**
     * Projects the Guest ActivityInfo before ActivityThread chooses the lifecycle callback
     * overload.  performLaunchActivity calls Instrumentation.newActivity first and then uses
     * ActivityClientRecord.info.persistableMode to choose the two- or three-argument onCreate
     * path.  Waiting until callActivityOnCreate is therefore already one framework decision too
     * late for persistable activities and task metadata.
     */
    public static Bundle projectPendingFrameworkRecord(Object activityThread,
                                                        String routeToken,
                                                        GuestRuntimeEnvironment.Session session,
                                                        String componentClass,
                                                        String hostClassName,
                                                        Intent guestIntent) {
        if (activityThread == null || routeToken == null || routeToken.trim().isEmpty()) {
            throw new IllegalArgumentException("ActivityThread/routeToken is required");
        }
        try {
            Field activitiesField = findField(activityThread.getClass(), "mActivities");
            if (activitiesField == null) {
                throw new IllegalStateException("ACTIVITY_CLIENT_RECORDS_UNAVAILABLE");
            }
            activitiesField.setAccessible(true);
            Object records = activitiesField.get(activityThread);
            if (!(records instanceof Map<?, ?> map)) {
                throw new IllegalStateException("ACTIVITY_CLIENT_RECORDS_NOT_MAP");
            }
            Object pendingCandidate = null;
            int pendingCandidateCount = 0;
            StringBuilder scan = new StringBuilder("route=").append(routeToken)
                    .append(" host=").append(hostClassName).append(" count=").append(map.size());
            for (Object record : map.values()) {
                if (record == null) continue;
                Field intentField = findField(record.getClass(), "intent");
                if (intentField == null) continue;
                intentField.setAccessible(true);
                Object value = intentField.get(record);
                String route = "";
                String intentComponent = "";
                if (value instanceof Intent current) {
                    route = current.getStringExtra(RuntimeKeys.ROUTE_TOKEN);
                    intentComponent = String.valueOf(current.getComponent());
                }
                scan.append(" [route=").append(route).append(" component=")
                        .append(intentComponent).append(" pending=")
                        .append(isPendingStubRecord(record, hostClassName)).append("]");
                if (value instanceof Intent current
                        && routeToken.equals(current.getStringExtra(RuntimeKeys.ROUTE_TOKEN))) {
                    return projectRecord(record, session, componentClass, guestIntent,
                            intentField, "route");
                }
                // Some Android 12 transaction paths copy the launch Intent into the
                // ActivityClientRecord after Instrumentation.newActivity() is entered, so the
                // route extra is not visible yet. The record is still uniquely identifiable by
                // its Stub ActivityInfo and its not-yet-created Activity instance.
                if (isPendingStubRecord(record, hostClassName)) {
                    pendingCandidate = record;
                    pendingCandidateCount++;
                }
            }
            if (pendingCandidateCount == 1) {
                Field intentField = findField(pendingCandidate.getClass(), "intent");
                intentField.setAccessible(true);
                return projectRecord(pendingCandidate, session, componentClass, guestIntent,
                        intentField, "stub-fallback");
            }
            android.util.Log.e("CS_FRAMEWORK_ACTIVITY", "PRELAUNCH_SCAN_FAILED " + scan);
            throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_PRELAUNCH_NOT_FOUND");
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_PRELAUNCH_PROJECTION_FAILED", error);
        }
    }

    private static boolean isPendingStubRecord(Object record, String hostClassName) {
        try {
            Field activityField = findField(record.getClass(), "activity");
            if (activityField != null) {
                activityField.setAccessible(true);
                if (activityField.get(record) != null) return false;
            }
            Field infoField = findField(record.getClass(), "activityInfo");
            if (infoField == null) return false;
            infoField.setAccessible(true);
            Object info = infoField.get(record);
            if (!(info instanceof ActivityInfo activityInfo)) return false;
            return hostClassName == null || hostClassName.equals(activityInfo.name);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return false;
        }
    }

    private static Bundle projectRecord(Object record,
                                        GuestRuntimeEnvironment.Session session,
                                        String componentClass,
                                        Intent guestIntent,
                                        Field intentField,
                                        String source) throws IllegalAccessException {
        Field infoField = findField(record.getClass(), "activityInfo");
        if (infoField == null) {
            throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_INFO_UNAVAILABLE");
        }
        infoField.setAccessible(true);
        infoField.set(record, guestActivityInfo(session, componentClass));
        intentField.set(record, guestIntent == null ? new Intent() : new Intent(guestIntent));
        Bundle evidence = new Bundle();
        evidence.putBoolean("frameworkRecordPrelaunchProjected", true);
        evidence.putString("frameworkRecordProjectionSource", source);
        evidence.putString("frameworkComponentClass", componentClass);
        evidence.putString("frameworkPackageName", session.spec().packageName);
        return evidence;
    }

    /**
     * Updates the ActivityClientRecord after Instrumentation has created the Guest object.
     * ActivityThread owns the record; CAS only validates the token and projects the Guest
     * component metadata into it. This is the ownership boundary used by the RD trace.
     */
    public static Bundle promoteFrameworkRecord(Activity guest,
                                                 GuestRuntimeEnvironment.Session session,
                                                 String componentClass, Intent intent) {
        if (guest == null || session == null) throw new IllegalArgumentException("Activity/session required");
        Bundle evidence = frameworkEvidence(guest);
        try {
            Object record = findActivityClientRecord(guest);
            if (record == null) throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_UNAVAILABLE");
            Field recordClassField = findField(record.getClass(), "activityInfo");
            if (recordClassField != null) {
                recordClassField.setAccessible(true);
                recordClassField.set(record, guestActivityInfo(session, componentClass));
            }
            Field intentField = findField(record.getClass(), "intent");
            if (intentField != null) {
                intentField.setAccessible(true);
                intentField.set(record, intent == null ? new Intent() : new Intent(intent));
            }
            if (session.loadedApkProjection() != null) {
                Field packageInfoField = findField(record.getClass(), "packageInfo");
                if (packageInfoField != null) {
                    packageInfoField.setAccessible(true);
                    packageInfoField.set(record, session.loadedApkProjection());
                }
            }
            Field activityField = findField(record.getClass(), "activity");
            if (activityField != null) {
                activityField.setAccessible(true);
                Object current = activityField.get(record);
                if (current != guest) {
                    throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_ACTIVITY_MISMATCH");
                }
            }
            // Activity.attach() accepts ActivityClientRecord's preserved window. A Stub route
            // can arrive with that slot populated by a previous host trampoline, while the
            // newly instantiated Guest has never registered that DecorView with WMG. Clear the
            // preserved record so ActivityThread.handleResumeActivity obtains the Guest window
            // through its normal r.window == null branch and owns addView/removeView symmetrically.
            clearStaleFrameworkWindow(record, guest);
            evidence.putBoolean("frameworkRecordPromoted", true);
            evidence.putString("frameworkComponentClass", componentClass);
            evidence.putString("frameworkPackageName", session.spec().packageName);
            return evidence;
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_PROMOTION_FAILED", error);
        }
    }

    /**
     * Updates the framework-owned Activity and ActivityClientRecord with the decoded Guest
     * Intent before the application callback observes {@code getIntent()}.  ATMS delivers the
     * host Stub transport Intent for a reused singleTop/singleTask Activity; leaving that object
     * in place leaks the trampoline component and makes onNewIntent semantically different from a
     * normal Guest launch.
     */
    public static void projectFrameworkNewIntent(Activity activity,
                                                 GuestRuntimeEnvironment.Session session,
                                                 String componentClass, Intent intent) {
        if (activity == null || session == null) {
            throw new IllegalArgumentException("Activity/session required");
        }
        Intent guestIntent = intent == null ? new Intent() : new Intent(intent);
        guestIntent.setComponent(new ComponentName(session.spec().packageName, componentClass));
        try {
            setOptionalObject(activity, "mIntent", guestIntent);
            setOptionalObject(activity, "mComponent",
                    new ComponentName(session.spec().packageName, componentClass));
            Object record = findActivityClientRecord(activity);
            if (record == null) throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_UNAVAILABLE");
            Field recordIntent = findField(record.getClass(), "intent");
            if (recordIntent != null) {
                recordIntent.setAccessible(true);
                recordIntent.set(record, new Intent(guestIntent));
            }
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_NEW_INTENT_PROJECTION_FAILED", error);
        }
    }

    /** Returns framework-owned ActivityClientRecord evidence for the Activity token. */
    public static Bundle frameworkEvidence(Activity activity) {
        if (activity == null) throw new IllegalArgumentException("activity is required");
        try {
            Object record = findActivityClientRecord(activity);
            if (record == null) throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_NOT_FOUND");
            Field tokenField = findField(activity.getClass(), "mToken");
            tokenField.setAccessible(true);
            Object token = tokenField.get(activity);
            Field recordActivity = findField(record.getClass(), "activity");
            recordActivity.setAccessible(true);
            if (recordActivity.get(record) != activity) {
                throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_ACTIVITY_MISMATCH");
            }
            Bundle evidence = new Bundle();
            evidence.putString("frameworkRecordClass", record.getClass().getName());
            evidence.putString("frameworkActivityClass", activity.getClass().getName());
            evidence.putString("frameworkToken", String.valueOf(token));
            Field info = findField(record.getClass(), "activityInfo");
            if (info != null) {
                info.setAccessible(true);
                Object value = info.get(record);
                if (value instanceof ActivityInfo activityInfo) {
                    evidence.putString("frameworkRecordPackage", activityInfo.packageName);
                    evidence.putString("frameworkRecordName", activityInfo.name);
                }
            }
            android.view.Window window = activity.getWindow();
            android.view.View decor = window == null ? null : window.getDecorView();
            evidence.putBoolean("windowAttached", decor != null && decor.isAttachedToWindow());
            evidence.putBoolean("windowCreated", window != null);
            return evidence;
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_FRAMEWORK_EVIDENCE_FAILED", error);
        }
    }

    /** Returns the ActivityInfo persistable mode attached by ActivityThread, or -1 if unavailable. */
    public static int frameworkPersistableMode(Activity activity) {
        if (activity == null) return -1;
        try {
            Field infoField = findField(activity.getClass(), "mActivityInfo");
            if (infoField == null) return -1;
            infoField.setAccessible(true);
            Object info = infoField.get(activity);
            if (!(info instanceof ActivityInfo activityInfo)) return -1;
            Field persistableField = findField(activityInfo.getClass(), "persistableMode");
            if (persistableField == null) return -1;
            persistableField.setAccessible(true);
            Object value = persistableField.get(activityInfo);
            return value instanceof Number number ? number.intValue() : -1;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return -1;
        }
    }

    /** Returns the identity hash of Activity.mActivityInfo for transaction correlation. */
    public static int frameworkActivityInfoIdentity(Activity activity) {
        if (activity == null) return 0;
        try {
            Field infoField = findField(activity.getClass(), "mActivityInfo");
            if (infoField == null) return 0;
            infoField.setAccessible(true);
            Object info = infoField.get(activity);
            return info == null ? 0 : System.identityHashCode(info);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return 0;
        }
    }

    private static Object findActivityClientRecord(Activity activity) throws Exception {
        Class<?> activityThreadType = Class.forName("android.app.ActivityThread");
        Field currentField = findField(activityThreadType, "sCurrentActivityThread");
        if (currentField == null) throw new NoSuchFieldException("ActivityThread.sCurrentActivityThread");
        currentField.setAccessible(true);
        Object thread = currentField.get(null);
        if (thread == null) throw new IllegalStateException("ACTIVITY_THREAD_CURRENT_NULL");
        Field activitiesField = findField(thread.getClass(), "mActivities");
        if (activitiesField == null) throw new NoSuchFieldException("ActivityThread.mActivities");
        activitiesField.setAccessible(true);
        Object activities = activitiesField.get(thread);
        if (activities == null) throw new IllegalStateException("ACTIVITY_THREAD_ACTIVITIES_NULL");
        Field tokenField = findField(activity.getClass(), "mToken");
        if (tokenField == null) throw new NoSuchFieldException("Activity.mToken");
        tokenField.setAccessible(true);
        Object token = tokenField.get(activity);
        if (token == null) throw new IllegalStateException("ACTIVITY_FRAMEWORK_TOKEN_NULL");
        Method get = activities.getClass().getMethod("get", Object.class);
        Object record = get.invoke(activities, token);
        if (record == null) throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_NOT_FOUND");
        Field recordActivity = findField(record.getClass(), "activity");
        if (recordActivity == null) throw new NoSuchFieldException("ActivityClientRecord.activity");
        recordActivity.setAccessible(true);
        if (recordActivity.get(record) != activity) {
            throw new IllegalStateException("ACTIVITY_CLIENT_RECORD_ACTIVITY_MISMATCH");
        }
        return record;
    }

    /**
     * Prevents ActivityThread from asking WindowManager to remove a DecorView that never made it
     * through addView (for example an Activity that starts another Activity from onCreate and is
     * hidden before its first visible frame). This only clears framework bookkeeping when the
     * current DecorView is demonstrably not attached or registered; visible Guest windows are
     * left entirely to Android's normal destroy path.
     */
    public static void repairFrameworkWindowBeforeDestroy(Activity activity) {
        if (activity == null) return;
        try {
            android.view.Window window = activity.getWindow();
            android.view.View decor = window == null ? null : window.getDecorView();
            WindowRegistration registration = windowRegistration(decor);
            if (decor == null || decor.isAttachedToWindow()
                    || registration != WindowRegistration.NOT_REGISTERED) return;
            setOptionalBoolean(activity, "mWindowAdded", false);
            setOptionalObject(activity, "mDecor", null);
            Class<?> threadType = Class.forName("android.app.ActivityThread");
            Field current = findField(threadType, "sCurrentActivityThread");
            if (current == null) return;
            current.setAccessible(true);
            Object thread = current.get(null);
            if (thread == null) return;
            Field activities = findField(thread.getClass(), "mActivities");
            activities.setAccessible(true);
            Object map = activities.get(thread);
            Field token = findField(activity.getClass(), "mToken");
            token.setAccessible(true);
            Object record = map.getClass().getMethod("get", Object.class).invoke(map, token.get(activity));
            if (record == null) return;
            setOptionalObject(record, "window", null);
            setOptionalBoolean(record, "mPreserveWindow", false);
            setOptionalObject(record, "mPendingRemoveWindow", null);
            setOptionalObject(record, "mPendingRemoveWindowManager", null);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_FRAMEWORK_ACTIVITY", "window destroy repair skipped", error);
        }
    }

    /**
     * Reconciles the ActivityThread record before {@code handleResumeActivity()} reaches its
     * layout-update branch.  Android's client code deliberately uses two independent markers:
     * {@code Activity.mWindowAdded} and {@code ActivityClientRecord.window}.  A task transition
     * or a host-side trampoline cleanup can remove the DecorView from WindowManagerGlobal while
     * leaving either marker set.  The next resume then calls updateViewLayout() for a root that
     * is no longer registered and the process dies with "View ... not attached to window
     * manager".  Clearing the stale record makes the real framework take its normal
     * {@code r.window == null -> addView()} path on the same main thread.
     *
     * <p>This method is intentionally limited to an unregistered, detached root with evidence
     * that the framework still believes a window exists.  A visible or registered Guest window
     * remains entirely under ActivityThread/WindowManager ownership.</p>
     */
    /**
     * True when ActivityThread has not yet published this Activity's window. {@code mDecor} is
     * not a publication signal: {@code setContentView} in onCreate creates it while
     * {@code mWindowAdded} and {@code ActivityClientRecord.window} stay unset until
     * {@code handleResumeActivity()} calls addView. Treating that DecorView as a stale root
     * caused a premature addView and a black screen ({@code windows=[]}, {@code reportedDrawn=false}).
     */
    static boolean firstResumeWithoutFrameworkWindow(boolean windowAdded, Object activityDecor,
                                                     Object recordWindow) {
        return !windowAdded && recordWindow == null;
    }

    public static Bundle repairFrameworkWindowBeforeResume(Activity activity) {
        Bundle evidence = new Bundle();
        if (activity == null) return evidence;
        Object record = null;
        try {
            record = findActivityClientRecord(activity);
            android.view.Window window = activity.getWindow();
            ensureFrameworkRecordIntent(record, activity);
            boolean windowAdded = optionalBoolean(activity, "mWindowAdded", false);
            Object activityDecor = optionalObject(activity, "mDecor");
            Object recordWindow = optionalObject(record, "window");
            boolean frameworkWindowPresent = !firstResumeWithoutFrameworkWindow(
                    windowAdded, activityDecor, recordWindow);
            evidence.putBoolean("resumeWindowAddedMarker", windowAdded);
            evidence.putBoolean("resumeFrameworkWindowPresent", frameworkWindowPresent);
            if (firstResumeWithoutFrameworkWindow(windowAdded, activityDecor, recordWindow)) {
                // First frame belongs to ActivityThread.handleResumeActivity() addView.
                setOptionalBoolean(record, "hideForNow", false);
                evidence.putBoolean("resumeFirstWindowDeferredToActivityThread", true);
                evidence.putString("resumeWindowRegistration", "FIRST_RESUME");
                android.util.Log.i("CS_FRAMEWORK_ACTIVITY",
                        "WINDOW_FIRST_RESUME_DEFER"
                                + " windowAdded=" + windowAdded
                                + " recordWindow=" + (recordWindow != null)
                                + " startedActivity=" + optionalBoolean(activity, "mStartedActivity", false)
                                + " visibleFromClient=" + optionalBoolean(activity, "mVisibleFromClient", true)
                                + " finished=" + activity.isFinishing()
                                + " activityClass=" + activity.getClass().getName());
                return evidence;
            }
            android.view.View decor = activityDecor instanceof android.view.View view
                    ? view
                    : (window == null ? null : window.getDecorView());
            boolean attached = decor != null && decor.isAttachedToWindow();
            WindowRegistration registration = windowRegistration(decor);
            boolean registered = registration == WindowRegistration.REGISTERED;
            evidence.putBoolean("resumeWindowAttached", attached);
            evidence.putBoolean("resumeWindowRegistered", registered);
            evidence.putString("resumeWindowRegistration", registration.name());
            if (attached || registration == WindowRegistration.REGISTERED) {
                // A previous recovery may have hidden this record while WMS was detaching the
                // old root. Once the root is healthy again, let ActivityThread publish it using
                // its normal visibility contract.
                setOptionalBoolean(record, "hideForNow", false);
            }
            if (attached || registration != WindowRegistration.NOT_REGISTERED
                    || !frameworkWindowPresent) {
                return evidence;
            }

            // Re-register the existing DecorView before ActivityThread resumes its own
            // bookkeeping. Clearing r.window/mDecor alone is unsafe on API 31/32: the Window
            // object still owns the detached DecorView, and handleResumeActivity() can select
            // that stale root again and call updateViewLayout() on a root absent from
            // WindowManagerGlobal. Re-adding the same root preserves the Guest view hierarchy
            // and lets ActivityThread continue through its normal already-added branch.
            android.view.WindowManager windowManager = activity.getWindowManager();
            if (window == null || windowManager == null || decor == null) {
                throw new IllegalStateException("FRAMEWORK_WINDOW_REATTACH_STATE_UNAVAILABLE");
            }
            setOptionalBoolean(activity, "mWindowAdded", false);
            setOptionalObject(activity, "mDecor", decor);
            android.view.WindowManager.LayoutParams layout = window.getAttributes();
            layout.type = android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
            // ViewManager owns this API on Android. WindowManagerImpl may inherit it through
            // the interface, so a declared-method walk on the concrete implementation misses
            // the actual contract. Use ViewGroup.LayoutParams (the platform signature) and
            // search interfaces as well as the concrete class.
            Method addView = findMethodIncludingInterfaces(windowManager.getClass(), "addView",
                    android.view.View.class, android.view.ViewGroup.LayoutParams.class);
            if (addView == null) throw new NoSuchMethodException("ViewManager.addView");
            addView.setAccessible(true);
            addView.invoke(windowManager, decor, layout);
            setOptionalBoolean(activity, "mWindowAdded", true);
            setOptionalObject(record, "window", window);
            setOptionalBoolean(record, "mPreserveWindow", false);
            setOptionalObject(record, "mPendingRemoveWindow", null);
            setOptionalObject(record, "mPendingRemoveWindowManager", null);
            evidence.putBoolean("resumeWindowRepaired", true);
            evidence.putBoolean("resumeWindowReattached", true);
            WindowRegistration afterRegistration = windowRegistration(decor);
            if (afterRegistration != WindowRegistration.REGISTERED) {
                // Reattach did not land in WindowManagerGlobal. Do not set hideForNow: that
                // suppresses handleResumeActivity's visible-frame path and leaves a black
                // screen. Clear stale markers so ActivityThread can take addView() itself.
                setOptionalBoolean(activity, "mWindowAdded", false);
                setOptionalObject(record, "window", null);
                setOptionalBoolean(record, "hideForNow", false);
                evidence.putBoolean("resumeWindowClearedForFrameworkAdd", true);
            } else {
                setOptionalBoolean(record, "hideForNow", false);
            }
            android.util.Log.i("CS_FRAMEWORK_ACTIVITY",
                    "WINDOW_REPAIR stage=RESUME attached=" + attached
                            + " registered=" + registered
                            + " windowAdded=" + windowAdded
                            + " recordWindow=" + (recordWindow != null)
                            + " action=REATTACH"
                            + " afterRegistration=" + afterRegistration.name()
                            + " activityClass=" + activity.getClass().getName());
            return evidence;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            try {
                // A failed reattach must not hide the Activity. Clear stale window markers so
                // ActivityThread can still addView on this resume.
                if (record != null) {
                    setOptionalBoolean(record, "hideForNow", false);
                    setOptionalBoolean(activity, "mWindowAdded", false);
                    setOptionalObject(record, "window", null);
                }
            } catch (Throwable ignored) {
                com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
            }
            evidence.putString("resumeWindowRepairError", error.getClass().getName());
            android.util.Log.w("CS_FRAMEWORK_ACTIVITY", "window resume repair skipped", error);
            return evidence;
        }
    }

    /**
     * Reference {@code ContextCompat.fix} + {@code IWindowSessionProxy.addToDisplay} contract
     * for the framework objects WMS actually reads. Guest-visible
     * {@code GuestContext.getOpPackageName()} stays the Guest package (C2-T02); only the
     * WindowManagerImpl {@code ContextImpl} and {@code LayoutParams.packageName} present the
     * Host identity that matches {@code Binder.getCallingUid()}.
     */
    public static void fixFrameworkWindowIdentity(Activity activity) {
        if (activity == null) return;
        try {
            HostWindowIdentity host = hostWindowIdentity(activity);
            if (host == null) return;
            patchWindowManagerContext(activity.getWindowManager(), host);
            android.view.Window window = activity.getWindow();
            if (window != null) {
                patchWindowManagerContext(window.getWindowManager(), host);
                applyHostPackageToLayoutParams(window.getAttributes(), host.packageName);
            }
            android.util.Log.i("CS_FRAMEWORK_ACTIVITY",
                    "WINDOW_IDENTITY_FIXED hostPkg=" + host.packageName
                            + " hostUid=" + host.uid
                            + " activityClass=" + activity.getClass().getName());
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_FRAMEWORK_ACTIVITY", "window identity fix skipped", error);
        }
    }

    /**
     * After {@code callActivityOnResume} returns, ActivityThread continues into
     * {@code handleResumeActivity}'s addView. If that path still leaves the DecorView
     * unregistered, publish it on the next main-loop turn so WMS attaches a real window
     * instead of a black, {@code windows=[]} ActivityRecord.
     */
    public static void ensureWindowPublishedAfterResume(Activity activity) {
        if (activity == null) return;
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        // ActivityThread.handleResumeActivity owns the normal first addView.  This is one
        // explicit post-resume observation/repair only; repeated delayed addView attempts made
        // the first failure non-deterministic and could mask a framework black-screen state.
        handler.post(() -> {
            logPostResumeWindowState(activity, "before_publish");
            publishWindowIfMissing(activity);
            logPostResumeWindowState(activity, "after_publish");
        });
    }

    /**
     * Captures the single post-resume boundary at which ActivityThread should already have
     * completed its normal addView/makeVisible path.  This is deliberately an observation only:
     * it does not change visibility, enqueue another callback, or attempt a second addView.
     * Keeping the Activity and ActivityClientRecord fields together is important because a
     * window can be present in WMS while the client-side record still says that the activity is
     * hidden or being destroyed.
     */
    private static void logPostResumeWindowState(Activity activity, String boundary) {
        try {
            android.view.Window window = activity.getWindow();
            android.view.View decor = window == null ? null : window.getDecorView();
            Object record = findActivityClientRecord(activity);
            Object thread = currentActivityThread();
            Object token = optionalObject(activity, "mToken");
            Object recordWindow = record == null ? null : optionalObject(record, "window");
            Object state = record == null ? null : optionalObject(record, "state");
            Object toBeDestroyed = thread == null
                    ? null : optionalObject(thread, "mActivitiesToBeDestroyed");
            boolean scheduledForDestroy = containsKey(toBeDestroyed, token);
            android.view.ViewParent parent = decor == null ? null : decor.getParent();
            Object root = decor == null ? null : invokeOptional(decor, "getViewRootImpl");
            String packageName = "";
            int type = -1;
            boolean layoutToken = false;
            if (window != null) {
                android.view.WindowManager.LayoutParams layout = window.getAttributes();
                if (layout != null) {
                    packageName = String.valueOf(layout.packageName);
                    type = layout.type;
                    layoutToken = layout.token != null;
                }
            }
            android.util.Log.i("CS_FRAMEWORK_ACTIVITY",
                    "WINDOW_POST_RESUME_STATE boundary=" + boundary
                            + " activityClass=" + activity.getClass().getName()
                            + " activityToken=" + String.valueOf(token)
                            + " recordWindow=" + (recordWindow != null)
                            + " recordState=" + String.valueOf(state)
                            + " preserveWindow=" + optionalBoolean(record, "mPreserveWindow", false)
                            + " hideForNow=" + optionalBoolean(record, "hideForNow", false)
                            + " scheduledForDestroy=" + scheduledForDestroy
                            + " mWindowAdded=" + optionalBoolean(activity, "mWindowAdded", false)
                            + " mDecor=" + (optionalObject(activity, "mDecor") != null)
                            + " visibleFromClient=" + optionalBoolean(activity, "mVisibleFromClient", true)
                            + " visibleFromServer=" + optionalBoolean(activity, "mVisibleFromServer", false)
                            + " startedActivity=" + optionalBoolean(activity, "mStartedActivity", false)
                            + " finished=" + activity.isFinishing()
                            + " decorVisibility=" + (decor == null ? -1 : decor.getVisibility())
                            + " windowVisibility=" + (decor == null ? -1 : decor.getWindowVisibility())
                            + " attached=" + (decor != null && decor.isAttachedToWindow())
                            + " registration=" + windowRegistration(decor).name()
                            + " parent=" + (parent == null ? "null" : parent.getClass().getName())
                            + " viewRoot=" + (root == null ? "null" : root.getClass().getName())
                            + " viewRootState=" + viewRootState(parent)
                            + " wmgViews=" + windowManagerViewCount()
                            + " layoutPackage=" + packageName
                            + " layoutType=" + type
                            + " layoutToken=" + layoutToken);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_FRAMEWORK_ACTIVITY",
                    "WINDOW_POST_RESUME_STATE unavailable boundary=" + boundary, error);
        }
    }

    private static Object currentActivityThread() throws Exception {
        Class<?> activityThreadType = Class.forName("android.app.ActivityThread");
        Field currentField = findField(activityThreadType, "sCurrentActivityThread");
        if (currentField == null) return null;
        currentField.setAccessible(true);
        return currentField.get(null);
    }

    private static boolean containsKey(Object target, Object key) throws Exception {
        if (target == null || key == null) return false;
        if (target instanceof Map<?, ?> map) return map.containsKey(key);
        Method contains = findMethod(target.getClass(), "containsKey", Object.class);
        if (contains == null) return false;
        contains.setAccessible(true);
        Object value = contains.invoke(target, key);
        return value instanceof Boolean && (Boolean) value;
    }

    private static Object invokeOptional(Object target, String name) {
        if (target == null) return null;
        try {
            Method method = findMethod(target.getClass(), name);
            if (method == null) return null;
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return null;
        }
    }

    /**
     * Captures the platform ViewRoot bookkeeping without treating it as a repair signal.  A
     * DecorView can remain in WindowManagerGlobal while its parent has already cleared
     * AttachInfo during an asynchronous remove; that distinction is not visible in WMS's
     * window list and is needed to classify a post-resume black frame.
     */
    private static String viewRootState(Object parent) {
        if (parent == null) return "null";
        try {
            return "added=" + optionalBoolean(parent, "mAdded", false)
                    + " removed=" + optionalBoolean(parent, "mRemoved", false)
                    + " dying=" + optionalBoolean(parent, "mDying", false)
                    + " stopped=" + optionalBoolean(parent, "mStopped", false)
                    + " attachInfo=" + (optionalObject(parent, "mAttachInfo") != null)
                    + " view=" + (optionalObject(parent, "mView") != null)
                    + " window=" + (optionalObject(parent, "mWindow") != null)
                    + " surface=" + (optionalObject(parent, "mSurface") != null);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            return "unavailable=" + error.getClass().getSimpleName();
        }
    }

    private static void publishWindowIfMissing(Activity activity) {
        try {
            fixFrameworkWindowIdentity(activity);
            android.view.Window window = activity.getWindow();
            if (window == null) return;
            android.view.View decor = window.getDecorView();
            if (decor.isAttachedToWindow()
                    || windowRegistration(decor) == WindowRegistration.REGISTERED) {
                Object record = findActivityClientRecord(activity);
                if (record != null) setOptionalBoolean(record, "hideForNow", false);
                return;
            }
            Object record = findActivityClientRecord(activity);
            if (record != null) setOptionalBoolean(record, "hideForNow", false);
            // The reference path uses the Activity/PhoneWindow WindowManagerImpl (mParentWindow set).
            // The Host application WindowManager has no parent PhoneWindow and cannot attach
            // TYPE_BASE_APPLICATION to the Stub ActivityRecord.
            android.view.WindowManager windowManager = activity.getWindowManager();
            if (windowManager == null) return;
            android.view.WindowManager.LayoutParams layout = window.getAttributes();
            layout.type = android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
            Field tokenField = findField(activity.getClass(), "mToken");
            if (tokenField != null) {
                tokenField.setAccessible(true);
                Object token = tokenField.get(activity);
                if (token instanceof android.os.IBinder binder) layout.token = binder;
            }
            HostWindowIdentity host = hostWindowIdentity(activity);
            if (host != null) applyHostPackageToLayoutParams(layout, host.packageName);
            android.util.Log.i("CS_FRAMEWORK_ACTIVITY",
                    "WINDOW_PUBLISH_ADDVIEW attempt=1"
                            + " wm=" + windowManager.getClass().getName()
                            + " pkg=" + layout.packageName
                            + " token=" + (layout.token != null)
                            + " type=" + layout.type
                            + " activityClass=" + activity.getClass().getName());
            windowManager.addView(decor, layout);
            if (windowRegistration(decor) != WindowRegistration.REGISTERED) {
                // Device evidence: WindowManagerImpl.addView can return without mutating
                // WindowManagerGlobal.mViews (wmgViews=0). Add through the process singleton
                // the same way ActivityThread/ViewRootImpl would.
                addViewThroughWindowManagerGlobal(decor, layout, activity, window);
            }
            setOptionalBoolean(activity, "mWindowAdded", true);
            if (record != null) setOptionalObject(record, "window", window);
            boolean attached = decor.isAttachedToWindow();
            int viewCount = windowManagerViewCount();
            android.util.Log.i("CS_FRAMEWORK_ACTIVITY",
                    "WINDOW_PUBLISH_AFTER_RESUME attempt=1"
                            + " attached=" + attached
                            + " registered=" + windowRegistration(decor).name()
                            + " parent=" + (decor.getParent() == null ? "null" : decor.getParent().getClass().getName())
                            + " wmgViews=" + viewCount
                            + " activityClass=" + activity.getClass().getName());
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            android.util.Log.w("CS_FRAMEWORK_ACTIVITY",
                    "WINDOW_PUBLISH_AFTER_RESUME failed attempt=1 retryable=false", error);
        }
    }

    private record HostWindowIdentity(String packageName, int uid) { }

    private static HostWindowIdentity hostWindowIdentity(Activity activity) {
        android.content.Context base = activity.getBaseContext();
        if (!(base instanceof com.warden.controlledsandbox.runtime.guest.GuestContext guest)) {
            return null;
        }
        android.content.Context host = guest.hostServiceContext();
        if (host == null) return null;
        String packageName = host.getPackageName();
        if (packageName == null || packageName.isEmpty()) return null;
        return new HostWindowIdentity(packageName, host.getApplicationInfo().uid);
    }

    private static void applyHostPackageToLayoutParams(android.view.WindowManager.LayoutParams layout,
                                                       String hostPackage) {
        if (layout == null || hostPackage == null || hostPackage.isEmpty()) return;
        layout.packageName = hostPackage;
    }

    private static void patchWindowManagerContext(android.view.WindowManager windowManager,
                                                  HostWindowIdentity host) throws Exception {
        if (windowManager == null || host == null) return;
        Field contextField = findField(windowManager.getClass(), "mContext");
        if (contextField == null) return;
        contextField.setAccessible(true);
        patchContextImplIdentity(contextField.get(windowManager), host);
    }

    /**
     * Reference {@code ContextCompat.fix}: rewrite ContextImpl {@code mBasePackageName},
     * {@code mOpPackageName} and AttributionSource uid/package to the Host. Never rewrite
     * {@code GuestContext} itself — Guest code must still observe Guest {@code getOpPackageName()}.
     */
    private static void patchContextImplIdentity(Object context, HostWindowIdentity host)
            throws Exception {
        if (context == null || host == null) return;
        Object cursor = context;
        int depth = 0;
        while (cursor instanceof android.content.ContextWrapper wrapper && depth < 10) {
            if (cursor instanceof com.warden.controlledsandbox.runtime.guest.GuestContext) return;
            android.content.Context next = wrapper.getBaseContext();
            if (next == null || next == cursor) break;
            cursor = next;
            depth++;
        }
        if (cursor == null
                || cursor instanceof com.warden.controlledsandbox.runtime.guest.GuestContext) {
            return;
        }
        if (!cursor.getClass().getName().contains("ContextImpl")) return;
        setOptionalObject(cursor, "mBasePackageName", host.packageName);
        setOptionalObject(cursor, "mOpPackageName", host.packageName);
        Method attribution = findMethod(cursor.getClass(), "getAttributionSource");
        if (attribution != null) {
            attribution.setAccessible(true);
            patchAttributionSource(attribution.invoke(cursor), host, 0);
        }
    }

    private static void patchAttributionSource(Object source, HostWindowIdentity host, int depth)
            throws Exception {
        if (source == null || host == null || depth > 5) return;
        Object state = optionalObject(source, "mAttributionSourceState");
        if (state != null) {
            setOptionalObject(state, "packageName", host.packageName);
            Field uidField = findField(state.getClass(), "uid");
            if (uidField != null) {
                uidField.setAccessible(true);
                uidField.setInt(state, host.uid);
            }
        }
        Method getNext = findMethod(source.getClass(), "getNext");
        if (getNext == null) return;
        getNext.setAccessible(true);
        patchAttributionSource(getNext.invoke(source), host, depth + 1);
    }

    private static void ensureFrameworkRecordIntent(Object record, Activity activity)
            throws Exception {
        Field intentField = findField(record.getClass(), "intent");
        if (intentField == null) return;
        intentField.setAccessible(true);
        Object current = intentField.get(record);
        if (current instanceof Intent intent && intent.getComponent() != null) return;
        Intent replacement = activity.getIntent() == null
                ? new Intent() : new Intent(activity.getIntent());
        if (replacement.getComponent() == null) {
            replacement.setComponent(new ComponentName(activity, activity.getClass()));
        }
        intentField.set(record, replacement);
    }

    /**
     * Activity.attach() may initialize ContextThemeWrapper's theme bookkeeping from the Host
     * transport before the audited field projection runs. Re-apply the Guest component theme
     * through the public API after projection so AndroidX/AppCompat sees the Guest style parent
     * during both creation and teardown.
     */
    private static void applyGuestTheme(Activity guest,
                                        GuestRuntimeEnvironment.Session session,
                                        String componentClass) {
        int themeResId = 0;
        for (VirtualComponentSnapshot component : session.spec().packageState().components()) {
            if ("ACTIVITY".equals(component.type())
                    && componentClass.equals(component.className())) {
                themeResId = component.themeResId();
                break;
            }
        }
        if (themeResId != 0) guest.setTheme(themeResId);
    }

    private static void clearStaleFrameworkWindow(Object record, Activity activity) throws Exception {
        android.view.Window window = activity.getWindow();
        android.view.View decor = window == null ? null : window.getDecorView();
        if (decor != null && (decor.isAttachedToWindow()
                || windowRegistration(decor) != WindowRegistration.NOT_REGISTERED)) return;
        clearFrameworkWindowState(record, activity);
    }

    private static void clearFrameworkWindowState(Object record, Activity activity) throws Exception {
        Field recordWindow = findField(record.getClass(), "window");
        if (recordWindow != null) {
            recordWindow.setAccessible(true);
            recordWindow.set(record, null);
        }
        setOptionalBoolean(record, "mPreserveWindow", false);
        setOptionalObject(record, "mPendingRemoveWindow", null);
        setOptionalObject(record, "mPendingRemoveWindowManager", null);
        setOptionalBoolean(activity, "mWindowAdded", false);
        setOptionalObject(activity, "mDecor", null);
    }

    private static boolean optionalBoolean(Object target, String name, boolean fallback)
            throws Exception {
        Field field = findField(target.getClass(), name);
        if (field == null) return fallback;
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static Object optionalObject(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        if (field == null) return null;
        field.setAccessible(true);
        return field.get(target);
    }

    private enum WindowRegistration {
        REGISTERED,
        NOT_REGISTERED,
        UNKNOWN
    }

    private static void addViewThroughWindowManagerGlobal(android.view.View decor,
                                                          android.view.WindowManager.LayoutParams layout,
                                                          Activity activity,
                                                          android.view.Window parentWindow)
            throws Exception {
        Object global = Class.forName("android.view.WindowManagerGlobal")
                .getDeclaredMethod("getInstance").invoke(null);
        Method getDisplay = findMethod(activity.getClass(), "getDisplay");
        android.view.Display display = getDisplay == null ? null
                : (android.view.Display) getDisplay.invoke(activity);
        int userId = 0;
        Method getUserId = findMethod(activity.getClass(), "getUserId");
        if (getUserId != null) {
            Object value = getUserId.invoke(activity);
            if (value instanceof Integer integer) userId = integer;
        }
        Method selected = null;
        for (Method method : global.getClass().getDeclaredMethods()) {
            if (!"addView".equals(method.getName())) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 5 || parameters.length == 4) {
                selected = method;
                if (parameters.length == 5) break;
            }
        }
        if (selected == null) {
            throw new NoSuchMethodException("WindowManagerGlobal.addView");
        }
        selected.setAccessible(true);
        if (selected.getParameterCount() == 5) {
            selected.invoke(global, decor, layout, display, parentWindow, userId);
        } else {
            selected.invoke(global, decor, layout, display, parentWindow);
        }
        android.util.Log.i("CS_FRAMEWORK_ACTIVITY",
                "WINDOW_PUBLISH_GLOBAL_ADDVIEW arity=" + selected.getParameterCount()
                        + " display=" + (display == null ? "null" : display.getDisplayId())
                        + " userId=" + userId
                        + " activityClass=" + activity.getClass().getName());
    }

    private static int windowManagerViewCount() {
        try {
            Object global = Class.forName("android.view.WindowManagerGlobal")
                    .getDeclaredMethod("getInstance").invoke(null);
            Field views = requireField(global.getClass(), "mViews");
            views.setAccessible(true);
            Object value = views.get(global);
            return value instanceof java.util.List<?> list ? list.size() : -1;
        } catch (Throwable ignored) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
            return -2;
        }
    }

    private static WindowRegistration windowRegistration(android.view.View decor) {
        if (decor == null) return WindowRegistration.UNKNOWN;
        try {
            Method getInstance = Class.forName("android.view.WindowManagerGlobal")
                    .getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object global = getInstance.invoke(null);
            Field views = requireField(global.getClass(), "mViews");
            views.setAccessible(true);
            Object value = views.get(global);
            if (!(value instanceof java.util.List<?> list)) return WindowRegistration.UNKNOWN;
            for (Object item : list) {
                if (item == decor) return WindowRegistration.REGISTERED;
            }
            return WindowRegistration.NOT_REGISTERED;
        } catch (Throwable ignored) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
            android.util.Log.w("CS_FRAMEWORK_ACTIVITY",
                    "window registration inspection unavailable="
                            + ignored.getClass().getSimpleName());
        }
        return WindowRegistration.UNKNOWN;
    }

    private static boolean isWindowRegistered(android.view.View decor) {
        return windowRegistration(decor) == WindowRegistration.REGISTERED;
    }

    private static void setOptionalBoolean(Object target, String name, boolean value) throws Exception {
        Field field = findField(target.getClass(), name);
        if (field == null) return;
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static void setOptionalObject(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        if (field == null) return;
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Activity.attach() starts from the host StubActivity metadata.  Leaving that metadata on
     * the Guest Activity makes framework code such as WebView's BuildInfo query the host package.
     * Keep the host's audited framework identity fields, but leave the Guest-owned Window and
     * WindowManager created by Activity.attach() intact. Sharing the Stub Window would make two
     * Android Activity records own one DecorView and causes WindowManagerGlobal cleanup failures
     * when a Guest Activity starts a nested Stub.
     */
    private static ActivityInfo guestActivityInfo(Activity host,
                                                  GuestRuntimeEnvironment.Session session,
                                                  String componentClass) {
        try {
            Field field = requireField(host.getClass(), "mActivityInfo");
            field.setAccessible(true);
            Object value = field.get(host);
            if (!(value instanceof ActivityInfo source)) {
                throw new IllegalStateException("ACTIVITY_INFO_SOURCE_INVALID");
            }
            ActivityInfo projected = new ActivityInfo(source);
            ApplicationInfo guestApplication = session.context().getApplicationInfo();
            projected.applicationInfo = guestApplication;
            projected.packageName = session.spec().packageName;
            projected.name = componentClass;
            setOptionalObjectUnchecked(projected, "metaData", null);
            projected.processName = guestApplication.processName == null
                    ? session.spec().packageName : guestApplication.processName;
            for (VirtualComponentSnapshot component : session.spec().packageState().components()) {
                if (!"ACTIVITY".equals(component.type())
                        || !componentClass.equals(component.className())) continue;
                // A Stub Activity's framework metadata describes the host trampoline,
                // not the Guest component. Project the virtual package authority's
                // exported/enabled/security identity onto the Guest Activity.
                projected.exported = component.exported();
                projected.enabled = component.enabled();
                projected.permission = component.permission().isEmpty()
                        ? null : component.permission();
                projected.processName = component.processName().isEmpty()
                        ? projected.processName : component.processName();
                projected.theme = component.themeResId();
                setOptionalObjectUnchecked(projected, "metaData",
                        com.warden.controlledsandbox.runtime.guest.GuestPackageMetadataMapper
                                .toMetadataBundle(component.metaData()));
                projectActivityContract(projected, component);
                break;
            }
            projectActivityAlias(projected, session, componentClass);
            return projected;
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_INFO_GUEST_PROJECTION_FAILED", error);
        }
    }

    private static ActivityInfo guestActivityInfo(GuestRuntimeEnvironment.Session session,
                                                  String componentClass) {
        ActivityInfo projected = new ActivityInfo();
        ApplicationInfo application = new ApplicationInfo(session.context().getApplicationInfo());
        application.packageName = session.spec().packageName;
        application.processName = session.spec().processName();
        projected.applicationInfo = application;
        projected.packageName = session.spec().packageName;
        projected.name = componentClass;
        setOptionalObjectUnchecked(projected, "metaData", null);
        projected.processName = session.spec().processName();
        for (VirtualComponentSnapshot component : session.spec().packageState().components()) {
            if (!"ACTIVITY".equals(component.type()) || !componentClass.equals(component.className())) continue;
            projected.exported = component.exported();
            projected.enabled = component.enabled();
            projected.permission = component.permission().isEmpty() ? null : component.permission();
            projected.processName = component.processName().isEmpty()
                    ? projected.processName : component.processName();
            projected.theme = component.themeResId();
            setOptionalObjectUnchecked(projected, "metaData",
                    com.warden.controlledsandbox.runtime.guest.GuestPackageMetadataMapper
                            .toMetadataBundle(component.metaData()));
            projectActivityContract(projected, component);
            break;
        }
        projectActivityAlias(projected, session, componentClass);
        return projected;
    }

    /**
     * ActivityThread keeps a second ActivityInfo copy on ActivityClientRecord.  The logical
     * component remains the alias so task/intent routing can preserve the public component name,
     * while framework factory resolution must see the alias target.  Keep both views consistent.
     */
    private static void projectActivityAlias(ActivityInfo info,
                                              GuestRuntimeEnvironment.Session session,
                                              String componentClass) {
        if (info == null || session == null || componentClass == null) return;
        VirtualPackageMetadata.Component component = session.packageMetadata().component(
                componentClass, VirtualPackageMetadata.Type.ACTIVITY);
        if (component == null) return;
        setOptionalObjectUnchecked(info, "targetActivity",
                component.targetActivity().isEmpty() ? null : component.targetActivity());
    }

    /**
     * Projects the complete public ActivityInfo task contract onto the object consumed by
     * ActivityThread. PackageManager queries already expose these fields, but ActivityThread
     * drives launch/reuse/recreation from its ActivityClientRecord copy. Leaving the copy with
     * Stub defaults makes a Guest singleTask/document/PiP/configuration policy diverge from the
     * virtual PMS even though the Guest PackageManager reports the right metadata.
     */
    private static void projectActivityContract(ActivityInfo info,
                                                VirtualComponentSnapshot component) {
        setOptionalObjectUnchecked(info, "launchMode", launchMode(component.launchMode()));
        setOptionalObjectUnchecked(info, "documentLaunchMode",
                documentLaunchMode(component.documentLaunchMode()));
        setOptionalObjectUnchecked(info, "persistableMode",
                persistableMode(component.persistableMode()));
        setOptionalObjectUnchecked(info, "taskAffinity", component.taskAffinity());
        setOptionalObjectUnchecked(info, "configChanges", component.configChanges());
        setOptionalObjectUnchecked(info, "screenOrientation",
                screenOrientation(component.screenOrientation()));
        setOptionalObjectUnchecked(info, "windowSoftInputMode", component.windowSoftInputMode());
        setOptionalObjectUnchecked(info, "maxAspectRatio", component.maxAspectRatio());
        setOptionalObjectUnchecked(info, "minAspectRatio", component.minAspectRatio());
        setOptionalObjectUnchecked(info, "resizeMode", resizeMode(component.resizeMode()));
        setOptionalObjectUnchecked(info, "targetActivity",
                component.targetActivity().isEmpty() ? null : component.targetActivity());
        int flags = component.flags();
        if (component.excludeFromRecents()) flags |= staticInt(ActivityInfo.class,
                "FLAG_EXCLUDE_FROM_RECENTS");
        if (component.noHistory()) flags |= staticInt(ActivityInfo.class, "FLAG_NO_HISTORY");
        if (component.finishOnTaskLaunch()) flags |= staticInt(ActivityInfo.class,
                "FLAG_FINISH_ON_TASK_LAUNCH");
        if (component.clearTaskOnLaunch()) flags |= staticInt(ActivityInfo.class,
                "FLAG_CLEAR_TASK_ON_LAUNCH");
        if (component.alwaysRetainTaskState()) flags |= staticInt(ActivityInfo.class,
                "FLAG_ALWAYS_RETAIN_TASK_STATE");
        if (component.allowTaskReparenting()) flags |= staticInt(ActivityInfo.class,
                "FLAG_ALLOW_TASK_REPARENTING");
        if (component.supportsPictureInPicture()) flags |= staticInt(ActivityInfo.class,
                "FLAG_SUPPORTS_PICTURE_IN_PICTURE");
        setOptionalObjectUnchecked(info, "flags", flags);
    }

    private static int launchMode(String value) {
        String name = normalizedEnum(value);
        if ("singletop".equals(name)) return staticInt(ActivityInfo.class, "LAUNCH_SINGLE_TOP");
        if ("singletask".equals(name)) return staticInt(ActivityInfo.class, "LAUNCH_SINGLE_TASK");
        if ("singleinstance".equals(name)) return staticInt(ActivityInfo.class, "LAUNCH_SINGLE_INSTANCE");
        if ("singleinstancepertask".equals(name)) {
            return staticInt(ActivityInfo.class, "LAUNCH_SINGLE_INSTANCE_PER_TASK");
        }
        return staticInt(ActivityInfo.class, "LAUNCH_MULTIPLE");
    }

    private static int documentLaunchMode(String value) {
        String name = normalizedEnum(value);
        if ("intoexisting".equals(name)) {
            return staticInt(ActivityInfo.class, "DOCUMENT_LAUNCH_INTO_EXISTING");
        }
        if ("always".equals(name)) return staticInt(ActivityInfo.class, "DOCUMENT_LAUNCH_ALWAYS");
        if ("never".equals(name)) return staticInt(ActivityInfo.class, "DOCUMENT_LAUNCH_NEVER");
        return staticInt(ActivityInfo.class, "DOCUMENT_LAUNCH_NONE");
    }

    private static int persistableMode(String value) {
        String name = normalizedEnum(value);
        if ("acrossreboots".equals(name) || "persistacrossreboots".equals(name)) {
            return staticInt(ActivityInfo.class, "PERSIST_ACROSS_REBOOTS");
        }
        if ("rootonly".equals(name) || "persistrootonly".equals(name)) {
            return staticInt(ActivityInfo.class, "PERSIST_ROOT_ONLY");
        }
        return staticInt(ActivityInfo.class, "PERSIST_NEVER");
    }

    private static int screenOrientation(String value) {
        String name = normalizedEnum(value);
        if (name.isEmpty()) name = "unspecified";
        if ("sensorportrait".equals(name)) name = "sensor_portrait";
        else if ("sensorlandscape".equals(name)) name = "sensor_landscape";
        else if ("fullsensor".equals(name)) name = "full_sensor";
        else if ("fulluser".equals(name)) name = "full_user";
        else if ("userlandscape".equals(name)) name = "user_landscape";
        else if ("userportrait".equals(name)) name = "user_portrait";
        else if ("reverselandscape".equals(name)) name = "reverse_landscape";
        else if ("reverseportrait".equals(name)) name = "reverse_portrait";
        return staticInt(ActivityInfo.class, "SCREEN_ORIENTATION_" + name.toUpperCase(java.util.Locale.ROOT));
    }

    private static int resizeMode(String value) {
        String name = normalizedEnum(value);
        if (name.isEmpty()) name = "resizeable";
        if ("forceresizable".equals(name)) name = "force_resizeable";
        return staticInt(ActivityInfo.class, "RESIZE_MODE_" + name.toUpperCase(java.util.Locale.ROOT));
    }

    private static String normalizedEnum(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("-", "").replace("_", "");
    }

    private static int staticInt(Class<?> type, String name) {
        try {
            Field field = type.getField(name);
            return field.getInt(null);
        } catch (Throwable ignored) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored);
            return 0;
        }
    }

    private static void setOptionalObjectUnchecked(Object target, String name, Object value) {
        try {
            setOptionalObject(target, name, value);
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_INFO_FIELD_PROJECTION_FAILED:" + name,
                    error);
        }
    }

    static BridgeReport installFields(Object host, Object guest, List<String> requiredHostFields,
                                      List<String> optionalHostFields, Map<String, Object> directValues,
                                      int apiLevel) {
        if (apiLevel < MIN_API || apiLevel > MAX_AUDITED_API) {
            throw new IllegalStateException("UNSUPPORTED_ACTIVITY_BRIDGE_API:" + apiLevel);
        }
        java.util.Objects.requireNonNull(host, "host");
        java.util.Objects.requireNonNull(guest, "guest");
        List<Write> writes = new ArrayList<>();
        List<String> optionalMissing = new ArrayList<>();
        try {
            for (String name : requiredHostFields) {
                Field source = requireField(host.getClass(), name);
                Field target = requireField(guest.getClass(), name);
                source.setAccessible(true);
                target.setAccessible(true);
                Object value = source.get(host);
                if (!assignable(target, value)) {
                    if ("mFragments".equals(name)) {
                        // AndroidX FragmentActivity owns a different FragmentController type;
                        // its guest-side controller must not be replaced with the host platform
                        // controller. Keep the mismatch visible in the bridge report.
                        optionalMissing.add(name + ":TYPE_MISMATCH");
                        continue;
                    }
                    ensureAssignable(target, value, name);
                }
                writes.add(new Write(target, guest, target.get(guest), value));
            }
            for (String name : optionalHostFields) {
                Field source = findField(host.getClass(), name);
                Field target = findField(guest.getClass(), name);
                if (source == null || target == null) {
                    optionalMissing.add(name);
                    continue;
                }
                source.setAccessible(true);
                target.setAccessible(true);
                Object value = source.get(host);
                ensureAssignable(target, value, name);
                writes.add(new Write(target, guest, target.get(guest), value));
            }
            for (Map.Entry<String, Object> entry : directValues.entrySet()) {
                Field target = requireField(guest.getClass(), entry.getKey());
                target.setAccessible(true);
                ensureAssignable(target, entry.getValue(), entry.getKey());
                writes.add(new Write(target, guest, target.get(guest), entry.getValue()));
            }
            int applied = 0;
            try {
                for (Write write : writes) {
                    write.field.set(write.target, write.value);
                    applied++;
                }
            } catch (Throwable failure) {
                try {
                    rollback(writes, applied);
                } finally {
                    com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(failure);
                }
                throw failure;
            }
            return new BridgeReport(apiLevel, writes.size(), List.copyOf(optionalMissing));
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            throw new IllegalStateException("ACTIVITY_FIELD_BRIDGE_FAILED", error);
        }
    }

    private static void rollback(List<Write> writes, int applied) {
        for (int index = applied - 1; index >= 0; index--) {
            Write write = writes.get(index);
            try { write.field.set(write.target, write.previous); } catch (Throwable ignored) { com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(ignored); }
        }
    }

    private static void ensureAssignable(Field field, Object value, String name) {
        if (!assignable(field, value)) {
            throw new IllegalStateException("ACTIVITY_FIELD_TYPE_MISMATCH:" + name
                    + ":" + field.getType().getName() + "<-" + value.getClass().getName());
        }
    }

    private static boolean assignable(Field field, Object value) {
        return value == null || boxed(field.getType()).isInstance(value);
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static Field requireField(Class<?> type, String name) {
        Field field = findField(type, name);
        if (field == null) throw new IllegalStateException("ACTIVITY_FIELD_MISSING:" + name);
        return field;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                Method method = cursor.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethodIncludingInterfaces(Class<?> type, String name,
                                                         Class<?>... parameterTypes) {
        Method direct = findMethod(type, name, parameterTypes);
        if (direct != null) return direct;
        for (Class<?> iface : type.getInterfaces()) {
            Method inherited = findMethodIncludingInterfaces(iface, name, parameterTypes);
            if (inherited != null) return inherited;
        }
        Class<?> parent = type.getSuperclass();
        return parent == null ? null : findMethodIncludingInterfaces(parent, name, parameterTypes);
    }

    record BridgeReport(int apiLevel, int appliedFieldCount, List<String> optionalMissingFields) {
        BridgeReport {
            optionalMissingFields = List.copyOf(optionalMissingFields);
            if (appliedFieldCount < 1) throw new IllegalArgumentException("appliedFieldCount must be positive");
        }
    }

    private record Write(Field field, Object target, Object previous, Object value) { }
}
