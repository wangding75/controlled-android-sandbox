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

    save_pos = text['lifecycle'].find('catalogRepository.save(next);',
                                      text['lifecycle'].find('deleteInstance('))
    delete_pos = text['lifecycle'].find('deleteForMaintenance(instanceDirectory', save_pos)
    if save_pos < 0 or delete_pos < 0 or save_pos > delete_pos:
        errors.append('instance/package files must be deleted only after catalog commit')

    required_importer = [
        'storageLayout.revisionsDirectory(packageName)',
        'storageLayout.revisionDirectory(packageName, sha)',
        'storageLayout.requireNoManagedSymlinks(revisionDir)',
        'IMMUTABLE_REVISION_DIRECTORY_MISMATCH',
        'IMMUTABLE_REVISION_NATIVE_MISMATCH',
        'publishDirectory(transactionDir, revisionDir);',
        'Files.isSymbolicLink(path)',
        'StandardCopyOption.ATOMIC_MOVE',
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
    for required in ['packageService.importApk(uri)', 'packageService.createClone(',
                     'packageService.deleteInstance(', 'packageService.updateInstanceStatus(']:
        if required not in text['activity']:
            errors.append(f'MainActivity lifecycle wiring missing: {required}')

    for forbidden in ['new SandboxRepository(', 'new ApkImportManager(', 'repository.save(']:
        if forbidden in text['debug_activity']:
            errors.append(f'DebugCommandActivity bypasses lifecycle authority: {forbidden}')
    for required in ['packages.importApkFile(', 'packages.ensureInstance(',
                     'packages.findRecord(']:
        if required not in text['debug_activity']:
            errors.append(f'DebugCommandActivity lifecycle wiring missing: {required}')

    backup_write = text['store'].find('writePath(backup, content);')
    primary_write = text['store'].find('writePath(primary, content);', backup_write)
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
