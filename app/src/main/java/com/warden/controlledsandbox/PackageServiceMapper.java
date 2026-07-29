package com.warden.controlledsandbox;

import com.warden.controlledsandbox.contract.PackageArtifactSnapshot;
import com.warden.controlledsandbox.contract.PackageCatalogSnapshot;
import com.warden.controlledsandbox.contract.PackageInstanceSnapshot;
import com.warden.controlledsandbox.contract.PackageRecordSnapshot;
import java.util.ArrayList;
import java.util.List;

final class PackageServiceMapper {
    private PackageServiceMapper() { }

    static PackageRecordSnapshot toSnapshot(SandboxRecord record) {
        if (record == null) return null;
        List<PackageArtifactSnapshot> artifacts = new ArrayList<>();
        for (PackageArtifactRecord artifact : record.artifacts) {
            artifacts.add(new PackageArtifactSnapshot(artifact.splitName, artifact.type,
                    artifact.configForSplit, artifact.usesSplit, artifact.path, artifact.sha256));
        }
        return new PackageRecordSnapshot(record.packageName, record.label, record.versionName,
                record.versionCode, record.signatureSha256, record.apkPath,
                record.nativeLibraryDir, record.nativeAbi, record.launchActivity, record.launchProcess,
                record.applicationClass, record.serviceClass, record.serviceProcess,
                record.receiverClass, record.receiverProcess, record.receiverAction,
                record.providerClass, record.providerProcess, record.providerAuthority,
                record.permissions, record.sharedLibraries, record.sha256,
                record.baseApkSha256, artifacts, record.importedAt,
                record.lastProbeStatus, record.lastProbeAt);
    }

    static SandboxRecord fromSnapshot(PackageRecordSnapshot record) {
        if (record == null) return null;
        List<PackageArtifactRecord> artifacts = new ArrayList<>();
        for (PackageArtifactSnapshot artifact : record.artifacts()) {
            artifacts.add(new PackageArtifactRecord(artifact.splitName(), artifact.type(),
                    artifact.configForSplit(), artifact.usesSplit(), artifact.path(), artifact.sha256()));
        }
        return new SandboxRecord(record.packageName(), record.label(), record.versionName(),
                record.versionCode(), record.signatureSha256(), record.apkPath(),
                record.nativeLibraryDir(), record.nativeAbi(), record.launchActivity(), record.launchProcess(),
                record.applicationClass(), record.serviceClass(), record.serviceProcess(),
                record.receiverClass(), record.receiverProcess(), record.receiverAction(),
                record.providerClass(), record.providerProcess(), record.providerAuthority(),
                record.permissions(), record.sharedLibraries(), record.apkSha256(),
                record.baseApkSha256(), artifacts, record.importedAt(),
                record.lastProbeStatus(), record.lastProbeAt());
    }

    static PackageInstanceSnapshot toSnapshot(SandboxInstance instance) {
        return new PackageInstanceSnapshot(instance.packageName, instance.virtualUserId,
                instance.displayName, instance.createdAt, instance.lastRuntimeStatus,
                instance.lastRuntimeAt);
    }

    static SandboxInstance fromSnapshot(PackageInstanceSnapshot instance) {
        return new SandboxInstance(instance.packageName(), instance.virtualUserId(),
                instance.displayName(), instance.createdAt(), instance.lastRuntimeStatus(),
                instance.lastRuntimeAt());
    }

    static PackageCatalogSnapshot toSnapshot(SandboxCatalogState state, String warning) {
        List<PackageRecordSnapshot> records = new ArrayList<>();
        for (SandboxRecord record : state.records()) records.add(toSnapshot(record));
        List<PackageInstanceSnapshot> instances = new ArrayList<>();
        for (SandboxInstance instance : state.instances()) instances.add(toSnapshot(instance));
        return new PackageCatalogSnapshot(records, instances, warning);
    }

    static SandboxCatalogState fromSnapshot(PackageCatalogSnapshot state) {
        List<SandboxRecord> records = new ArrayList<>();
        for (PackageRecordSnapshot record : state.packages()) records.add(fromSnapshot(record));
        List<SandboxInstance> instances = new ArrayList<>();
        for (PackageInstanceSnapshot instance : state.instances()) instances.add(fromSnapshot(instance));
        return new SandboxCatalogState(records, instances);
    }
}
