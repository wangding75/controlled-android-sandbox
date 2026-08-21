package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.framework.contract.InvocationMethodMatcher;

import com.warden.controlledsandbox.framework.identity.GuestIdentity;
import com.warden.controlledsandbox.framework.identity.VirtualNotificationNamespace;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceState;
import com.warden.controlledsandbox.framework.identity.VirtualSystemServiceAuthority;
import com.warden.controlledsandbox.framework.identity.VirtualPendingIntentToken;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Service-specific isolation and namespace rewriting for bounded framework surfaces. */
public final class VirtualSystemServiceInterceptor {
    private final GuestIdentity identity;
    private final String service;
    private final VirtualSystemServiceState state;

    public VirtualSystemServiceInterceptor(GuestIdentity identity, String service) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.service = normalize(service);
        this.state = identity.virtualServices();
    }

    public Call before(Method method, Object[] arguments) {
        if (method == null) return Call.passThrough();
        return switch (service) {
            case "clipboard" -> clipboard(method, arguments);
            case "account" -> account(method, arguments);
            case "alarm" -> alarm(method, arguments);
            case "notification" -> notification(method, arguments);
            case "jobscheduler" -> jobs(method, arguments);
            default -> Call.passThrough();
        };
    }

    private Call clipboard(Method method, Object[] arguments) {
        String name = normalize(method.getName());
        VirtualSystemServiceState.ClipboardState clipboard = state.clipboard();
        if (name.startsWith("setprimaryclip")) {
            clipboard.set(firstPayload(arguments));
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("getprimaryclipdescription")) {
            Object clip = clipboard.get();
            return Call.handled(clip == null ? null : invoke(clip, "getDescription"));
        }
        if (name.startsWith("getprimaryclipsource")) return Call.handled(identity.packageName());
        if (name.startsWith("getprimaryclip")) return Call.handled(clipboard.get());
        if (name.startsWith("hasprimaryclip") || name.startsWith("hasclipboardtext")) {
            return Call.handled(clipboard.has());
        }
        if (name.startsWith("clearprimaryclip")) {
            clipboard.clear();
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("addprimaryclipchangedlistener")) {
            clipboard.addListener(firstPayload(arguments));
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("removeprimaryclipchangedlistener")) {
            clipboard.removeListener(firstPayload(arguments));
            return Call.handled(defaultValue(method.getReturnType()));
        }
        throw new SecurityException("VIRTUAL_CLIPBOARD_SIGNATURE_UNSUPPORTED:" + method.getName());
    }

    private Call account(Method method, Object[] arguments) {
        String name = normalize(method.getName());
        VirtualSystemServiceState.AccountState accounts = state.accounts();
        if (name.startsWith("getaccounts") || name.startsWith("getaccountsbytype")) {
            Class<?> returnType = method.getReturnType();
            if (!returnType.isArray()) {
                throw new SecurityException("VIRTUAL_ACCOUNT_QUERY_SIGNATURE_UNSUPPORTED:" + method.getName());
            }
            String requestedType = firstAccountType(arguments);
            return Call.handled(accounts.array(returnType.getComponentType(), requestedType));
        }
        if (name.equals("addaccountexplicitly") || name.startsWith("addaccountexplicitlywithvisibility")) {
            Object account = firstAccount(arguments);
            String password = stringAfter(arguments, account);
            return Call.handled(accounts.add(account, password));
        }
        if (name.startsWith("removeaccountexplicitly") || name.startsWith("removeaccountasuser")) {
            return Call.handled(accounts.remove(firstAccount(arguments)));
        }
        if (name.startsWith("setpassword")) {
            Object account = firstAccount(arguments); accounts.setPassword(account, stringAfter(arguments, account));
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("clearpassword")) {
            accounts.setPassword(firstAccount(arguments), "");
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("getpassword")) return Call.handled(accounts.password(firstAccount(arguments)));
        if (name.startsWith("setauthtoken")) {
            Object account = firstAccount(arguments);
            List<String> strings = stringsAfter(arguments, account);
            accounts.setToken(account, strings.size() > 0 ? strings.get(0) : "",
                    strings.size() > 1 ? strings.get(1) : "");
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("peekauthtoken")) {
            Object account = firstAccount(arguments);
            return Call.handled(accounts.token(account, stringAfter(arguments, account)));
        }
        if (name.startsWith("getauthtoken")) {
            Object account = firstAccount(arguments);
            String tokenType = stringAfter(arguments, account);
            String token = accounts.token(account, tokenType);
            Object callback = firstObjectWithMethod(arguments, "onResult");
            if (callback == null) callback = firstObjectWithMethod(arguments, "onError");
            AccountAuthenticatorBoundary.completeToken(callback, account, token);
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("addaccount") || name.startsWith("confirmcredentials")
                || name.startsWith("hasfeatures")) {
            Object callback = firstObjectWithMethod(arguments, "onError");
            AccountAuthenticatorBoundary.deferAuthenticator(callback);
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("invalidateauthtoken")) {
            String[] strings = stringArguments(arguments);
            accounts.invalidateToken(strings.length > 0 ? strings[0] : "", strings.length > 1 ? strings[1] : "");
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("accountauthenticated")) return Call.handled(accounts.password(firstAccount(arguments)) != null);
        if (name.startsWith("getaccountvisibility")) {
            Object account = tryFirstAccount(arguments);
            Class<?> type = method.getReturnType();
            if (account == null) {
                return Call.handled(type == boolean.class || type == Boolean.class
                        ? Boolean.TRUE : Integer.valueOf(1));
            }
            int visibility = accounts.visibility(account);
            if (type == boolean.class || type == Boolean.class) {
                return Call.handled(Boolean.valueOf(visibility != 0));
            }
            return Call.handled(Integer.valueOf(visibility));
        }
        if (name.startsWith("setaccountvisibility")) {
            Object account = firstAccount(arguments);
            int visibility = 1;
            if (arguments != null) {
                for (Object value : arguments) {
                    if (value instanceof Integer integer) visibility = integer;
                }
            }
            return Call.handled(Boolean.valueOf(accounts.setVisibility(account, visibility)));
        }
        if (name.startsWith("registeraccountlistener")) {
            accounts.addListener(firstPayload(arguments));
            return Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("unregisteraccountlistener")) {
            accounts.removeListener(firstPayload(arguments));
            return Call.handled(defaultValue(method.getReturnType()));
        }
        throw new SecurityException("VIRTUAL_ACCOUNT_SIGNATURE_UNSUPPORTED:" + method.getName());
    }

    private Call alarm(Method method, Object[] arguments) {
        String name = normalize(method.getName());
        if (name.startsWith("set") || name.startsWith("schedule")) {
            Object token = alarmToken(arguments);
            long trigger = normalizedTrigger(arguments);
            long interval = repeating(name) ? interval(arguments, trigger) : 0L;
            String pendingIntentTokenId = pendingIntentTokenId(token);
            boolean hostHeld = isHostHeldAlarmToken(token) || !pendingIntentTokenId.isEmpty();
            String deliveryPath = hostHeld ? "PENDING_INTENT" : "LISTENER";
            boolean exact = name.contains("exact") || name.contains("alarmclock");
            boolean allowWhileIdle = name.contains("allowwhileidle");
            boolean alarmClock = name.contains("alarmclock");
            Object alarmClockInfo = alarmClock ? firstAlarmClockInfo(arguments) : null;
            Object alarmClockShowIntent = alarmClock
                    ? member(alarmClockInfo, "mShowIntent", "showIntent", "getShowIntent") : null;
            state.alarms().schedule(token, trigger, interval, exact, allowWhileIdle, deliveryPath,
                    pendingIntentTokenId, identity.processName(), identity.generation(), identity.packageRevision(),
                    alarmClock, alarmClockShowIntent);
            // PendingIntent alarms must be held by Android AlarmManager so the Broker-owned
            // IIntentSender can fire after the Guest stub process dies. Listener tokens stay
            // virtual: they are process-local and cannot outlive the creator.
            return hostHeld ? Call.passThrough() : Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("remove") || name.startsWith("cancel")) {
            Object token = alarmToken(arguments);
            state.alarms().cancel(token);
            return isHostHeldAlarmToken(token) || !pendingIntentTokenId(token).isEmpty()
                    ? Call.passThrough()
                    : Call.handled(defaultValue(method.getReturnType()));
        }
        if (name.startsWith("canScheduleExactAlarms".toLowerCase(Locale.ROOT))) {
            return Call.handled(identity.permissionPolicy().isGranted("android.permission.SCHEDULE_EXACT_ALARM"));
        }
        if (name.startsWith("getnextalarmclock")) return Call.handled(nextAlarmClock());
        if (name.contains("time") || name.contains("timezone")) {
            throw new SecurityException("VIRTUAL_ALARM_HOST_CLOCK_MUTATION_DENIED:" + method.getName());
        }
        throw new SecurityException("VIRTUAL_ALARM_SIGNATURE_UNSUPPORTED:" + method.getName());
    }

    private Call notification(Method method, Object[] arguments) {
        String name = normalize(method.getName());
        List<Restore> restores = new ArrayList<>();
        if (name.contains("cancelall")) {
            return Call.direct((delegate, intercepted) -> cancelAllNotifications(delegate, intercepted));
        }
        if (name.contains("enqueue") || name.contains("notify")) {
            int guestId = notificationGuestId(arguments);
            String guestTag = notificationGuestTag(arguments);
            String channelId = notificationChannelId(arguments);
            boolean created = findNotification(guestId, guestTag) == null;
            NotificationMetadata metadata = notificationMetadata(arguments, guestId, guestTag);
            VirtualSystemServiceAuthority.NotificationRecord candidate = new VirtualSystemServiceAuthority.NotificationRecord(
                    guestId, 0, guestTag, "", channelId, "RESERVED", identity.packageRevision(),
                    metadata.contentIntentTokenId, metadata.deleteIntentTokenId, metadata.actionIntentTokenIds,
                    metadata.foregroundService, metadata.foregroundServiceKey, null, System.currentTimeMillis());
            VirtualSystemServiceAuthority.NotificationRecord reservation = state.notifications().reserve(candidate);
            rewriteNotificationTag(arguments, restores, notificationHostTag(guestTag));
            rewriteNotificationId(arguments, restores, reservation.hostId());
            rewriteChannelStrings(arguments, restores, name);
            rewriteNotificationChannelFields(arguments, restores);
            Object notificationPayload = notificationPayload(arguments);
            return Call.passThroughLifecycle(restores, result -> {
                state.notifications().commit(new VirtualSystemServiceAuthority.NotificationRecord(
                        guestId, reservation.hostId(), guestTag, reservation.hostTag(), channelId, "ACTIVE",
                        identity.packageRevision(), metadata.contentIntentTokenId, metadata.deleteIntentTokenId,
                        metadata.actionIntentTokenIds, metadata.foregroundService, metadata.foregroundServiceKey,
                        notificationPayload, System.currentTimeMillis()));
                return result;
            }, () -> { if (created) state.notifications().remove(guestId, guestTag); });
        }
        if (name.contains("cancel")) {
            int guestId = notificationGuestId(arguments);
            String guestTag = notificationGuestTag(arguments);
            VirtualSystemServiceAuthority.NotificationRecord record = findNotification(guestId, guestTag);
            if (record == null) return Call.handled(defaultValue(method.getReturnType()));
            rewriteNotificationTag(arguments, restores, notificationHostTag(guestTag));
            rewriteNotificationId(arguments, restores, record.hostId());
            return Call.passThroughLifecycle(restores, result -> {
                state.notifications().remove(guestId, guestTag);
                return result;
            }, () -> { });
        }
        if (isChannelCreate(name)) {
            List<ChannelDraft> drafts = notificationChannelDrafts(arguments, name);
            rewriteChannelStrings(arguments, restores, name);
            rewriteChannelObjects(arguments, restores);
            return Call.passThroughLifecycle(restores, result -> {
                for (ChannelDraft draft : drafts) {
                    state.notifications().upsertChannel(new VirtualSystemServiceAuthority.NotificationChannelRecord(
                            draft.kind, draft.id, draft.groupId, identity.packageRevision(), draft.payload,
                            System.currentTimeMillis()));
                }
                return filterAndRestoreChannelResult(result);
            }, () -> { });
        }
        if (isChannelDelete(name)) {
            String kind = name.contains("group") ? "GROUP" : "CHANNEL";
            String guestId = firstChannelString(arguments);
            rewriteChannelStrings(arguments, restores, name);
            return Call.passThroughLifecycle(restores, result -> {
                if (!guestId.isEmpty()) state.notifications().removeChannel(kind, guestId);
                return result;
            }, () -> { });
        }
        // NotificationManager uses getAppActiveNotifications() on API 29+ while older
        // framework revisions expose getActiveNotifications().  Both are the same
        // Guest-visible query; leaving the former to the generic query fallback silently
        // returned an empty array even though the enqueue transaction had committed.
        if (name.startsWith("getactivenotifications")
                || name.startsWith("getappactivenotifications")) {
            return Call.passThroughWithResult(this::rewriteActiveNotifications);
        }
        if (name.contains("channel") || name.contains("group")) {
            rewriteChannelStrings(arguments, restores, name);
            rewriteChannelObjects(arguments, restores);
            return Call.passThroughLifecycle(restores, this::filterAndRestoreChannelResult, () -> { });
        }
        if (name.startsWith("arenotificationsenabled")) {
            return Call.passThrough();
        }
        throw new SecurityException("VIRTUAL_NOTIFICATION_SIGNATURE_UNSUPPORTED:" + method.getName());
    }

    private Call jobs(Method method, Object[] arguments) {
        String name = normalize(method.getName());
        List<Restore> restores = new ArrayList<>();
        if (name.startsWith("schedule") || name.startsWith("enqueue")) {
            Object job = firstObjectWithMethod(arguments, "getId");
            if (job == null) throw new SecurityException("VIRTUAL_JOB_OBJECT_REQUIRED");
            int virtualId = intResult(job, "getId");
            boolean created = state.jobs().hostIdIfPresent(virtualId) == null;
            VirtualSystemServiceAuthority.JobRecord reservation = state.jobs().reserve(
                    VirtualJobPolicySnapshotFactory.from(job, virtualId, identity));
            Field field = findField(job.getClass(), "jobId", "mJobId");
            if (field == null) {
                if (created) state.jobs().remove(virtualId);
                throw new SecurityException("VIRTUAL_JOB_ID_FIELD_UNSUPPORTED");
            }
            try {
                field.setAccessible(true); Object original = field.get(job); field.set(job, reservation.hostId());
                restores.add(() -> field.set(job, original));
            } catch (ReflectiveOperationException error) {
                if (created) state.jobs().remove(virtualId);
                throw new SecurityException("VIRTUAL_JOB_ID_REWRITE_FAILED", error);
            }
            rewriteJobService(job, restores);
            return Call.passThroughLifecycle(restores, result -> {
                state.jobs().commit(virtualId); return result;
            }, () -> { if (created) state.jobs().remove(virtualId); });
        }
        if (name.startsWith("cancelall")) {
            return Call.direct((delegate, intercepted) -> cancelAllJobs(delegate, intercepted));
        }
        if (name.startsWith("cancel") || name.startsWith("getpendingjob")) {
            int index = firstIntIndex(arguments);
            if (index < 0) throw new SecurityException("VIRTUAL_JOB_ID_REQUIRED");
            int virtualId = ((Number) arguments[index]).intValue();
            Integer hostId = state.jobs().hostIdIfPresent(virtualId);
            if (hostId == null) return Call.handled(defaultValue(method.getReturnType()));
            Object original = arguments[index]; arguments[index] = hostId;
            restores.add(() -> arguments[index] = original);
            if (name.startsWith("cancel")) {
                return Call.passThroughLifecycle(restores, result -> {
                    state.jobs().remove(virtualId); return result;
                }, () -> { });
            }
            return Call.passThroughLifecycle(restores, this::rewriteSingleJobResult, () -> { });
        }
        if (name.startsWith("getallpendingjobs")) return Call.passThroughWithResult(this::rewriteJobResults);
        throw new SecurityException("VIRTUAL_JOB_SIGNATURE_UNSUPPORTED:" + method.getName());
    }

    private Object rewriteJobResults(Object result) {
        if (!(result instanceof List<?> list)) return result;
        List<Object> filtered = new ArrayList<>();
        for (Object job : list) {
            try {
                int hostId = intResult(job, "getId");
                Integer guestId = state.jobs().guestId(hostId);
                if (guestId == null) continue;
                Field field = findField(job.getClass(), "jobId", "mJobId");
                if (field != null) { field.setAccessible(true); field.set(job, guestId); }
                filtered.add(job);
            } catch (Throwable ignored) { com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(ignored); }
        }
        return filtered;
    }

    /**
     * Projects the single-job query back to the Guest namespace.  The old path only rewrote
     * getAllPendingJobs(); getPendingJob() therefore returned a Host ID and leaked the Host
     * JobService component into the Guest process.
     */
    private Object rewriteSingleJobResult(Object result) {
        if (result == null) return null;
        try {
            int hostId = intResult(result, "getId");
            Integer guestId = state.jobs().guestId(hostId);
            if (guestId == null) return null;
            Field field = findField(result.getClass(), "jobId", "mJobId");
            if (field == null) throw new SecurityException("VIRTUAL_JOB_ID_FIELD_UNSUPPORTED");
            field.setAccessible(true);
            field.set(result, guestId);
            return result;
        } catch (SecurityException error) {
            throw error;
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            throw new SecurityException("VIRTUAL_JOB_RESULT_REWRITE_FAILED", error);
        }
    }

    /**
     * Returns only notifications owned by this virtual package and rewrites the platform
     * StatusBarNotification identity back to the Guest namespace.  The Host notification
     * service still owns the underlying record; the Guest must never observe the Host package,
     * Host integer ID or generation-qualified Host tag.
     */
    private Object rewriteActiveNotifications(Object result) {
        if (result == null) return null;
        String resultClass = result.getClass().getName();
        // INotificationManager returns ParceledListSlice<StatusBarNotification> on API 32;
        // NotificationManager unwraps it before the Guest sees the public array. Project the
        // slice itself, otherwise the result-rewrite hook observes no array at all.
        if (resultClass.contains("ParceledListSlice")) {
            Field listField = findField(result.getClass(), "mList", "list");
            if (listField == null) return null;
            try {
                listField.setAccessible(true);
                listField.set(result, rewriteActiveNotifications(listField.get(result)));
                return result;
            } catch (ReflectiveOperationException error) {
                throw new SecurityException("VIRTUAL_NOTIFICATION_RESULT_SLICE_UNSUPPORTED", error);
            }
        }
        if (result instanceof Iterable<?> iterable) {
            List<Object> owned = new ArrayList<>();
            int index = 0;
            for (Object value : iterable) {
                Object projected = projectActiveNotification(value, index++);
                if (projected != null) owned.add(projected);
            }
            return owned;
        }
        if (!result.getClass().isArray()) return result;
        List<Object> owned = new ArrayList<>();
        int count = Array.getLength(result);
        for (int index = 0; index < count; index++) {
            Object projected = projectActiveNotification(Array.get(result, index), index);
            if (projected != null) owned.add(projected);
        }
        Object projected = Array.newInstance(result.getClass().getComponentType(), owned.size());
        for (int index = 0; index < owned.size(); index++) {
            Array.set(projected, index, owned.get(index));
        }
        return projected;
    }

    private Object projectActiveNotification(Object value, int index) {
        if (value == null) return null;
        int hostId = intMember(value, "id", "mId", "getId");
        String hostTag = VirtualSystemServiceState.stringMember(value,
                "tag", "mTag", "getTag");
        VirtualSystemServiceAuthority.NotificationRecord record =
                findNotificationByHost(hostId, hostTag);
        if (record == null || !rewriteActiveNotification(value, record)) return null;
        return value;
    }

    private VirtualSystemServiceAuthority.NotificationRecord findNotificationByHost(
            int hostId, String hostTag) {
        String normalizedTag = hostTag == null ? "" : hostTag;
        for (VirtualSystemServiceAuthority.NotificationRecord record : state.notifications().records()) {
            if (record.hostId() == hostId && record.hostTag().equals(normalizedTag)
                    && "ACTIVE".equals(record.state())) return record;
        }
        return null;
    }

    private boolean rewriteActiveNotification(Object value,
            VirtualSystemServiceAuthority.NotificationRecord record) {
        Field packageField = findField(value.getClass(), "pkg", "mPkg");
        Field opPackageField = findField(value.getClass(), "opPkg", "mOpPkg");
        Field idField = findField(value.getClass(), "id", "mId");
        Field tagField = findField(value.getClass(), "tag", "mTag");
        if (packageField == null || idField == null || tagField == null) return false;
        try {
            packageField.setAccessible(true);
            idField.setAccessible(true);
            tagField.setAccessible(true);
            packageField.set(value, identity.packageName());
            if (opPackageField != null) {
                opPackageField.setAccessible(true);
                opPackageField.set(value, identity.packageName());
            }
            idField.set(value, record.guestId());
            tagField.set(value, record.guestTag());
            Object notification = member(value, "notification", "mNotification", "getNotification");
            if (notification != null && !record.channelId().isEmpty()) {
                Field channelField = findField(notification.getClass(), "mChannelId", "channelId");
                if (channelField != null) {
                    channelField.setAccessible(true);
                    channelField.set(notification, record.channelId());
                }
            }
            return true;
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            return false;
        }
    }

    private VirtualSystemServiceAuthority.NotificationRecord findNotification(int guestId, String guestTag) {
        String normalizedTag = guestTag == null ? "" : guestTag.trim();
        for (VirtualSystemServiceAuthority.NotificationRecord record : state.notifications().records()) {
            if (record.guestId() == guestId && record.guestTag().equals(normalizedTag)) return record;
        }
        return null;
    }
    private int notificationGuestId(Object[] arguments) {
        int index = notificationIdIndex(arguments);
        if (index < 0) throw new SecurityException("VIRTUAL_NOTIFICATION_ID_REQUIRED");
        return ((Number) arguments[index]).intValue();
    }
    private String notificationGuestTag(Object[] arguments) {
        if (arguments == null) return "";
        for (String value : stringArguments(arguments)) {
            if (value.equals(identity.packageName()) || value.equals(identity.hostPackageName())) continue;
            if (value.startsWith("android:") || value.contains("permission")) continue;
            return value;
        }
        return "";
    }
    private String notificationHostTag(String guestTag) {
        return "cs:u" + identity.virtualUserId() + ":g" + identity.generation() + ":"
                + (guestTag == null ? "" : guestTag.trim());
    }
    private String notificationChannelId(Object[] arguments) {
        if (arguments == null) return "";
        for (Object value : arguments) {
            if (value == null || !value.getClass().getName().contains("Notification")) continue;
            String id = VirtualSystemServiceState.stringMember(value, "mChannelId", "channelId", "getChannelId");
            if (!id.isEmpty()) return id;
        }
        return "";
    }
    private void rewriteNotificationId(Object[] arguments, List<Restore> restores, int hostId) {
        int index = notificationIdIndex(arguments);
        if (index < 0) throw new SecurityException("VIRTUAL_NOTIFICATION_ID_REQUIRED");
        Object original = arguments[index]; arguments[index] = hostId;
        restores.add(() -> arguments[index] = original);
    }
    private void rewriteNotificationTag(Object[] arguments, List<Restore> restores, String hostTag) {
        if (arguments == null) return;
        for (int index = 0; index < arguments.length; index++) {
            Object value = arguments[index];
            if (!(value instanceof String string)) continue;
            if (string.equals(identity.packageName()) || string.equals(identity.hostPackageName())) continue;
            if (string.contains("permission") || string.startsWith("android:")) continue;
            arguments[index] = hostTag;
            int restoreIndex = index;
            restores.add(() -> arguments[restoreIndex] = value);
            return;
        }
    }
    private Object cancelAllNotifications(Object delegate, Method intercepted) throws Throwable {
        List<VirtualSystemServiceAuthority.NotificationRecord> records = new ArrayList<>();
        for (VirtualSystemServiceAuthority.NotificationRecord record : state.notifications().records()) {
            if ("ACTIVE".equals(record.state())) records.add(record);
        }
        for (VirtualSystemServiceAuthority.NotificationRecord record : records) {
            invokeNotificationCancel(delegate, record);
            state.notifications().remove(record.guestId(), record.guestTag());
        }
        return defaultValue(intercepted.getReturnType());
    }
    private void invokeNotificationCancel(Object delegate,
                                          VirtualSystemServiceAuthority.NotificationRecord record) throws Throwable {
        Method selected = null;
        for (Method candidate : delegate.getClass().getMethods()) {
            String name = normalize(candidate.getName());
            if (!name.contains("cancel") || name.contains("all")) continue;
            boolean hasInt = false;
            for (Class<?> type : candidate.getParameterTypes()) if (type == int.class || type == Integer.class) hasInt = true;
            if (hasInt) { selected = candidate; break; }
        }
        if (selected == null) throw new SecurityException("VIRTUAL_NOTIFICATION_CANCEL_METHOD_UNAVAILABLE");
        selected.setAccessible(true);
        Object[] values = notificationCancelArguments(selected.getParameterTypes(), record);
        try { selected.invoke(delegate, values); }
        catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
    }
    private Object[] notificationCancelArguments(Class<?>[] types,
                                                  VirtualSystemServiceAuthority.NotificationRecord record) {
        Object[] values = new Object[types.length]; int stringIndex = 0; boolean idWritten = false;
        for (int index = 0; index < types.length; index++) {
            Class<?> type = types[index];
            if (type == String.class) {
                values[index] = stringIndex++ < 2 ? identity.hostPackageName() : record.hostTag();
            } else if ((type == int.class || type == Integer.class) && !idWritten) {
                values[index] = record.hostId(); idWritten = true;
            } else values[index] = defaultValue(type);
        }
        return values;
    }
    private Object cancelAllJobs(Object delegate, Method intercepted) throws Throwable {
        Method cancel = null;
        for (Method candidate : delegate.getClass().getMethods()) {
            if (normalize(candidate.getName()).equals("cancel") && candidate.getParameterCount() == 1
                    && (candidate.getParameterTypes()[0] == int.class || candidate.getParameterTypes()[0] == Integer.class)) {
                cancel = candidate; break;
            }
        }
        if (cancel == null) throw new SecurityException("VIRTUAL_JOB_CANCEL_METHOD_UNAVAILABLE");
        cancel.setAccessible(true);
        for (VirtualSystemServiceAuthority.JobRecord record : new ArrayList<>(state.jobs().records())) {
            try { cancel.invoke(delegate, record.hostId()); }
            catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
            state.jobs().remove(record.guestId());
        }
        return defaultValue(intercepted.getReturnType());
    }
    private void rewriteJobService(Object job, List<Restore> restores) {
        Field field = findField(job.getClass(), "service", "mService");
        if (field == null) return;
        try {
            field.setAccessible(true); Object original = field.get(job);
            Class<?> componentType = Class.forName("android.content.ComponentName");
            Object replacement = componentType.getConstructor(String.class, String.class)
                    .newInstance(identity.hostPackageName(), "com.warden.controlledsandbox.VirtualJobService");
            field.set(job, replacement); restores.add(() -> field.set(job, original));
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            throw new SecurityException("VIRTUAL_JOB_SERVICE_REWRITE_FAILED", error);
        }
    }
    private static boolean isChannelCreate(String name) {
        return (name.contains("create") || name.contains("update")) && (name.contains("channel") || name.contains("group"));
    }
    private static boolean isChannelDelete(String name) {
        return (name.contains("delete") || name.contains("remove")) && (name.contains("channel") || name.contains("group"));
    }
    private List<ChannelDraft> notificationChannelDrafts(Object[] arguments, String methodName) {
        List<ChannelDraft> out = new ArrayList<>();
        if (arguments == null) return out;
        java.util.Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Object value : arguments) collectChannelDrafts(value, out, visited, methodName);
        return out;
    }
    private void collectChannelDrafts(Object value, List<ChannelDraft> out, java.util.Set<Object> visited,
                                      String methodName) {
        if (value == null || !visited.add(value)) return;
        if (value instanceof Iterable<?> iterable) { for (Object item : iterable) collectChannelDrafts(item, out, visited, methodName); return; }
        if (value.getClass().isArray()) { int size = Array.getLength(value); for (int i=0;i<size;i++) collectChannelDrafts(Array.get(value,i), out, visited, methodName); return; }
        String className = value.getClass().getName();
        // NotificationManager transports channel collections through ParceledListSlice on
        // API 26-32. Traverse the wrapper so Guest channel IDs are rewritten transactionally.
        if (className.contains("ParceledListSlice")) {
            Field listField = findField(value.getClass(), "mList", "list");
            if (listField == null) return;
            try {
                listField.setAccessible(true);
                collectChannelDrafts(listField.get(value), out, visited, methodName);
            } catch (ReflectiveOperationException error) {
                throw new SecurityException("VIRTUAL_NOTIFICATION_CHANNEL_SLICE_UNSUPPORTED", error);
            }
            return;
        }
        if (!className.contains("NotificationChannel") && !className.contains("NotificationChannelGroup")) return;
        String id = VirtualSystemServiceState.stringMember(value, "mId", "id", "getId");
        if (id.isEmpty()) return;
        String kind = InvocationMethodMatcher.containsAny(className, "Group")
                || InvocationMethodMatcher.containsAny(methodName, "group") ? "GROUP" : "CHANNEL";
        String group = "CHANNEL".equals(kind)
                ? VirtualSystemServiceState.stringMember(value, "mGroup", "group", "getGroup") : "";
        out.add(new ChannelDraft(kind, id, group, value));
    }
    private static String firstChannelString(Object[] arguments) {
        if (arguments == null) return "";
        for (Object value : arguments) if (value instanceof String string && !string.trim().isEmpty()) return string.trim();
        return "";
    }
    private record ChannelDraft(String kind, String id, String groupId, Object payload) { }
    private record NotificationMetadata(String contentIntentTokenId, String deleteIntentTokenId,
            List<String> actionIntentTokenIds, boolean foregroundService, String foregroundServiceKey) { }

    private NamespaceRewrite rewriteNotificationId(Object[] arguments, List<Restore> restores,
                                                   boolean create) {
        int index = notificationIdIndex(arguments);
        if (index < 0) return NamespaceRewrite.absent();
        int guestId = ((Number) arguments[index]).intValue();
        VirtualSystemServiceState.NotificationState.Mapping mapping;
        if (create) mapping = state.notifications().ensure(guestId);
        else {
            Integer host = state.notifications().hostIdIfPresent(guestId);
            if (host == null) return new NamespaceRewrite(guestId, 0, false, false);
            mapping = new VirtualSystemServiceState.NotificationState.Mapping(host, false);
        }
        Object original = arguments[index]; arguments[index] = mapping.hostId();
        restores.add(() -> arguments[index] = original);
        return new NamespaceRewrite(guestId, mapping.hostId(), true, mapping.created());
    }

    private void rewriteNotificationTag(Object[] arguments, List<Restore> restores) {
        if (arguments == null) return;
        for (int index = 0; index < arguments.length; index++) {
            Object value = arguments[index];
            if (!(value instanceof String string)) continue;
            if (string.equals(identity.packageName()) || string.equals(identity.hostPackageName())) continue;
            if (string.contains("permission") || string.startsWith("android:")) continue;
            String namespaced = "cs:u" + identity.virtualUserId() + ":g" + identity.generation() + ":" + string;
            arguments[index] = namespaced;
            int restoreIndex = index;
            restores.add(() -> arguments[restoreIndex] = value);
            return;
        }
    }

    private void rewriteNotificationChannelFields(Object[] arguments, List<Restore> restores) {
        if (arguments == null) return;
        for (Object value : arguments) {
            if (value == null || !value.getClass().getName().contains("Notification")) continue;
            rewriteStringField(value, restores, "mChannelId", "channelId");
            rewriteStringField(value, restores, "mShortcutId", "shortcutId");
        }
    }

    private void rewriteChannelObjects(Object[] arguments, List<Restore> restores) {
        if (arguments == null) return;
        java.util.Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Object value : arguments) rewriteChannelObject(value, restores, visited, true);
    }

    private Object filterAndRestoreChannelResult(Object result) {
        if (result == null) return null;
        if (result instanceof Iterable<?> iterable) {
            List<Object> owned = new ArrayList<>();
            for (Object item : iterable) {
                Object restored = filterAndRestoreChannelResult(item);
                if (restored != null) owned.add(restored);
            }
            return owned;
        }
        if (result.getClass().isArray()) {
            Class<?> component = result.getClass().getComponentType();
            List<Object> owned = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(result);
            for (int index = 0; index < length; index++) {
                Object restored = filterAndRestoreChannelResult(java.lang.reflect.Array.get(result, index));
                if (restored != null) owned.add(restored);
            }
            Object filtered = java.lang.reflect.Array.newInstance(component, owned.size());
            for (int index = 0; index < owned.size(); index++) {
                java.lang.reflect.Array.set(filtered, index, owned.get(index));
            }
            return filtered;
        }
        if (!isChannelObject(result)) {
            Field listField = findField(result.getClass(), "mList", "list");
            if (listField == null) return null;
            try {
                listField.setAccessible(true);
                Object raw = listField.get(result);
                Object filtered = filterAndRestoreChannelResult(raw);
                listField.set(result, filtered == null ? new ArrayList<>() : filtered);
                return result;
            } catch (ReflectiveOperationException error) {
                throw new SecurityException("VIRTUAL_NOTIFICATION_CHANNEL_LIST_RESULT_UNSUPPORTED", error);
            }
        }
        if (!isOwnedChannelObject(result)) return null;
        java.util.Set<Object> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        rewriteChannelObject(result, new ArrayList<>(), visited, false);
        return result;
    }

    private static boolean isChannelObject(Object value) {
        String className = value.getClass().getName();
        return className.contains("NotificationChannel") || className.contains("NotificationChannelGroup");
    }

    private boolean isOwnedChannelObject(Object value) {
        if (!isChannelObject(value)) return false;
        Field idField = findField(value.getClass(), "mId", "id");
        if (idField == null) return false;
        try {
            idField.setAccessible(true);
            Object raw = idField.get(value);
            return raw instanceof String string && string.startsWith(channelNamespace(""));
        } catch (ReflectiveOperationException error) {
            throw new SecurityException("VIRTUAL_NOTIFICATION_CHANNEL_RESULT_UNSUPPORTED", error);
        }
    }

    private void rewriteChannelObject(Object value, List<Restore> restores, java.util.Set<Object> visited,
                                      boolean toHost) {
        if (value == null || !visited.add(value)) return;
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) rewriteChannelObject(item, restores, visited, toHost);
            return;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) rewriteChannelObject(
                    java.lang.reflect.Array.get(value, index), restores, visited, toHost);
            return;
        }
        String className = value.getClass().getName();
        // NotificationManager transports channel collections through ParceledListSlice on
        // API 26-32. Traverse the wrapper so Guest channel IDs are rewritten transactionally.
        if (className.contains("ParceledListSlice")) {
            Field listField = findField(value.getClass(), "mList", "list");
            if (listField == null) return;
            try {
                listField.setAccessible(true);
                rewriteChannelObject(listField.get(value), restores, visited, toHost);
            } catch (ReflectiveOperationException error) {
                throw new SecurityException("VIRTUAL_NOTIFICATION_CHANNEL_SLICE_UNSUPPORTED", error);
            }
            return;
        }
        if (!className.contains("NotificationChannel") && !className.contains("NotificationChannelGroup")) return;
        rewriteStringField(value, restores, toHost, "mId", "id");
        if (className.contains("NotificationChannel") && !className.contains("Group")) {
            rewriteStringField(value, restores, toHost, "mGroup", "group");
        }
    }

    private void rewriteStringField(Object value, List<Restore> restores, String... names) {
        rewriteStringField(value, restores, true, names);
    }

    private void rewriteStringField(Object value, List<Restore> restores, boolean toHost, String... names) {
        Field field = findField(value.getClass(), names);
        if (field == null) return;
        try {
            field.setAccessible(true);
            Object raw = field.get(value);
            if (!(raw instanceof String string) || string.isEmpty()) return;
            String updated = toHost ? channelNamespace(string) : stripChannelNamespace(string);
            if (updated.equals(string)) return;
            field.set(value, updated);
            if (toHost) restores.add(() -> field.set(value, raw));
        } catch (ReflectiveOperationException error) {
            throw new SecurityException("VIRTUAL_NOTIFICATION_CHANNEL_FIELD_UNSUPPORTED", error);
        }
    }

    private String channelNamespace(String value) {
        return VirtualNotificationNamespace.hostChannelId(identity.packageName(),
                identity.virtualUserId(), value);
    }
    private String stripChannelNamespace(String value) {
        return VirtualNotificationNamespace.guestChannelId(identity.packageName(),
                identity.virtualUserId(), value);
    }

    private void rewriteChannelStrings(Object[] arguments, List<Restore> restores, String methodName) {
        if (arguments == null || !(InvocationMethodMatcher.containsAny(methodName, "channel", "group"))) return;
        for (int index = 0; index < arguments.length; index++) {
            Object value = arguments[index];
            if (!(value instanceof String string)) continue;
            if (string.equals(identity.packageName()) || string.equals(identity.hostPackageName())) continue;
            String namespaced = channelNamespace(string);
            arguments[index] = namespaced;
            int restoreIndex = index;
            restores.add(() -> arguments[restoreIndex] = value);
        }
    }


    private static Object notificationPayload(Object[] arguments) {
        if (arguments == null) return null;
        for (Object value : arguments) {
            if (value == null) continue;
            String type = value.getClass().getName();
            if (type.contains("Notification") && !type.contains("NotificationChannel")
                    && !type.contains("NotificationChannelGroup")) return value;
        }
        return null;
    }

    private static int notificationIdIndex(Object[] arguments) {
        if (arguments == null) return -1;
        for (int index = 0; index < arguments.length - 1; index++) {
            if (arguments[index] instanceof Integer && arguments[index + 1] != null
                    && arguments[index + 1].getClass().getName().contains("Notification")) return index;
        }
        for (int index = 0; index < arguments.length; index++) if (arguments[index] instanceof Integer) return index;
        return -1;
    }

    private NotificationMetadata notificationMetadata(Object[] arguments, int guestId, String guestTag) {
        Object notification = notificationPayload(arguments);
        String content = pendingIntentTokenId(member(notification, "contentIntent", "mContentIntent", "getContentIntent"));
        String delete = pendingIntentTokenId(member(notification, "deleteIntent", "mDeleteIntent", "getDeleteIntent"));
        List<String> actions = new ArrayList<>();
        Object rawActions = member(notification, "actions", "mActions", "getActions");
        if (rawActions != null) {
            if (rawActions instanceof Iterable<?> iterable) {
                for (Object action : iterable) addToken(actions,
                        pendingIntentTokenId(member(action, "actionIntent", "mActionIntent", "getActionIntent")));
            } else if (rawActions.getClass().isArray()) {
                for (int index = 0; index < Array.getLength(rawActions); index++) addToken(actions,
                        pendingIntentTokenId(member(Array.get(rawActions, index), "actionIntent", "mActionIntent", "getActionIntent")));
            }
        }
        boolean foreground = (intMember(notification, "flags", "mFlags", "getFlags") & 0x40) != 0;
        String serviceKey = foreground ? identity.packageName() + ":u" + identity.virtualUserId()
                + ":" + guestId + ":" + (guestTag == null ? "" : guestTag.trim()) : "";
        return new NotificationMetadata(content, delete, java.util.Collections.unmodifiableList(actions),
                foreground, serviceKey);
    }
    private static void addToken(List<String> values, String token) {
        if (token != null && !token.isEmpty() && !values.contains(token)) values.add(token);
    }
    private static String pendingIntentTokenId(Object value) {
        return pendingIntentTokenId(value, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 0);
    }
    private static String pendingIntentTokenId(Object value, java.util.Set<Object> visited, int depth) {
        if (value == null || depth > 4 || !visited.add(value)) return "";
        if (value instanceof VirtualPendingIntentToken token) return token.persistentTokenId();
        Object target = member(value, "mTarget", "target", "getTarget");
        if (target != null && target != value) {
            String found = pendingIntentTokenId(target, visited, depth + 1);
            if (!found.isEmpty()) return found;
        }
        Object binder = member(value, "mBinder", "binder", "asBinder");
        if (binder != null && binder != value) return pendingIntentTokenId(binder, visited, depth + 1);
        return "";
    }
    private static Object member(Object value, String field, String alternateField, String method) {
        if (value == null) return null;
        Field found = findField(value.getClass(), field, alternateField);
        if (found != null) try { found.setAccessible(true); return found.get(value); } catch (ReflectiveOperationException ignored) { }
        try { Method candidate = value.getClass().getMethod(method); candidate.setAccessible(true); return candidate.invoke(value); }
        catch (ReflectiveOperationException ignored) { return null; }
    }
    private static int intMember(Object value, String field, String alternateField, String method) {
        Object raw = member(value, field, alternateField, method);
        return raw instanceof Number ? ((Number) raw).intValue() : 0;
    }

    private static boolean isHostHeldAlarmToken(Object token) {
        if (token == null) return false;
        String type = token.getClass().getName();
        return type.contains("PendingIntent") && !type.contains("Listener");
    }

    private static Object alarmToken(Object[] arguments) {
        if (arguments == null) throw new IllegalArgumentException("VIRTUAL_ALARM_ARGUMENTS_REQUIRED");
        for (int index = arguments.length - 1; index >= 0; index--) {
            Object value = arguments[index];
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) continue;
            String type = value.getClass().getName();
            if (type.contains("WorkSource") || type.contains("AlarmClockInfo") || type.contains("Bundle")) continue;
            return value;
        }
        throw new IllegalArgumentException("VIRTUAL_ALARM_TOKEN_REQUIRED");
    }

    private static long normalizedTrigger(Object[] arguments) {
        long trigger = System.currentTimeMillis();
        if (arguments != null) for (Object value : arguments) {
            if (value instanceof Long && ((Long) value) > 0L) { trigger = (Long) value; break; }
            if (value != null && value.getClass().getName().contains("AlarmClockInfo")) {
                Object clock = member(value, "triggerTime", "mTriggerTime", "getTriggerTime");
                if (clock instanceof Number number && number.longValue() > 0L) {
                    trigger = number.longValue(); break;
                }
            }
        }
        int type = -1;
        if (arguments != null) for (Object value : arguments) {
            if (value instanceof Integer) { type = (Integer) value; break; }
        }
        if (type == 2 || type == 3) {
            long elapsedNow = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            return System.currentTimeMillis() + Math.max(0L, trigger - elapsedNow);
        }
        return trigger;
    }

    private static Object firstAlarmClockInfo(Object[] arguments) {
        if (arguments != null) for (Object value : arguments) {
            if (value != null && value.getClass().getName().contains("AlarmClockInfo")) return value;
        }
        throw new IllegalArgumentException("VIRTUAL_ALARM_CLOCK_INFO_REQUIRED");
    }

    private Object nextAlarmClock() {
        VirtualSystemServiceAuthority.AlarmRecord selected = null;
        for (VirtualSystemServiceAuthority.AlarmRecord record : state.alarms().records()) {
            if (!record.alarmClock()) continue;
            if (selected == null || record.triggerAtMs() < selected.triggerAtMs()) selected = record;
        }
        if (selected == null) return null;
        try {
            Class<?> infoClass = Class.forName("android.app.AlarmManager$AlarmClockInfo");
            Class<?> pendingIntentClass = Class.forName("android.app.PendingIntent");
            java.lang.reflect.Constructor<?> constructor = infoClass.getConstructor(long.class, pendingIntentClass);
            Object showIntent = selected.alarmClockShowIntent();
            if (showIntent != null && !pendingIntentClass.isInstance(showIntent)) showIntent = null;
            return constructor.newInstance(selected.triggerAtMs(), showIntent);
        } catch (Throwable error) {
            com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(error);
            throw new SecurityException("VIRTUAL_ALARM_CLOCK_RESULT_UNSUPPORTED", error);
        }
    }
    private static long interval(Object[] arguments, long trigger) {
        boolean seen = false;
        if (arguments != null) for (Object value : arguments) {
            if (!(value instanceof Long)) continue;
            long candidate = (Long) value;
            if (!seen && candidate == trigger) { seen = true; continue; }
            if (candidate > 0L) return candidate;
        }
        return 0L;
    }
    private static boolean repeating(String name) { return name.contains("repeat"); }

    private static Object firstPayload(Object[] arguments) {
        if (arguments == null) return null;
        for (Object value : arguments) {
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) continue;
            return value;
        }
        return null;
    }
    private static Object firstAccount(Object[] arguments) {
        Object account = tryFirstAccount(arguments);
        if (account == null) throw new IllegalArgumentException("VIRTUAL_ACCOUNT_REQUIRED");
        return account;
    }
    private static Object tryFirstAccount(Object[] arguments) {
        if (arguments != null) for (Object value : arguments) {
            if (value == null) continue;
            String type = value.getClass().getName();
            if (type.endsWith(".Account") || type.endsWith("$Account")
                    || (!VirtualSystemServiceState.stringMember(value, "name", "mName", "getName").isEmpty()
                    && !VirtualSystemServiceState.stringMember(value, "type", "mType", "getType").isEmpty())) return value;
        }
        return null;
    }
    private static String firstAccountType(Object[] arguments) {
        if (arguments == null) return "";
        for (Object value : arguments) if (value instanceof String string
                && !string.contains(".") && !string.contains(":")) return string;
        return "";
    }
    private static String stringAfter(Object[] arguments, Object anchor) {
        boolean found = false;
        if (arguments != null) for (Object value : arguments) {
            if (value == anchor) { found = true; continue; }
            if (found && value instanceof String) return (String) value;
        }
        return "";
    }
    private static List<String> stringsAfter(Object[] arguments, Object anchor) {
        boolean found = false; List<String> result = new ArrayList<>();
        if (arguments != null) for (Object value : arguments) {
            if (value == anchor) { found = true; continue; }
            if (found && value instanceof String) result.add((String) value);
        }
        return result;
    }
    private static String[] stringArguments(Object[] arguments) {
        List<String> result = new ArrayList<>();
        if (arguments != null) for (Object value : arguments) if (value instanceof String) result.add((String) value);
        return result.toArray(new String[0]);
    }
    private static Object firstObjectWithMethod(Object[] arguments, String method) {
        if (arguments != null) for (Object value : arguments) {
            if (value == null) continue;
            try { Method found = value.getClass().getMethod(method); found.setAccessible(true); return value; } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }
    private static int intResult(Object value, String method) {
        try { Method found = value.getClass().getMethod(method); found.setAccessible(true); Object result = found.invoke(value); return ((Number) result).intValue(); }
        catch (ReflectiveOperationException error) { throw new IllegalArgumentException("Cannot read integer method " + method, error); }
    }
    private static int firstIntIndex(Object[] arguments) {
        if (arguments != null) for (int index = 0; index < arguments.length; index++) if (arguments[index] instanceof Integer) return index;
        return -1;
    }
    private static Field findField(Class<?> type, String... names) {
        try { return VirtualSystemServiceState.findField(type, names); } catch (NoSuchFieldException error) { return null; }
    }
    private static Object invoke(Object target, String methodName) {
        if (target == null) return null;
        try { Method method = target.getClass().getMethod(methodName); method.setAccessible(true); return method.invoke(target); }
        catch (Throwable ignored) { com.warden.controlledsandbox.framework.capability.FatalErrorPolicy.rethrowIfFatal(ignored); return null; }
    }
    private static Object defaultValue(Class<?> type) {
        if (type == void.class) return null;
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }

    private record NamespaceRewrite(int guestId, int hostId, boolean present, boolean created) {
        static NamespaceRewrite absent() { return new NamespaceRewrite(0, 0, false, false); }
    }

    @FunctionalInterface private interface Restore { void restore() throws Exception; }
    @FunctionalInterface public interface ResultRewrite { Object rewrite(Object result); }
    @FunctionalInterface public interface DirectInvocation { Object invoke(Object delegate, Method interceptedMethod) throws Throwable; }

    public static final class Call implements AutoCloseable {
        private static final Call PASS = new Call(false, null, List.of(), result -> result, () -> { }, null);
        private final boolean handled;
        private final Object result;
        private final List<Restore> restores;
        private final ResultRewrite resultRewrite;
        private final Runnable failureAction;
        private final DirectInvocation directInvocation;

        private Call(boolean handled, Object result, List<Restore> restores,
                     ResultRewrite resultRewrite, Runnable failureAction, DirectInvocation directInvocation) {
            this.handled = handled; this.result = result;
            this.restores = restores == null ? List.of() : List.copyOf(restores);
            this.resultRewrite = resultRewrite == null ? value -> value : resultRewrite;
            this.failureAction = failureAction == null ? () -> { } : failureAction;
            this.directInvocation = directInvocation;
        }
        public static Call passThrough() { return PASS; }
        static Call passThrough(List<Restore> restores) {
            return new Call(false, null, restores, result -> result, () -> { }, null);
        }
        static Call passThroughWithResult(ResultRewrite rewrite) {
            return new Call(false, null, List.of(), rewrite, () -> { }, null);
        }
        static Call passThroughLifecycle(List<Restore> restores, ResultRewrite rewrite, Runnable failure) {
            return new Call(false, null, restores, rewrite, failure, null);
        }
        static Call handled(Object value) {
            return new Call(true, value, List.of(), result -> result, () -> { }, null);
        }
        static Call direct(DirectInvocation invocation) {
            return new Call(false, null, List.of(), result -> result, () -> { },
                    java.util.Objects.requireNonNull(invocation, "invocation"));
        }
        public boolean handled() { return handled; }
        public boolean direct() { return directInvocation != null; }
        public Object invokeDirect(Object delegate, Method method) throws Throwable { return directInvocation.invoke(delegate, method); }
        public Object result() { return result; }
        public Object rewriteResult(Object value) { return resultRewrite.rewrite(value); }
        public void onFailure() { failureAction.run(); }
        @Override public void close() {
            for (int index = restores.size() - 1; index >= 0; index--) {
                try { restores.get(index).restore(); } catch (Exception ignored) { }
            }
        }
    }

}
