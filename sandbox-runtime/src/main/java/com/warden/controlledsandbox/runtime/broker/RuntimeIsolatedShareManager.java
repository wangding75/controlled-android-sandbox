package com.warden.controlledsandbox.runtime.broker;

import android.app.Service;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import com.warden.controlledsandbox.contract.VirtualPackageProjectionSnapshot;
import com.warden.controlledsandbox.domain.session.GuestSession;
import com.warden.controlledsandbox.runtime.diagnostics.RuntimeEventLog;
import com.warden.controlledsandbox.runtime.protocol.RuntimeKeys;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides an explicit file capability for Android {@code isolatedProcess} workers.
 *
 * <p>An isolated UID cannot traverse the host application's private data directory. Passing the
 * ordinary APK/data paths through Binder therefore makes the isolated route fail before Guest
 * bootstrap. This manager creates a short-lived, random staging tree with only the artifacts
 * required by one generation. The Broker copies mutable Guest data back before the tree is
 * destroyed, and removes the traversal bit from the host data root when the last lease closes.
 * The path is never reused across sessions or generations.</p>
 */
final class RuntimeIsolatedShareManager {
    private final Service host;
    private final Map<String, Share> shares = new ConcurrentHashMap<>();

    RuntimeIsolatedShareManager(Service host) {
        if (host == null) throw new IllegalArgumentException("host is required");
        this.host = host;
    }

    synchronized Bundle prepare(Bundle source, GuestSession session) throws Exception {
        if (source == null || session == null) throw new IllegalArgumentException("share inputs are required");
        source.setClassLoader(VirtualPackageProjectionSnapshot.class.getClassLoader());
        String sessionKey = session.sessionId();
        if (shares.containsKey(sessionKey)) throw new IllegalStateException("ISOLATED_SHARE_ALREADY_PREPARED");
        File hostData = host.getDataDir().getCanonicalFile();
        File originalData = new File(required(source, RuntimeKeys.DATA_ROOT)).getCanonicalFile();
        requireHostPrivate(originalData, host.getFilesDir().getCanonicalFile(), "dataRoot");
        if (source.getBoolean(RuntimeKeys.ISOLATED_PROCESS, false)) {
            // An isolated_app UID cannot be granted access to app-private or FUSE-scoped paths
            // by chmod alone. Pass explicit Binder file capabilities instead; the worker turns
            // them into /proc/self/fd paths after the kernel has transferred the descriptors.
            return prepareFileDescriptorShare(source, session, originalData);
        }
        File sharedRoot = shareRoot().getCanonicalFile();
        boolean hostTraversalRequired = sharedRoot.toPath().startsWith(hostData.toPath());
        File root = new File(sharedRoot, safe(session.packageName()) + "-" + safe(session.sessionId()))
                .getCanonicalFile();
        if (!root.toPath().startsWith(sharedRoot.toPath())) {
            throw new SecurityException("ISOLATED_SHARE_PATH_ESCAPE");
        }
        if (root.exists()) deleteTree(root);
        boolean hostTraverseGranted = false;
        try {
            mkdirs(sharedRoot);
            makeDirectoryAccessible(sharedRoot, false);
            // Only traversal is granted on the host package root. The randomized share itself
            // carries the read/write capability; packages and instances remain non-listable to
            // other UIDs.
            if (hostTraversalRequired) {
                grantTraverse(hostData);
                hostTraverseGranted = true;
            }
            mkdirs(root);
            makeDirectoryAccessible(root, false);

            File artifacts = new File(root, "artifacts");
            File data = new File(root, "data");
            mkdirs(artifacts);
            makeDirectoryAccessible(artifacts, false);
            copyTree(originalData, data, true);
            makeDataTreeAccessible(data);

            String apkPath = required(source, RuntimeKeys.APK_PATH);
            File apk = copyFileFromHost(apkPath, new File(artifacts, "base.apk"), false);
            source.putString(RuntimeKeys.APK_PATH, apk.getCanonicalPath());

            ArrayList<String> splitPaths = source.getStringArrayList(RuntimeKeys.SPLIT_PATHS);
            ArrayList<String> sharedSplits = new ArrayList<>();
            if (splitPaths != null) {
                for (int index = 0; index < splitPaths.size(); index++) {
                    File split = copyFileFromHost(splitPaths.get(index),
                            new File(artifacts, "split-" + index + ".apk"), false);
                    sharedSplits.add(split.getCanonicalPath());
                }
            }
            source.putStringArrayList(RuntimeKeys.SPLIT_PATHS, sharedSplits);

            String nativePath = source.getString(RuntimeKeys.NATIVE_LIBRARY_DIR, "");
            if (!nativePath.trim().isEmpty()) {
                File nativeSource = new File(nativePath).getCanonicalFile();
                requireHostPrivate(nativeSource, host.getFilesDir().getCanonicalFile(), "native");
                File nativeDir = new File(root, "native");
                copyTree(nativeSource, nativeDir, false);
                makeNativeTreeAccessible(nativeDir);
                source.putString(RuntimeKeys.NATIVE_LIBRARY_DIR, nativeDir.getCanonicalPath());
            }

            ArrayList<VirtualPackageProjectionSnapshot> projections =
                    source.getParcelableArrayList(RuntimeKeys.PACKAGE_UNIVERSE);
            if (projections != null && !projections.isEmpty()) {
                ArrayList<VirtualPackageProjectionSnapshot> sharedProjections = new ArrayList<>();
                File projectionRoot = new File(root, "projections");
                mkdirs(projectionRoot);
                makeDirectoryAccessible(projectionRoot, false);
                int index = 0;
                for (VirtualPackageProjectionSnapshot projection : projections) {
                    if (projection == null) continue;
                    File projectionDir = new File(projectionRoot, index++ + "-"
                            + safe(projection.packageState().packageName()));
                    mkdirs(projectionDir);
                    makeDirectoryAccessible(projectionDir, false);
                    File projectionApk = copyFileFromHost(projection.apkPath(),
                            new File(projectionDir, "base.apk"), false);
                    String projectionNative = projection.nativeLibraryDir();
                    String sharedNative = "";
                    if (projectionNative != null && !projectionNative.trim().isEmpty()) {
                        File nativeSource = new File(projectionNative).getCanonicalFile();
                        requireHostPrivate(nativeSource, host.getFilesDir().getCanonicalFile(),
                                "projectionNative");
                        File nativeDir = new File(projectionDir, "native");
                        copyTree(nativeSource, nativeDir, false);
                        makeNativeTreeAccessible(nativeDir);
                        sharedNative = nativeDir.getCanonicalPath();
                    }
                    sharedProjections.add(new VirtualPackageProjectionSnapshot(
                            projection.packageState(), projectionApk.getCanonicalPath(), sharedNative,
                            projection.virtualUid(), projection.parsedApplicationInfo()));
                }
                source.putParcelableArrayList(RuntimeKeys.PACKAGE_UNIVERSE, sharedProjections);
            }

            source.putString(RuntimeKeys.DATA_ROOT, data.getCanonicalPath());
            source.putBoolean(RuntimeKeys.ISOLATED_PROCESS, true);
            shares.put(sessionKey, new Share(sessionKey, originalData, root, hostData,
                    hostTraversalRequired));
            return source;
        } catch (Throwable error) {
            try {
                if (root.exists()) deleteTree(root);
            } catch (Throwable cleanupFailure) {
                error.addSuppressed(cleanupFailure);
            }
            if (hostTraverseGranted && shares.isEmpty()) revokeTraverse(hostData);
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("ISOLATED_SHARE_PREPARE_FAILED", error);
        }
    }

    synchronized void finish(GuestSession session) {
        if (session == null) return;
        Share share = shares.remove(session.sessionId());
        if (share == null) return;
        try {
            if (share.descriptors != null) {
                closeDescriptors(share.descriptors, null);
            } else {
                File sharedData = new File(share.root, "data");
                if (sharedData.exists()) {
                    deleteChildren(share.originalData);
                    copyTree(sharedData, share.originalData, true);
                }
            }
        } catch (Throwable error) {
            recordFailure(share.descriptors != null
                    ? "ISOLATED_FD_CAPABILITY_CLOSE_FAILED"
                    : "ISOLATED_SHARE_COPYBACK_FAILED", error);
        } finally {
            if (share.root != null) {
                try { deleteTree(share.root); }
                catch (Throwable error) {
                    recordFailure("ISOLATED_SHARE_DELETE_FAILED", error);
                }
            }
            if (shares.isEmpty() && share.hostTraversalRequired) revokeTraverse(share.hostData);
        }
    }

    synchronized void close() {
        boolean hostTraversalRequired = false;
        for (Share share : shares.values().toArray(new Share[0])) {
            hostTraversalRequired |= share.hostTraversalRequired;
            if (share.descriptors != null) {
                closeDescriptors(share.descriptors, null);
            } else if (share.root != null) {
                try {
                    deleteTree(share.root);
                } catch (Throwable error) {
                    recordFailure("ISOLATED_SHARE_CLOSE_FAILED", error);
                }
            }
        }
        shares.clear();
        if (hostTraversalRequired) revokeTraverse(host.getDataDir());
    }

    private File copyFileFromHost(String sourcePath, File target, boolean executable) throws Exception {
        if (sourcePath == null || sourcePath.trim().isEmpty()) {
            throw new IllegalArgumentException("artifact is required");
        }
        File source = new File(sourcePath).getCanonicalFile();
        requireHostPrivate(source, host.getFilesDir().getCanonicalFile(), "artifact");
        if (!source.isFile()) throw new IOException("isolated artifact is missing: " + source);
        copyFile(source, target);
        makeFileAccessible(target, executable);
        return target;
    }

    private Bundle prepareFileDescriptorShare(Bundle source, GuestSession session,
                                              File originalData) throws Exception {
        ArrayList<ParcelFileDescriptor> descriptors = new ArrayList<>();
        try {
            if (!originalData.isDirectory()) mkdirs(originalData);
            // Directory descriptors are opened read-only on Android; MODE_READ_WRITE maps to
            // O_RDWR and the kernel rejects directories with EISDIR.  Mutation semantics are
            // validated separately through the native openat/Broker capability path.
            ParcelFileDescriptor data = openDirectory(originalData, "dataRoot",
                    ParcelFileDescriptor.MODE_READ_ONLY);
            descriptors.add(data);
            source.putParcelable(RuntimeKeys.ISOLATED_DATA_ROOT_FD, data);

            String apkPath = required(source, RuntimeKeys.APK_PATH);
            File apkFile = artifactFile(apkPath, false, "apk");
            ParcelFileDescriptor apk = openArtifact(apkPath, false, "apk");
            descriptors.add(apk);
            source.putParcelable(RuntimeKeys.ISOLATED_APK_FD, apk);
            source.putString(RuntimeKeys.ISOLATED_APK_ENTRY_NAME, apkFile.getName());

            ArrayList<ParcelFileDescriptor> splitDescriptors = new ArrayList<>();
            ArrayList<String> splitEntryNames = new ArrayList<>();
            ArrayList<String> splitPaths = source.getStringArrayList(RuntimeKeys.SPLIT_PATHS);
            if (splitPaths != null) {
                for (String splitPath : splitPaths) {
                    File splitFile = artifactFile(splitPath, false, "split");
                    ParcelFileDescriptor split = openArtifact(splitPath, false, "split");
                    descriptors.add(split);
                    splitDescriptors.add(split);
                    splitEntryNames.add(splitFile.getName());
                }
            }
            source.putParcelableArrayList(RuntimeKeys.ISOLATED_SPLIT_FDS, splitDescriptors);
            source.putStringArrayList(RuntimeKeys.ISOLATED_SPLIT_ENTRY_NAMES, splitEntryNames);

            String nativePath = source.getString(RuntimeKeys.NATIVE_LIBRARY_DIR, "");
            if (!nativePath.trim().isEmpty()) {
                File nativeSource = artifactFile(nativePath, true, "native");
                ParcelFileDescriptor nativeDirectory = openArtifact(nativePath, true, "native");
                descriptors.add(nativeDirectory);
                source.putParcelable(RuntimeKeys.ISOLATED_NATIVE_LIBRARY_FD, nativeDirectory);
                ArrayList<ParcelFileDescriptor> nativeFiles = new ArrayList<>();
                ArrayList<String> nativeEntryNames = new ArrayList<>();
                File[] children = nativeSource.listFiles();
                if (children == null) {
                    throw new IOException("isolated native library directory cannot be listed: "
                            + nativeSource);
                }
                Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
                if (children.length > 256) {
                    throw new IOException("isolated native library directory is too large");
                }
                for (File child : children) {
                    if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                        throw new SecurityException("ISOLATED_NATIVE_LIBRARY_SYMLINK:" + child);
                    }
                    if (!child.isFile() || !child.getName().endsWith(".so")) continue;
                    ParcelFileDescriptor library = openArtifact(child.getCanonicalPath(), false,
                            "native library");
                    descriptors.add(library);
                    nativeFiles.add(library);
                    nativeEntryNames.add(child.getName());
                }
                source.putParcelableArrayList(RuntimeKeys.ISOLATED_NATIVE_LIBRARY_FDS, nativeFiles);
                source.putStringArrayList(RuntimeKeys.ISOLATED_NATIVE_LIBRARY_ENTRY_NAMES,
                        nativeEntryNames);
            }
            source.putBoolean(RuntimeKeys.ISOLATED_PROCESS, true);
            shares.put(session.sessionId(), new Share(session.sessionId(), originalData, null,
                    host.getDataDir().getCanonicalFile(), false, descriptors));
            return source;
        } catch (Throwable error) {
            closeDescriptors(descriptors, error);
            com.warden.controlledsandbox.runtime.protocol.FatalErrorPolicy.rethrowIfFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("ISOLATED_FD_CAPABILITY_PREPARE_FAILED", error);
        }
    }

    private File artifactFile(String path, boolean directory, String kind) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("isolated " + kind + " path is required");
        }
        File file = new File(path).getCanonicalFile();
        requireHostPrivate(file, host.getFilesDir().getCanonicalFile(), kind);
        if (directory ? !file.isDirectory() : !file.isFile()) {
            throw new IOException("isolated " + kind + " is missing: " + file);
        }
        return file;
    }

    private ParcelFileDescriptor openArtifact(String path, boolean directory, String kind)
            throws Exception {
        return ParcelFileDescriptor.open(artifactFile(path, directory, kind),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static ParcelFileDescriptor openDirectory(File directory, String kind, int mode)
            throws Exception {
        if (!directory.isDirectory()) throw new IOException("isolated " + kind + " is missing: " + directory);
        return ParcelFileDescriptor.open(directory, mode);
    }

    private static void closeDescriptors(ArrayList<ParcelFileDescriptor> descriptors,
                                         Throwable primary) {
        for (ParcelFileDescriptor descriptor : descriptors) {
            try {
                if (descriptor != null) descriptor.close();
            } catch (Throwable cleanupFailure) {
                if (primary != null) primary.addSuppressed(cleanupFailure);
            }
        }
    }

    private File shareRoot() {
        // Android instantiates Service fields before attachBaseContext(). Resolve context-backed
        // paths only after the Service lifecycle has attached the base Context.
        File external = host.getExternalFilesDir(null);
        if (external != null) return new File(external, "isolated-share");
        return new File(host.getFilesDir(), "isolated-share");
    }

    private void copyTree(File source, File target, boolean mutable) throws Exception {
        File canonical = source.getCanonicalFile();
        if (!canonical.exists()) {
            mkdirs(target);
            return;
        }
        if (java.nio.file.Files.isSymbolicLink(canonical.toPath())) {
            throw new SecurityException("ISOLATED_SHARE_SYMLINK:" + canonical);
        }
        if (canonical.isDirectory()) {
            mkdirs(target);
            File[] children = canonical.listFiles();
            if (children == null) throw new IOException("cannot list isolated source: " + canonical);
            for (File child : children) {
                copyTree(child, new File(target, child.getName()), mutable);
            }
            return;
        }
        if (!canonical.isFile()) throw new IOException("isolated source is not a file: " + canonical);
        copyFile(canonical, target);
        makeFileAccessible(target, mutable);
    }

    private static void copyFile(File source, File target) throws Exception {
        File parent = target.getParentFile();
        mkdirs(parent);
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private static void makeDataTreeAccessible(File root) {
        if (!root.exists()) return;
        makeDirectoryAccessible(root, true);
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) makeDataTreeAccessible(child);
            else makeFileAccessible(child, false);
        }
    }

    private static void makeNativeTreeAccessible(File root) {
        if (!root.exists()) return;
        makeDirectoryAccessible(root, false);
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) makeNativeTreeAccessible(child);
            else makeFileAccessible(child, true);
        }
    }

    private static void grantTraverse(File directory) {
        if (!directory.setExecutable(true, false)) {
            throw new IllegalStateException("ISOLATED_SHARE_HOST_TRAVERSE_FAILED:" + directory);
        }
    }

    private static void revokeTraverse(File directory) {
        // File.setExecutable(false, false) clears the owner bit as well on Android. That leaves
        // the package root at 0600 and makes the next run-as/PackageManagement cold start fail.
        // App data roots are owner-only; restore that invariant after the isolated lease closes.
        directory.setExecutable(true, true);
    }

    private static void makeDirectoryAccessible(File directory, boolean mutable) {
        if (!directory.setReadable(true, false) || !directory.setExecutable(true, false)
                || (mutable && !directory.setWritable(true, false))) {
            throw new IllegalStateException("ISOLATED_SHARE_DIRECTORY_MODE_FAILED:" + directory);
        }
    }

    private static void makeFileAccessible(File file, boolean executable) {
        if (!file.setReadable(true, false) || !file.setWritable(true, false)
                || (executable && !file.setExecutable(true, false))) {
            throw new IllegalStateException("ISOLATED_SHARE_FILE_MODE_FAILED:" + file);
        }
    }

    private static void deleteChildren(File directory) throws Exception {
        if (!directory.exists()) { mkdirs(directory); return; }
        if (java.nio.file.Files.isSymbolicLink(directory.toPath())) {
            throw new SecurityException("ISOLATED_COPYBACK_ROOT_SYMLINK:" + directory);
        }
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("cannot list copyback directory: " + directory);
        for (File child : children) deleteTree(child);
    }

    private static void deleteTree(File file) throws Exception {
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            throw new SecurityException("ISOLATED_SHARE_DELETE_SYMLINK:" + file);
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("cannot list isolated cleanup directory: " + file);
            for (File child : children) deleteTree(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("cannot delete isolated share: " + file);
    }

    private static void mkdirs(File directory) {
        if (directory == null || directory.isDirectory()) return;
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("cannot create isolated share directory: " + directory);
        }
    }

    private static void requireHostPrivate(File file, File privateRoot, String name) throws Exception {
        if (!file.toPath().startsWith(privateRoot.toPath())) {
            throw new SecurityException("ISOLATED_SHARE_" + name.toUpperCase() + "_OUTSIDE_HOST_PRIVATE");
        }
    }

    private static String required(Bundle bundle, String key) {
        String value = bundle.getString(key, "");
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static void recordFailure(String event, Throwable error) {
        Bundle fields = new Bundle();
        fields.putString(RuntimeKeys.ERROR_TYPE,
                error == null ? "unknown" : error.getClass().getName());
        fields.putString(RuntimeKeys.ERROR_MESSAGE,
                error == null ? "" : String.valueOf(error.getMessage()));
        RuntimeEventLog.event(event, fields);
    }

    private static final class Share {
        final String sessionId;
        final File originalData;
        final File root;
        final File hostData;
        final boolean hostTraversalRequired;
        final ArrayList<ParcelFileDescriptor> descriptors;

        Share(String sessionId, File originalData, File root, File hostData,
                boolean hostTraversalRequired) {
            this(sessionId, originalData, root, hostData, hostTraversalRequired, null);
        }

        Share(String sessionId, File originalData, File root, File hostData,
                boolean hostTraversalRequired, ArrayList<ParcelFileDescriptor> descriptors) {
            this.sessionId = sessionId;
            this.originalData = originalData;
            this.root = root;
            this.hostData = hostData;
            this.hostTraversalRequired = hostTraversalRequired;
            this.descriptors = descriptors;
        }
    }
}
