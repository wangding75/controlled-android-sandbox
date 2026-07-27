package com.warden.controlledsandbox.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Immutable resource counters returned by the typed runtime-status contract. */
public final class RuntimeStatusSnapshot implements Parcelable {
    public static final Creator<RuntimeStatusSnapshot> CREATOR = new Creator<>() {
        @Override public RuntimeStatusSnapshot createFromParcel(Parcel source) {
            return builder()
                    .slots(source.readInt(), source.readInt())
                    .sessions(source.readInt())
                    .activity(source.readInt(), source.readInt(), source.readInt())
                    .services(source.readInt())
                    .providerResources(source.readInt(), source.readInt(), source.readInt(),
                            source.readInt(), source.readInt(), source.readInt())
                    .providerAudit(source.readInt(), source.readLong(), source.readLong())
                    .dynamicReceivers(source.readInt())
                    .manifestReceivers(source.readInt(), source.readInt(), source.readInt())
                    .receiverIndexes(source.readInt(), source.readInt(), source.readInt(),
                            source.readInt(), source.readInt(), source.readInt())
                    .build();
        }

        @Override public RuntimeStatusSnapshot[] newArray(int size) {
            return new RuntimeStatusSnapshot[size];
        }
    };

    private final int slotCapacity;
    private final int slotUsed;
    private final int sessionCount;
    private final int pendingRoutes;
    private final int taskCount;
    private final int activityCount;
    private final int serviceRecordCount;
    private final int uriGrantCount;
    private final int providerCursorAccessCount;
    private final int providerFileLeaseCount;
    private final int providerObserverCount;
    private final int providerAuthorityCount;
    private final int providerResourceCount;
    private final int providerAuditRetainedCount;
    private final long providerAuditSuccessCount;
    private final long providerAuditFailureCount;
    private final int dynamicReceiverCount;
    private final int dynamicReceiverActionSubscriptionCount;
    private final int manifestReceiverPackageCount;
    private final int manifestReceiverCount;
    private final int manifestReceiverBindingCount;
    private final int manifestReceiverActionIndexKeyCount;
    private final int manifestReceiverActionIndexEntryCount;
    private final int manifestReceiverStartupTemplateCount;
    private final int orderedReceiverPendingCount;
    private final int receiverResourceCount;

    private RuntimeStatusSnapshot(Builder builder) {
        slotCapacity = ContractChecks.nonNegative(builder.slotCapacity, "slotCapacity");
        slotUsed = ContractChecks.nonNegative(builder.slotUsed, "slotUsed");
        sessionCount = ContractChecks.nonNegative(builder.sessionCount, "sessionCount");
        pendingRoutes = ContractChecks.nonNegative(builder.pendingRoutes, "pendingRoutes");
        taskCount = ContractChecks.nonNegative(builder.taskCount, "taskCount");
        activityCount = ContractChecks.nonNegative(builder.activityCount, "activityCount");
        serviceRecordCount = ContractChecks.nonNegative(builder.serviceRecordCount, "serviceRecordCount");
        uriGrantCount = ContractChecks.nonNegative(builder.uriGrantCount, "uriGrantCount");
        providerCursorAccessCount = ContractChecks.nonNegative(builder.providerCursorAccessCount, "providerCursorAccessCount");
        providerFileLeaseCount = ContractChecks.nonNegative(builder.providerFileLeaseCount, "providerFileLeaseCount");
        providerObserverCount = ContractChecks.nonNegative(builder.providerObserverCount, "providerObserverCount");
        providerAuthorityCount = ContractChecks.nonNegative(builder.providerAuthorityCount, "providerAuthorityCount");
        providerResourceCount = ContractChecks.nonNegative(builder.providerResourceCount, "providerResourceCount");
        providerAuditRetainedCount = ContractChecks.nonNegative(builder.providerAuditRetainedCount, "providerAuditRetainedCount");
        providerAuditSuccessCount = ContractChecks.nonNegative(builder.providerAuditSuccessCount, "providerAuditSuccessCount");
        providerAuditFailureCount = ContractChecks.nonNegative(builder.providerAuditFailureCount, "providerAuditFailureCount");
        dynamicReceiverCount = ContractChecks.nonNegative(builder.dynamicReceiverCount, "dynamicReceiverCount");
        dynamicReceiverActionSubscriptionCount = ContractChecks.nonNegative(
                builder.dynamicReceiverActionSubscriptionCount, "dynamicReceiverActionSubscriptionCount");
        manifestReceiverPackageCount = ContractChecks.nonNegative(builder.manifestReceiverPackageCount, "manifestReceiverPackageCount");
        manifestReceiverCount = ContractChecks.nonNegative(builder.manifestReceiverCount, "manifestReceiverCount");
        manifestReceiverBindingCount = ContractChecks.nonNegative(builder.manifestReceiverBindingCount, "manifestReceiverBindingCount");
        manifestReceiverActionIndexKeyCount = ContractChecks.nonNegative(
                builder.manifestReceiverActionIndexKeyCount, "manifestReceiverActionIndexKeyCount");
        manifestReceiverActionIndexEntryCount = ContractChecks.nonNegative(
                builder.manifestReceiverActionIndexEntryCount, "manifestReceiverActionIndexEntryCount");
        manifestReceiverStartupTemplateCount = ContractChecks.nonNegative(
                builder.manifestReceiverStartupTemplateCount, "manifestReceiverStartupTemplateCount");
        orderedReceiverPendingCount = ContractChecks.nonNegative(
                builder.orderedReceiverPendingCount, "orderedReceiverPendingCount");
        long calculatedReceiverResources = (long) dynamicReceiverCount
                + dynamicReceiverActionSubscriptionCount + manifestReceiverPackageCount
                + manifestReceiverCount + manifestReceiverBindingCount
                + manifestReceiverActionIndexKeyCount + manifestReceiverActionIndexEntryCount
                + manifestReceiverStartupTemplateCount + orderedReceiverPendingCount;
        if (calculatedReceiverResources > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("receiverResourceCount exceeds integer range");
        }
        receiverResourceCount = builder.receiverResourceCountSet
                ? ContractChecks.nonNegative(builder.receiverResourceCount, "receiverResourceCount")
                : (int) calculatedReceiverResources;
        if (receiverResourceCount != calculatedReceiverResources) {
            throw new IllegalArgumentException("receiverResourceCount does not match component counts");
        }
        if (slotUsed > slotCapacity) throw new IllegalArgumentException("slotUsed exceeds slotCapacity");
        long calculatedProviderResources = (long) uriGrantCount + providerCursorAccessCount
                + providerFileLeaseCount + providerObserverCount + providerAuthorityCount;
        if (providerResourceCount != calculatedProviderResources) {
            throw new IllegalArgumentException("providerResourceCount does not match component counts");
        }
    }

    public static Builder builder() { return new Builder(); }

    public int slotCapacity() { return slotCapacity; }
    public int slotUsed() { return slotUsed; }
    public int sessionCount() { return sessionCount; }
    public int pendingRoutes() { return pendingRoutes; }
    public int taskCount() { return taskCount; }
    public int activityCount() { return activityCount; }
    public int serviceRecordCount() { return serviceRecordCount; }
    public int uriGrantCount() { return uriGrantCount; }
    public int providerCursorAccessCount() { return providerCursorAccessCount; }
    public int providerFileLeaseCount() { return providerFileLeaseCount; }
    public int providerObserverCount() { return providerObserverCount; }
    public int providerAuthorityCount() { return providerAuthorityCount; }
    public int providerResourceCount() { return providerResourceCount; }
    public int providerAuditRetainedCount() { return providerAuditRetainedCount; }
    public long providerAuditSuccessCount() { return providerAuditSuccessCount; }
    public long providerAuditFailureCount() { return providerAuditFailureCount; }
    public int dynamicReceiverCount() { return dynamicReceiverCount; }
    public int dynamicReceiverActionSubscriptionCount() { return dynamicReceiverActionSubscriptionCount; }
    public int manifestReceiverPackageCount() { return manifestReceiverPackageCount; }
    public int manifestReceiverCount() { return manifestReceiverCount; }
    public int manifestReceiverBindingCount() { return manifestReceiverBindingCount; }
    public int manifestReceiverActionIndexKeyCount() { return manifestReceiverActionIndexKeyCount; }
    public int manifestReceiverActionIndexEntryCount() { return manifestReceiverActionIndexEntryCount; }
    public int manifestReceiverStartupTemplateCount() { return manifestReceiverStartupTemplateCount; }
    public int orderedReceiverPendingCount() { return orderedReceiverPendingCount; }
    public int receiverResourceCount() { return receiverResourceCount; }

    @Override public int describeContents() { return 0; }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(slotCapacity);
        dest.writeInt(slotUsed);
        dest.writeInt(sessionCount);
        dest.writeInt(pendingRoutes);
        dest.writeInt(taskCount);
        dest.writeInt(activityCount);
        dest.writeInt(serviceRecordCount);
        dest.writeInt(uriGrantCount);
        dest.writeInt(providerCursorAccessCount);
        dest.writeInt(providerFileLeaseCount);
        dest.writeInt(providerObserverCount);
        dest.writeInt(providerAuthorityCount);
        dest.writeInt(providerResourceCount);
        dest.writeInt(providerAuditRetainedCount);
        dest.writeLong(providerAuditSuccessCount);
        dest.writeLong(providerAuditFailureCount);
        dest.writeInt(dynamicReceiverCount);
        dest.writeInt(manifestReceiverPackageCount);
        dest.writeInt(manifestReceiverCount);
        dest.writeInt(manifestReceiverBindingCount);
        dest.writeInt(dynamicReceiverActionSubscriptionCount);
        dest.writeInt(manifestReceiverActionIndexKeyCount);
        dest.writeInt(manifestReceiverActionIndexEntryCount);
        dest.writeInt(manifestReceiverStartupTemplateCount);
        dest.writeInt(orderedReceiverPendingCount);
        dest.writeInt(receiverResourceCount);
    }

    public static final class Builder {
        private int slotCapacity;
        private int slotUsed;
        private int sessionCount;
        private int pendingRoutes;
        private int taskCount;
        private int activityCount;
        private int serviceRecordCount;
        private int uriGrantCount;
        private int providerCursorAccessCount;
        private int providerFileLeaseCount;
        private int providerObserverCount;
        private int providerAuthorityCount;
        private int providerResourceCount;
        private int providerAuditRetainedCount;
        private long providerAuditSuccessCount;
        private long providerAuditFailureCount;
        private int dynamicReceiverCount;
        private int dynamicReceiverActionSubscriptionCount;
        private int manifestReceiverPackageCount;
        private int manifestReceiverCount;
        private int manifestReceiverBindingCount;
        private int manifestReceiverActionIndexKeyCount;
        private int manifestReceiverActionIndexEntryCount;
        private int manifestReceiverStartupTemplateCount;
        private int orderedReceiverPendingCount;
        private int receiverResourceCount;
        private boolean receiverResourceCountSet;

        public Builder slots(int capacity, int used) {
            slotCapacity = capacity;
            slotUsed = used;
            return this;
        }

        public Builder sessions(int count) {
            sessionCount = count;
            return this;
        }

        public Builder activity(int pending, int tasks, int activities) {
            pendingRoutes = pending;
            taskCount = tasks;
            activityCount = activities;
            return this;
        }

        public Builder services(int records) {
            serviceRecordCount = records;
            return this;
        }

        public Builder providerResources(int grants, int cursors, int files, int observers,
                                         int authorities, int total) {
            uriGrantCount = grants;
            providerCursorAccessCount = cursors;
            providerFileLeaseCount = files;
            providerObserverCount = observers;
            providerAuthorityCount = authorities;
            providerResourceCount = total;
            return this;
        }

        public Builder providerAudit(int retained, long success, long failure) {
            providerAuditRetainedCount = retained;
            providerAuditSuccessCount = success;
            providerAuditFailureCount = failure;
            return this;
        }

        public Builder dynamicReceivers(int count) {
            dynamicReceiverCount = count;
            return this;
        }

        public Builder manifestReceivers(int packages, int receivers, int bindings) {
            manifestReceiverPackageCount = packages;
            manifestReceiverCount = receivers;
            manifestReceiverBindingCount = bindings;
            return this;
        }

        public Builder receiverIndexes(int dynamicActionSubscriptions, int actionIndexKeys,
                                       int actionIndexEntries, int startupTemplates,
                                       int orderedPending, int total) {
            dynamicReceiverActionSubscriptionCount = dynamicActionSubscriptions;
            manifestReceiverActionIndexKeyCount = actionIndexKeys;
            manifestReceiverActionIndexEntryCount = actionIndexEntries;
            manifestReceiverStartupTemplateCount = startupTemplates;
            orderedReceiverPendingCount = orderedPending;
            receiverResourceCount = total;
            receiverResourceCountSet = true;
            return this;
        }

        public Builder receiverResources(int dynamicRegistrations, int dynamicActionSubscriptions,
                                         int packages, int receivers, int bindings,
                                         int actionIndexKeys, int actionIndexEntries,
                                         int startupTemplates, int orderedPending, int total) {
            dynamicReceiverCount = dynamicRegistrations;
            manifestReceiverPackageCount = packages;
            manifestReceiverCount = receivers;
            manifestReceiverBindingCount = bindings;
            return receiverIndexes(dynamicActionSubscriptions, actionIndexKeys, actionIndexEntries,
                    startupTemplates, orderedPending, total);
        }

        public RuntimeStatusSnapshot build() { return new RuntimeStatusSnapshot(this); }
    }
}
