package com.warden.controlledsandbox.runtime.component.activity;

import com.warden.controlledsandbox.domain.persistence.DurableAtomicFile;

import com.warden.controlledsandbox.framework.activity.ActivityIdentity;
import com.warden.controlledsandbox.framework.activity.ActivityRestoreSnapshot;
import com.warden.controlledsandbox.framework.activity.ActivityResultRegistration;
import com.warden.controlledsandbox.framework.activity.PendingActivityResultSnapshot;
import com.warden.controlledsandbox.framework.activity.ActivityTaskCheckpoint;
import com.warden.controlledsandbox.framework.activity.ActivityInfoTaskFlags;
import com.warden.controlledsandbox.framework.activity.DocumentLaunchMode;
import com.warden.controlledsandbox.framework.activity.LaunchMode;
import com.warden.controlledsandbox.framework.activity.SavedActivityState;
import com.warden.controlledsandbox.framework.activity.TaskQuerySnapshot;
import com.warden.controlledsandbox.framework.activity.TaskRestoreSnapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32;

/** CRC-protected atomic persistence for the Broker-owned Activity task checkpoint. */
public final class ActivityTaskCheckpointStore {
    private static final int MAGIC = 0x43534154; // CSAT
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TASKS = 256;
    private static final int MAX_ACTIVITIES = 2048;
    private static final int MAX_RECENTS = 256;
    private static final int MAX_SAVED_STATE_ENTRIES = 128;
    private static final int MAX_SAVED_STATE_PAYLOAD_BYTES =
            com.warden.controlledsandbox.framework.activity.SavedActivityState.MAX_PAYLOAD_BYTES;
    private static final int MAX_RESULT_REGISTRATIONS = 128;
    private static final int MAX_PENDING_RESULT_LINKS = 128;

    private final Path file;

    public ActivityTaskCheckpointStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    public synchronized Optional<ActivityTaskCheckpoint> load() {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            long size = Files.size(file);
            if (size < 20 || size > MAX_FILE_BYTES) {
                throw new IllegalStateException("ACTIVITY_TASK_CHECKPOINT_SIZE_INVALID");
            }
            byte[] container = Files.readAllBytes(file);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(container))) {
                if (input.readInt() != MAGIC) {
                    throw new IllegalStateException("ACTIVITY_TASK_CHECKPOINT_MAGIC_INVALID");
                }
                int payloadLength = input.readInt();
                if (payloadLength < 1 || payloadLength > MAX_FILE_BYTES - 16
                        || payloadLength != container.length - 16) {
                    throw new IllegalStateException("ACTIVITY_TASK_CHECKPOINT_LENGTH_INVALID");
                }
                byte[] payload = new byte[payloadLength];
                input.readFully(payload);
                long expectedCrc = input.readLong();
                CRC32 crc = new CRC32();
                crc.update(payload);
                if (expectedCrc != crc.getValue()) {
                    throw new IllegalStateException("ACTIVITY_TASK_CHECKPOINT_CRC_INVALID");
                }
                return Optional.of(decode(payload));
            }
        } catch (IOException error) {
            throw new IllegalStateException("ACTIVITY_TASK_CHECKPOINT_READ_FAILED", error);
        }
    }

    public synchronized void save(ActivityTaskCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        byte[] payload = encode(checkpoint);
        if (payload.length > MAX_FILE_BYTES - 16) {
            throw new IllegalArgumentException("Activity task checkpoint exceeds file limit");
        }
        CRC32 crc = new CRC32();
        crc.update(payload);
        Path parent = file.getParent();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (parent != null) Files.createDirectories(parent);
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporary))) {
                output.writeInt(MAGIC);
                output.writeInt(payload.length);
                output.write(payload);
                output.writeLong(crc.getValue());
                output.flush();
            }
            DurableAtomicFile.replacePreparedAcknowledged(temporary, file);
        } catch (IOException error) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new IllegalStateException("ACTIVITY_TASK_CHECKPOINT_WRITE_FAILED", error);
        }
    }

    public synchronized Path quarantineCorrupt() {
        if (!Files.exists(file)) return file;
        Path quarantine = file.resolveSibling(file.getFileName() + ".corrupt");
        try {
            Files.move(file, quarantine, StandardCopyOption.REPLACE_EXISTING);
            return quarantine;
        } catch (IOException error) {
            throw new IllegalStateException("ACTIVITY_TASK_CHECKPOINT_QUARANTINE_FAILED", error);
        }
    }

    public Path file() {
        return file;
    }

    private static byte[] encode(ActivityTaskCheckpoint checkpoint) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(checkpoint.schemaVersion());
                output.writeInt(checkpoint.nextTaskId());
                output.writeLong(checkpoint.nextNewIntentSequence());
                output.writeLong(checkpoint.nextConfigurationSequence());
                output.writeLong(checkpoint.nextActivationSequence());
                output.writeInt(checkpoint.transportDeliveryCount());
                output.writeInt(checkpoint.tasks().size());
                for (TaskRestoreSnapshot task : checkpoint.tasks()) {
                    writeTask(output, task, checkpoint.schemaVersion());
                }
                output.writeInt(checkpoint.recentTasks().size());
                for (TaskQuerySnapshot recent : checkpoint.recentTasks()) {
                    writeRecent(output, recent, checkpoint.schemaVersion());
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ActivityTaskCheckpoint decode(byte[] payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int schemaVersion = input.readInt();
            if (schemaVersion != ActivityTaskCheckpoint.LEGACY_SCHEMA
                    && schemaVersion != ActivityTaskCheckpoint.PREVIOUS_SCHEMA
                    && schemaVersion != ActivityTaskCheckpoint.STABLE_ACTIVITY_SCHEMA
                    && schemaVersion != ActivityTaskCheckpoint.TASK_RESET_SCHEMA
                    && schemaVersion != ActivityTaskCheckpoint.BASE_INTENT_SCHEMA
                    && schemaVersion != ActivityTaskCheckpoint.TASK_TIME_SCHEMA
                    && schemaVersion != ActivityTaskCheckpoint.CURRENT_SCHEMA) {
                throw new IllegalStateException(
                        "ACTIVITY_TASK_CHECKPOINT_SCHEMA_UNSUPPORTED:" + schemaVersion);
            }
            int nextTaskId = input.readInt();
            long nextNewIntent = input.readLong();
            long nextConfiguration = input.readLong();
            long nextActivation = input.readLong();
            int transportDeliveries = input.readInt();
            int taskCount = boundedCount(input.readInt(), MAX_TASKS, "task");
            List<TaskRestoreSnapshot> tasks = new ArrayList<>(taskCount);
            int activityCount = 0;
            for (int index = 0; index < taskCount; index++) {
                TaskRestoreSnapshot task = readTask(input, schemaVersion);
                activityCount = Math.addExact(activityCount, task.activities().size());
                if (activityCount > MAX_ACTIVITIES) {
                    throw new IllegalStateException("ACTIVITY_TASK_CHECKPOINT_ACTIVITY_LIMIT");
                }
                tasks.add(task);
            }
            int recentCount = boundedCount(input.readInt(), MAX_RECENTS, "recent task");
            List<TaskQuerySnapshot> recents = new ArrayList<>(recentCount);
            for (int index = 0; index < recentCount; index++) {
                recents.add(readRecent(input, schemaVersion));
            }
            if (input.available() != 0) {
                throw new IllegalStateException("ACTIVITY_TASK_CHECKPOINT_TRAILING_DATA");
            }
            return new ActivityTaskCheckpoint(
                    schemaVersion,
                    nextTaskId,
                    nextNewIntent,
                    nextConfiguration,
                    nextActivation,
                    transportDeliveries,
                    tasks,
                    recents);
        } catch (IOException | ArithmeticException error) {
            throw new IllegalStateException("ACTIVITY_TASK_CHECKPOINT_DECODE_FAILED", error);
        }
    }

    private static void writeTask(
            DataOutputStream output,
            TaskRestoreSnapshot task,
            int schemaVersion) throws IOException {
        output.writeInt(task.taskId());
        output.writeInt(task.virtualUserId());
        output.writeUTF(task.packageName());
        if (schemaVersion >= ActivityTaskCheckpoint.PREVIOUS_SCHEMA) {
            output.writeUTF(task.packageRevision());
        }
        output.writeUTF(task.affinity());
        output.writeBoolean(task.documentTask());
        if (schemaVersion >= ActivityTaskCheckpoint.PREVIOUS_SCHEMA) {
            output.writeUTF(task.documentLaunchMode().name());
            output.writeUTF(task.documentKey());
        }
        output.writeInt(task.rootIntentFlags());
        output.writeBoolean(task.excludedFromRecents());
        output.writeBoolean(task.retainInRecents());
        output.writeLong(task.lastActiveSequence());
        output.writeLong(task.moveToFrontCount());
        if (schemaVersion >= ActivityTaskCheckpoint.TASK_TIME_SCHEMA) {
            output.writeLong(task.lastActiveTimeMillis());
        }
        if (schemaVersion >= ActivityTaskCheckpoint.BASE_INTENT_SCHEMA) {
            output.writeUTF(task.baseIntentAction());
            output.writeUTF(task.baseIntentDataUri());
            output.writeUTF(task.baseIntentMimeType());
            output.writeInt(task.baseIntentCategories().size());
            for (String category : task.baseIntentCategories()) output.writeUTF(category);
        }
        output.writeInt(task.activities().size());
        for (ActivityRestoreSnapshot activity : task.activities()) {
            writeActivity(output, activity, schemaVersion);
        }
    }

    private static TaskRestoreSnapshot readTask(DataInputStream input, int schemaVersion) throws IOException {
        int taskId = input.readInt();
        int virtualUserId = input.readInt();
        String packageName = input.readUTF();
        String packageRevision = schemaVersion >= ActivityTaskCheckpoint.PREVIOUS_SCHEMA
                ? input.readUTF() : "legacy";
        String affinity = input.readUTF();
        boolean documentTask = input.readBoolean();
        DocumentLaunchMode documentLaunchMode = documentTask
                ? DocumentLaunchMode.ALWAYS : DocumentLaunchMode.NONE;
        String documentKey = "";
        if (schemaVersion >= ActivityTaskCheckpoint.PREVIOUS_SCHEMA) {
            try {
                documentLaunchMode = DocumentLaunchMode.valueOf(input.readUTF());
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException(
                        "ACTIVITY_TASK_CHECKPOINT_DOCUMENT_MODE_INVALID", error);
            }
            documentKey = input.readUTF();
        }
        int rootIntentFlags = input.readInt();
        boolean excluded = input.readBoolean();
        boolean retain = input.readBoolean();
        long lastActive = input.readLong();
        long moveCount = input.readLong();
        long lastActiveTimeMillis = schemaVersion >= ActivityTaskCheckpoint.TASK_TIME_SCHEMA
                ? input.readLong() : 0L;
        String baseIntentAction = "";
        String baseIntentDataUri = "";
        String baseIntentMimeType = "";
        List<String> baseIntentCategories = List.of();
        if (schemaVersion >= ActivityTaskCheckpoint.BASE_INTENT_SCHEMA) {
            baseIntentAction = input.readUTF();
            baseIntentDataUri = input.readUTF();
            baseIntentMimeType = input.readUTF();
            int categoryCount = boundedCount(input.readInt(), 64, "base Intent category");
            ArrayList<String> categories = new ArrayList<>(categoryCount);
            for (int index = 0; index < categoryCount; index++) categories.add(input.readUTF());
            baseIntentCategories = categories;
        }
        int activityCount = boundedCount(input.readInt(), MAX_ACTIVITIES, "Activity");
        List<ActivityRestoreSnapshot> activities = new ArrayList<>(activityCount);
        for (int index = 0; index < activityCount; index++) {
            activities.add(readActivity(input, schemaVersion));
        }
        return new TaskRestoreSnapshot(taskId, virtualUserId, packageName, packageRevision,
                affinity, documentTask, documentLaunchMode, documentKey, rootIntentFlags,
                excluded, retain, lastActive, moveCount, baseIntentAction, baseIntentDataUri,
                baseIntentMimeType, baseIntentCategories, lastActiveTimeMillis, activities);
    }

    private static void writeActivity(
            DataOutputStream output,
            ActivityRestoreSnapshot activity,
            int schemaVersion) throws IOException {
        output.writeInt(activity.identity().virtualUserId());
        output.writeUTF(activity.identity().packageName());
        output.writeUTF(activity.identity().componentName());
        if (schemaVersion >= ActivityTaskCheckpoint.STABLE_ACTIVITY_SCHEMA) {
            output.writeUTF(activity.stableId());
        }
        output.writeUTF(activity.launchMode().name());
        output.writeUTF(activity.processName());
        output.writeLong(activity.processGeneration());
        output.writeUTF(activity.resultWho());
        output.writeInt(activity.requestCode());
        output.writeInt(activity.launchFlags());
        if (schemaVersion >= ActivityTaskCheckpoint.TASK_RESET_SCHEMA) {
            output.writeInt(activity.activityInfoFlags());
        }
        output.writeBoolean(activity.noHistory());
        if (schemaVersion >= ActivityTaskCheckpoint.ACTIVITY_AFFINITY_SCHEMA) {
            output.writeUTF(activity.taskAffinity());
            output.writeBoolean(activity.allowTaskReparenting());
        }
        output.writeLong(activity.newIntentCount());
        output.writeLong(activity.recreationCount());
        output.writeBoolean(activity.savedState() != null);
        if (activity.savedState() != null) {
            output.writeLong(activity.savedState().version());
            output.writeInt(activity.savedState().values().size());
            for (Map.Entry<String, String> entry : activity.savedState().values().entrySet()) {
                output.writeUTF(entry.getKey());
                output.writeUTF(entry.getValue());
            }
            if (schemaVersion >= ActivityTaskCheckpoint.SAVED_STATE_PAYLOAD_SCHEMA) {
                writePayload(output, activity.savedState().bundlePayload());
                writePayload(output, activity.savedState().persistableBundlePayload());
            }
        }
        output.writeLong(activity.configurationCount());
        output.writeUTF(activity.lastConfigurationToken());
        if (schemaVersion >= ActivityTaskCheckpoint.STABLE_ACTIVITY_SCHEMA) {
            output.writeInt(activity.resultRegistrations().size());
            for (ActivityResultRegistration registration : activity.resultRegistrations()) {
                output.writeUTF(registration.key());
                output.writeInt(registration.requestCode());
            }
            output.writeInt(activity.pendingResultLinks().size());
            for (PendingActivityResultSnapshot link : activity.pendingResultLinks()) {
                output.writeUTF(link.callerStableId());
                output.writeUTF(link.resultWho());
                output.writeUTF(link.registryKey());
                output.writeInt(link.requestCode());
                output.writeUTF(link.intentSenderToken());
            }
        }
    }

    private static ActivityRestoreSnapshot readActivity(
            DataInputStream input,
            int schemaVersion) throws IOException {
        ActivityIdentity identity = new ActivityIdentity(input.readInt(), input.readUTF(), input.readUTF());
        String stableId = schemaVersion >= ActivityTaskCheckpoint.STABLE_ACTIVITY_SCHEMA
                ? input.readUTF() : "";
        LaunchMode launchMode;
        try {
            launchMode = LaunchMode.valueOf(input.readUTF());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("ACTIVITY_TASK_CHECKPOINT_LAUNCH_MODE_INVALID", error);
        }
        String processName = input.readUTF();
        long processGeneration = input.readLong();
        String resultWho = input.readUTF();
        int requestCode = input.readInt();
        int launchFlags = input.readInt();
        int activityInfoFlags = schemaVersion >= ActivityTaskCheckpoint.TASK_RESET_SCHEMA
                ? input.readInt() : 0;
        boolean noHistory = input.readBoolean();
        String taskAffinity = identity.packageName();
        boolean allowTaskReparenting = ActivityInfoTaskFlags.has(
                activityInfoFlags, ActivityInfoTaskFlags.ALLOW_TASK_REPARENTING);
        if (schemaVersion >= ActivityTaskCheckpoint.ACTIVITY_AFFINITY_SCHEMA) {
            taskAffinity = input.readUTF();
            allowTaskReparenting = input.readBoolean();
        }
        long newIntentCount = input.readLong();
        long recreationCount = input.readLong();
        SavedActivityState savedState = null;
        if (input.readBoolean()) {
            long version = input.readLong();
            int count = boundedCount(input.readInt(), MAX_SAVED_STATE_ENTRIES, "saved state");
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) values.put(input.readUTF(), input.readUTF());
            byte[] bundlePayload = new byte[0];
            byte[] persistableBundlePayload = new byte[0];
            if (schemaVersion >= ActivityTaskCheckpoint.SAVED_STATE_PAYLOAD_SCHEMA) {
                bundlePayload = readPayload(input, "bundle saved-state payload");
                persistableBundlePayload = readPayload(input, "persistable saved-state payload");
            }
            savedState = new SavedActivityState(
                    version, values, bundlePayload, persistableBundlePayload);
        }
        long configurationCount = input.readLong();
        String lastConfigurationToken = input.readUTF();
        List<ActivityResultRegistration> registrations = new ArrayList<>();
        List<PendingActivityResultSnapshot> pendingLinks = new ArrayList<>();
        if (schemaVersion >= ActivityTaskCheckpoint.STABLE_ACTIVITY_SCHEMA) {
            int registrationCount = boundedCount(
                    input.readInt(), MAX_RESULT_REGISTRATIONS, "result registration");
            for (int index = 0; index < registrationCount; index++) {
                registrations.add(new ActivityResultRegistration(input.readUTF(), input.readInt()));
            }
            int pendingCount = boundedCount(
                    input.readInt(), MAX_PENDING_RESULT_LINKS, "pending result link");
            for (int index = 0; index < pendingCount; index++) {
                pendingLinks.add(new PendingActivityResultSnapshot(
                        input.readUTF(), input.readUTF(), input.readUTF(), input.readInt(), input.readUTF()));
            }
        }
        return new ActivityRestoreSnapshot(identity, stableId, launchMode, processName,
                processGeneration, resultWho, requestCode, launchFlags, activityInfoFlags, noHistory,
                newIntentCount, recreationCount, savedState, configurationCount,
                lastConfigurationToken, registrations, pendingLinks, taskAffinity,
                allowTaskReparenting);
    }

    private static void writeRecent(
            DataOutputStream output,
            TaskQuerySnapshot task,
            int schemaVersion) throws IOException {
        output.writeInt(task.taskId());
        output.writeInt(task.virtualUserId());
        output.writeUTF(task.packageName());
        if (schemaVersion >= ActivityTaskCheckpoint.PREVIOUS_SCHEMA) {
            output.writeUTF(task.packageRevision());
        }
        output.writeUTF(task.affinity());
        output.writeBoolean(task.documentTask());
        if (schemaVersion >= ActivityTaskCheckpoint.PREVIOUS_SCHEMA) {
            output.writeUTF(task.documentLaunchMode().name());
            output.writeUTF(task.documentKey());
        }
        output.writeBoolean(task.active());
        output.writeBoolean(task.excludedFromRecents());
        output.writeBoolean(task.retainInRecents());
        output.writeInt(task.activityCount());
        output.writeUTF(task.baseComponentName());
        output.writeUTF(task.topComponentName());
        output.writeLong(task.lastActiveSequence());
        output.writeLong(task.moveToFrontCount());
        if (schemaVersion >= ActivityTaskCheckpoint.TASK_TIME_SCHEMA) {
            output.writeLong(task.lastActiveTimeMillis());
        }
        if (schemaVersion >= ActivityTaskCheckpoint.BASE_INTENT_SCHEMA) {
            output.writeInt(task.baseIntentFlags());
            output.writeUTF(task.baseIntentAction());
            output.writeUTF(task.baseIntentDataUri());
            output.writeUTF(task.baseIntentMimeType());
            output.writeInt(task.baseIntentCategories().size());
            for (String category : task.baseIntentCategories()) output.writeUTF(category);
        }
    }

    private static TaskQuerySnapshot readRecent(DataInputStream input, int schemaVersion)
            throws IOException {
        int taskId = input.readInt();
        int virtualUserId = input.readInt();
        String packageName = input.readUTF();
        String packageRevision = schemaVersion >= ActivityTaskCheckpoint.PREVIOUS_SCHEMA
                ? input.readUTF() : "legacy";
        String affinity = input.readUTF();
        boolean documentTask = input.readBoolean();
        DocumentLaunchMode documentLaunchMode = documentTask
                ? DocumentLaunchMode.ALWAYS : DocumentLaunchMode.NONE;
        String documentKey = "";
        if (schemaVersion >= ActivityTaskCheckpoint.PREVIOUS_SCHEMA) {
            try {
                documentLaunchMode = DocumentLaunchMode.valueOf(input.readUTF());
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException(
                        "ACTIVITY_TASK_CHECKPOINT_DOCUMENT_MODE_INVALID", error);
            }
            documentKey = input.readUTF();
        }
        boolean active = input.readBoolean();
        boolean excluded = input.readBoolean();
        boolean retain = input.readBoolean();
        int activityCount = input.readInt();
        String baseComponentName = input.readUTF();
        String topComponentName = input.readUTF();
        long lastActiveSequence = input.readLong();
        long moveToFrontCount = input.readLong();
        long lastActiveTimeMillis = schemaVersion >= ActivityTaskCheckpoint.TASK_TIME_SCHEMA
                ? input.readLong() : 0L;
        int baseIntentFlags = 0;
        String baseIntentAction = "";
        String baseIntentDataUri = "";
        String baseIntentMimeType = "";
        List<String> baseIntentCategories = List.of();
        if (schemaVersion >= ActivityTaskCheckpoint.BASE_INTENT_SCHEMA) {
            baseIntentFlags = input.readInt();
            baseIntentAction = input.readUTF();
            baseIntentDataUri = input.readUTF();
            baseIntentMimeType = input.readUTF();
            int categoryCount = boundedCount(input.readInt(), 64, "recent base Intent category");
            ArrayList<String> categories = new ArrayList<>(categoryCount);
            for (int index = 0; index < categoryCount; index++) categories.add(input.readUTF());
            baseIntentCategories = categories;
        }
        return new TaskQuerySnapshot(
                taskId, virtualUserId, packageName, packageRevision, affinity,
                documentTask, documentLaunchMode, documentKey,
                active, excluded, retain, activityCount, baseComponentName, topComponentName,
                lastActiveSequence, moveToFrontCount,
                baseIntentFlags, baseIntentAction, baseIntentDataUri, baseIntentMimeType,
                baseIntentCategories, lastActiveTimeMillis);
    }

    private static int boundedCount(int value, int maximum, String label) {
        if (value < 0 || value > maximum) {
            throw new IllegalStateException("ACTIVITY_TASK_CHECKPOINT_" + label.toUpperCase().replace(' ', '_')
                    + "_COUNT_INVALID");
        }
        return value;
    }

    private static void writePayload(DataOutputStream output, byte[] payload) throws IOException {
        byte[] value = payload == null ? new byte[0] : payload;
        if (value.length > MAX_SAVED_STATE_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("saved-state payload is too large");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readPayload(DataInputStream input, String label) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_SAVED_STATE_PAYLOAD_BYTES) {
            throw new IllegalStateException("ACTIVITY_TASK_CHECKPOINT_"
                    + label.toUpperCase().replace(' ', '_') + "_LENGTH_INVALID");
        }
        byte[] payload = new byte[length];
        input.readFully(payload);
        return payload;
    }
}
