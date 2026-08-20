#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

files = {
    'catalog_state': ROOT / 'app/src/main/java/com/warden/controlledsandbox/SandboxCatalogState.java',
    'catalog_repo': ROOT / 'app/src/main/java/com/warden/controlledsandbox/SandboxCatalogRepository.java',
    'lifecycle': ROOT / 'app/src/main/java/com/warden/controlledsandbox/SandboxPackageLifecycle.java',
    'importer': ROOT / 'app/src/main/java/com/warden/controlledsandbox/ApkImportManager.java',
    'layout': ROOT / 'app/src/main/java/com/warden/controlledsandbox/PackageStorageLayout.java',
    'migrator': ROOT / 'app/src/main/java/com/warden/controlledsandbox/LegacyPackageLayoutMigrator.java',
    'package_session': ROOT / 'app/src/main/java/com/warden/controlledsandbox/PackageManagementSession.java',
    'dependencies': ROOT / 'app/src/main/java/com/warden/controlledsandbox/PackageServiceDependencies.java',
    'application_layer': ROOT / 'app/src/main/java/com/warden/controlledsandbox/SandboxApplicationLayer.java',
    'adapter': ROOT / 'app/src/main/java/com/warden/controlledsandbox/SxSandboxAdapter.java',
    'isolated': ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeIsolatedProcessCoordinator.java',
    'activity': ROOT / 'app/src/main/java/com/warden/controlledsandbox/MainActivity.java',
    'debug_activity': ROOT / 'app/src/debug/java/com/warden/controlledsandbox/DebugCommandActivity.java',
    'store': ROOT / 'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/persistence/RecoverableFileStore.java',
    'test': ROOT / 'app/src/testHarness/java/com/warden/controlledsandbox/SandboxCatalogStateSelfTest.java',
    'compiler': ROOT / 'tools/static_android_compile.py',
}
for name, path in files.items():
    if not path.is_file():
        errors.append(f'missing {name}: {path.relative_to(ROOT)}')

if not errors:
    text = {name: path.read_text(encoding='utf-8') for name, path in files.items()}

    required_catalog = [
        'new File(filesDir, "sandbox-catalog.json")',
        'root.put("packages", packages)',
        'root.put("instances", instances)',
        'SandboxCatalogState.normalizeLegacy(',
        'legacyLayoutMigrator.migrate(legacy)',
        'storageLayout.requireCatalogLayout(state)',
    ]
    for fragment in required_catalog:
        if fragment not in text['catalog_repo']:
            errors.append(f'atomic catalog repository missing: {fragment}')

    required_state = [
        'Collections.unmodifiableList(recordCopy)',
        'Collections.unmodifiableList(instanceCopy)',
        'withImported(SandboxRecord imported',
        'withoutInstance(String packageName, int virtualUserId)',
        'Instance references missing package:',
        'Duplicate sandbox instance:',
    ]
    for fragment in required_state:
        if fragment not in text['catalog_state']:
            errors.append(f'catalog aggregate invariant missing: {fragment}')

    required_lifecycle = [
        'catalogRepository.save(next);',
        'deletePublishedRevisionIfUnreferenced(current, imported)',
        'deleteForMaintenance(instanceDirectory(packageName, virtualUserId), cleanupFailures);',
        'sweepUnreferencedFiles(next, cleanupFailures);',
        'maintenanceWarning = formatMaintenanceWarning(cleanupFailures);',
    ]
    for fragment in required_lifecycle:
        if fragment not in text['lifecycle']:
            errors.append(f'package lifecycle transaction control missing: {fragment}')

    for operation, lifecycle_call in [
            ('deleteInstance(String packageName, int virtualUserId)',
             'lifecycle.deleteInstance(normalizedPackage, virtualUserId)'),
            ('clearInstanceData(String packageName, int virtualUserId)',
             'lifecycle.clearInstanceData(normalizedPackage, virtualUserId)')]:
        start = text['package_session'].find(operation)
        if start < 0:
            errors.append(f'package management operation missing: {operation}')
            continue
        end = text['package_session'].find('\n    @Override', start + len(operation))
        block = text['package_session'][start:end if end >= 0 else None]
        barrier = block.find(
            'dependencies.stopGuestBeforeDestructiveOperation(normalizedPackage, virtualUserId);')
        mutation = block.find(lifecycle_call)
        if barrier < 0 or mutation < 0 or barrier > mutation:
            errors.append(f'{operation} must stop the Guest before lifecycle mutation')

    required_isolated_barrier = [
        'SHUTDOWN_TIMEOUT_MILLIS',
        'final CountDownLatch terminated = new CountDownLatch(1);',
        'connection.awaitTerminated(SHUTDOWN_TIMEOUT_MILLIS',
        'abortConnection(connection, "ISOLATED_SHUTDOWN_PROCESS_TIMEOUT")',
        'source.terminated.countDown();',
        'RuntimeException firstFailure = null;',
        'if (firstFailure != null) throw firstFailure;',
    ]
    for fragment in required_isolated_barrier:
        if fragment not in text['isolated']:
            errors.append(f'isolated process death barrier missing: {fragment}')

    for fragment in ['RevisionCommitBarrier', 'commitImported(current, imported, barrier)',
                     'PackageLifecycleTransaction', 'prepareUpdate', 'switchUpdate',
                     'rollbackPackage', 'resetIdentity', 'withRestoredRevision']:
        if fragment not in text['lifecycle'] and fragment not in text['catalog_state']:
            errors.append(f'transactional package revision control missing: {fragment}')
    if text['package_session'].count('dependencies::stopGuestBeforeRevisionCommit') < 6:
        errors.append('all production APK import/install entry points must use the revision stop barrier')
    for fragment in ['stopGuestBeforeRevisionCommit',
                     'previous.sha256.equals(imported.sha256)',
                     'for (int userId : users) runtimeClient.stop(previous, userId);']:
        if fragment not in text['dependencies']:
            errors.append(f'revision stop barrier implementation missing: {fragment}')

    clear_start = text['application_layer'].find('void clearData(String packageName, int userId)')
    delete_start = text['application_layer'].find('void deleteInstance(String packageName, int userId)')
    if clear_start >= 0 and delete_start >= 0:
        if 'adapter.stopRuntime(' in text['application_layer'][clear_start:delete_start]:
            errors.append('SandboxApplicationLayer.clearData must not duplicate the stop barrier')
        next_method = text['application_layer'].find('\n    Bundle launch(', delete_start)
        if 'adapter.stopRuntime(' in text['application_layer'][delete_start:next_method if next_method >= 0 else None]:
            errors.append('SandboxApplicationLayer.deleteInstance must not duplicate the stop barrier')
    adapter_delete = text['adapter'].find('SandboxOperationResult deleteInstance(SandboxIdentity identity)')
    adapter_status = text['adapter'].find('SandboxOperationResult status()', adapter_delete)
    if adapter_delete >= 0 and 'runtime.stop(' in text['adapter'][adapter_delete:adapter_status if adapter_status >= 0 else None]:
        errors.append('SxSandboxAdapter.deleteInstance must delegate the single package transaction')

    save_pos = text['lifecycle'].find('catalogRepository.save(next);',
                                      text['lifecycle'].find('deleteInstance('))
    delete_pos = text['lifecycle'].find('deleteForMaintenance(instanceDirectory', save_pos)
    if save_pos < 0 or delete_pos < 0 or save_pos > delete_pos:
        errors.append('instance/package files must be deleted only after catalog commit')

    required_importer = [
        'storageLayout.revisionsDirectory(packageName)',
        'storageLayout.revisionDirectory(packageName, revisionSha256)',
        'storageLayout.requireNoManagedSymlinks(revisionDir)',
        'IMMUTABLE_REVISION_DIRECTORY_MISMATCH',
        'IMMUTABLE_REVISION_CONTENT_MISMATCH',
        'publishDirectory(transactionDir, revisionDir);',
        'Files.isSymbolicLink(path)',
        'DurableAtomicFile',
    ]
    for fragment in required_importer:
        if fragment not in text['importer']:
            errors.append(f'immutable package publication missing: {fragment}')
    required_layout = [
        'files/packages',
        'PACKAGE_REVISION_PATH_MISMATCH',
        'PACKAGE_PATH_CONTAINS_SYMBOLIC_LINK',
        'PACKAGE_REVISION_APK_MISSING',
        'requireCatalogLayout(SandboxCatalogState state)',
    ]
    # The root is constructed from filesDir + "packages" rather than a literal path.
    for fragment in required_layout[1:]:
        if fragment not in text['layout']:
            errors.append(f'package storage layout control missing: {fragment}')
    if 'new File(filesDir, "packages")' not in text['layout']:
        errors.append('package storage root is not app-private files/packages')

    required_migration = [
        'LEGACY_PACKAGE_DIGEST_MISMATCH',
        'layout.requireNoManagedSymlinks(configuredSourceApk)',
        'ApkImportManager.publishDirectory(transaction, revision)',
        'record.withStoragePaths(',
    ]
    for fragment in required_migration:
        if fragment not in text['migrator']:
            errors.append(f'legacy package layout migration missing: {fragment}')

    forbidden_importer = ['.backup-', 'renameTo(packageDir)', 'new SandboxRepository(context)']
    for fragment in forbidden_importer:
        if fragment in text['importer']:
            errors.append(f'legacy mutable package replacement remains: {fragment}')

    forbidden_activity = [
        'new SandboxRepository(',
        'new SandboxInstanceRepository(',
        'repository.save(',
        'instanceRepository.save(',
        'catch (Exception ignored)',
        'ApkImportManager.deleteTree(',
    ]
    for fragment in forbidden_activity:
        if fragment in text['activity']:
            errors.append(f'MainActivity bypasses lifecycle authority: {fragment}')
    for required in ['viewModel.application().importApk(', 'viewModel.application().createClone(',
                     'viewModel.application().deleteInstance(',
                     'viewModel.application().updateInstanceStatus(']:
        if required not in text['activity']:
            errors.append(f'MainActivity lifecycle wiring missing: {required}')

    for forbidden in ['new SandboxRepository(', 'new ApkImportManager(', 'repository.save(']:
        if forbidden in text['debug_activity']:
            errors.append(f'DebugCommandActivity bypasses lifecycle authority: {forbidden}')
    if not any(required in text['debug_activity'] for required in (
            'packages.importApkFile(', 'packages.importInstalledApplication(')):
        errors.append('DebugCommandActivity lifecycle wiring missing: package import entry point')
    for required in ['packages.ensureInstance(', 'packages.findRecord(']:
        if required not in text['debug_activity']:
            errors.append(f'DebugCommandActivity lifecycle wiring missing: {required}')

    backup_candidates = [
        text['store'].find('writePath(backup, content);'),
        text['store'].find('writePath(backup, content, encoded);'),
    ]
    backup_write = min((index for index in backup_candidates if index >= 0), default=-1)
    primary_candidates = [
        text['store'].find('writePath(primary, content);', backup_write),
        text['store'].find('writePath(primary, content, encoded);', backup_write),
    ]
    primary_write = min((index for index in primary_candidates if index >= 0), default=-1)
    if backup_write < 0 or primary_write < 0 or backup_write > primary_write:
        errors.append('RecoverableFileStore must publish backup before primary')
    for fragment in ['previousBackupExists', 'Files.deleteIfExists(backup)',
                     'primaryFailure.addSuppressed(rollbackFailure)']:
        if fragment not in text['store']:
            errors.append(f'RecoverableFileStore primary-failure rollback missing: {fragment}')

    test_class = 'com.warden.controlledsandbox.SandboxCatalogStateSelfTest'
    if text['compiler'].count("'" + test_class + "'") != 1:
        errors.append(f'static compiler must execute {test_class} exactly once')
    for coverage in ['last instance removal drops package atomically',
                     'orphan instance rejected', 'prior aggregate remains immutable',
                     'explicit test instance added',
                     'legacy APK moved to immutable revision metadata',
                     'legacy package path outside managed root rejected']:
        if coverage not in text['test']:
            errors.append(f'catalog aggregate self-test missing coverage: {coverage}')

if errors:
    print('FAIL package lifecycle transaction checks', file=sys.stderr)
    for error in errors:
        print(' - ' + error, file=sys.stderr)
    raise SystemExit(1)

print('PASS package lifecycle transaction checks')
