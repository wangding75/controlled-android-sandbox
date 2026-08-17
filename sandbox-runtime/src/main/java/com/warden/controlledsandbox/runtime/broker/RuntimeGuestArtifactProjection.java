package com.warden.controlledsandbox.runtime.broker;

import android.os.Bundle;

import com.warden.controlledsandbox.contract.PackageArtifactSnapshot;
import com.warden.controlledsandbox.contract.PackageRecordSnapshot;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import java.util.ArrayList;

/** Projects immutable split/native artifact metadata into a cross-package Guest request. */
final class RuntimeGuestArtifactProjection {
    private RuntimeGuestArtifactProjection() { }

    static void put(Bundle target, PackageRecordSnapshot record) {
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> types = new ArrayList<>();
        ArrayList<String> configFor = new ArrayList<>();
        ArrayList<String> uses = new ArrayList<>();
        ArrayList<String> paths = new ArrayList<>();
        ArrayList<String> digests = new ArrayList<>();
        for (PackageArtifactSnapshot artifact : record.artifacts()) {
            if (artifact == null || artifact.base()) continue;
            names.add(artifact.splitName());
            types.add(artifact.type());
            configFor.add(artifact.configForSplit());
            uses.add(artifact.usesSplit());
            paths.add(artifact.path());
            digests.add(artifact.sha256());
        }
        target.putStringArrayList(RuntimeKeys.SPLIT_NAMES, names);
        target.putStringArrayList(RuntimeKeys.SPLIT_TYPES, types);
        target.putStringArrayList(RuntimeKeys.SPLIT_CONFIG_FOR, configFor);
        target.putStringArrayList(RuntimeKeys.SPLIT_USES, uses);
        target.putStringArrayList(RuntimeKeys.SPLIT_PATHS, paths);
        target.putStringArrayList(RuntimeKeys.SPLIT_SHA256S, digests);
    }

    static ArrayList<String> csv(String value) {
        ArrayList<String> result = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) return result;
        for (String item : value.split(",")) {
            if (item != null && !item.trim().isEmpty()) result.add(item.trim());
        }
        return result;
    }
}
