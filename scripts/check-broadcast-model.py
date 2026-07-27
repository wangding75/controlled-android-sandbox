#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

required = [
    'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/component/receiver/BroadcastIntent.java',
    'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/component/receiver/OrderedBroadcastState.java',
    'sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IOrderedReceiverCompletion.aidl',
    'sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkCallInterceptor.java',
    'sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/FrameworkCallInterceptorSelfTest.java',
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/receiver/BroadcastPayloadEstimator.java',
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/receiver/ManifestBroadcastDispatcher.java',
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/receiver/OrderedReceiverTokenRegistry.java',
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/receiver/BrokerOrderedReceiverRuntime.java',
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/receiver/ReceiverLifecycleCoordinator.java',
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/OrderedReceiverPendingResultBridge.java',
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/OrderedReceiverFinishInterceptor.java',
    'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/OrderedReceiverFinishToken.java',
    'sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/receiver/ManifestBroadcastDispatcherSelfTest.java',
    'sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/receiver/OrderedReceiverTokenRegistrySelfTest.java',
    'sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/receiver/ReceiverLifecycleCoordinatorSelfTest.java',
    'sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/OrderedReceiverPendingResultBridgeSelfTest.java',
    'docs/fixes/b3-t4d-receiver-consistency-review.md',
]
for rel in required:
    if not (ROOT / rel).is_file():
        errors.append(f'missing broadcast model file: {rel}')

registry = (ROOT / 'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/component/receiver/ManifestReceiverRegistry.java').read_text()
for token in ['actionIndex', 'resolveImplicit(', 'MAX_IMPLICIT_MATCHES', 'priority()']:
    if token not in registry:
        errors.append(f'ManifestReceiverRegistry missing expected token: {token}')
if 'for (PackageRecord targetRecord : packages.values())' in registry:
    errors.append('implicit Receiver resolution regressed to full package scan')

parser = (ROOT / 'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/packageinfo/manifest/BinaryXmlManifestParser.java').read_text()
for token in ['case "data"', 'pathPrefix', 'pathPattern', 'mimeType', 'intAttr("priority"']:
    if token not in parser:
        errors.append(f'BinaryXmlManifestParser missing implicit filter support: {token}')

operations = (ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/protocol/ComponentOperations.java').read_text()
for token in ['SEND_IMPLICIT_BROADCAST', 'SEND_ORDERED_BROADCAST']:
    if token not in operations:
        errors.append(f'ComponentOperations missing {token}')

broker = (ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java').read_text()
for token in [
    'ManifestBroadcastDispatcher', 'dispatchImplicitManifestBroadcast(', 'BroadcastPayloadEstimator',
    'BrokerOrderedReceiverRuntime', 'orderedReceiverCompletion', 'orderedReceiverRuntime.issue(',
    'orderedReceiverRuntime.await(', 'ReceiverLifecycleCoordinator',
    'receiverLifecycle.disconnectSession(', 'receiverLifecycle.recoverSession(',
    'receiverLifecycle.stopSession(', 'receiverLifecycle.invalidateInstance(',
    'receiverLifecycle.invalidateAll(', 'receiverLifecycle.purgeExpired(',
    'ORDERED_RECEIVER_COMPLETION_BINDER',
]:
    if token not in broker:
        errors.append(f'RuntimeBrokerService missing broadcast routing token: {token}')
if 'for (BrokerManifestReceiverRuntime.Route route : routes)' in broker:
    errors.append('ordered broadcast policy must remain inside ManifestBroadcastDispatcher')
if 'new OrderedReceiverPendingResultBridge' in broker or 'setPendingResult(' in broker:
    errors.append('Broker must not construct Guest PendingResult objects')


dynamic_registry = (ROOT / 'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/component/receiver/DynamicReceiverRegistry.java').read_text()
for token in [
    'MAX_REGISTRATIONS = 4096', 'MAX_ACTIONS_PER_REGISTRATION = 128',
    'removeInstance(', 'Snapshot(', 'actionSubscriptions',
]:
    if token not in dynamic_registry:
        errors.append(f'dynamic Receiver lifecycle model missing: {token}')

manifest_runtime = (ROOT / 'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/component/receiver/ManifestReceiverRegistry.java').read_text()
for token in [
    'MANIFEST_RECEIVER_PACKAGE_NOT_INDEXED', 'receiverProcess',
    'actionIndexEntryCount()', 'Snapshot(', 'clear()',
]:
    if token not in manifest_runtime:
        errors.append(f'Manifest Receiver lifecycle model missing: {token}')

status_snapshot = (ROOT / 'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/RuntimeStatusSnapshot.java').read_text()
for token in [
    'receiverResources(', 'receiverResourceCount', 'orderedReceiverPendingCount',
    'manifestReceiverActionIndexEntryCount', 'dynamicReceiverActionSubscriptionCount',
]:
    if token not in status_snapshot:
        errors.append(f'Runtime status Receiver snapshot missing: {token}')
status_source = (ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/status/BrokerRuntimeStatusSource.java').read_text()
if 'ReceiverLifecycleCoordinator.Snapshot' not in status_source or '.receiverResources(' not in status_source:
    errors.append('Runtime status must use one Receiver lifecycle snapshot')

receiver_lifecycle = (ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/receiver/ReceiverLifecycleCoordinator.java').read_text()
for token in [
    'disconnectSession(', 'recoverSession(', 'stopSession(', 'invalidateInstance(',
    'invalidateAll(', 'purgeExpired(', 'Snapshot(', 'dynamicActionSubscriptions',
    'actionIndexEntries', 'orderedPendingTokens',
]:
    if token not in receiver_lifecycle:
        errors.append(f'Receiver lifecycle coordinator missing: {token}')
for forbidden in [
    'receiverRuntime.removeSession(', 'manifestReceiverRuntime.removeSession(',
    'manifestReceiverRuntime.removePackage(', 'orderedReceiverRuntime.cancelSession(',
    'orderedReceiverRuntime.cancelInstance(', 'orderedReceiverRuntime.cancelAll(',
    'orderedReceiverRuntime.purgeExpired()',
]:
    if forbidden in broker:
        errors.append(f'RuntimeBrokerService bypasses Receiver lifecycle coordinator: {forbidden}')

guest = (ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestComponentRuntime.java').read_text()
for token in [
    'broadcastIntent(request)', 'BROADCAST_CATEGORIES', 'BROADCAST_MIME_TYPE', 'setData',
    'OrderedReceiverPendingResultBridge.install(', 'afterOnReceive()',
]:
    if token not in guest:
        errors.append(f'Guest broadcast Intent/PendingResult propagation missing: {token}')

pending_bridge = (ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/OrderedReceiverPendingResultBridge.java').read_text()
for token in [
    'BroadcastReceiver$PendingResult', 'setPendingResult', 'getPendingResult', 'completeFromFramework',
    'ORDERED_RECEIVER_DEADLINE_MS', 'IOrderedReceiverCompletion', 'OrderedReceiverFinishToken',
]:
    if token not in pending_bridge:
        errors.append(f'ordered PendingResult bridge missing: {token}')

finish_interceptor = (ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/OrderedReceiverFinishInterceptor.java').read_text()
for token in [
    'finishReceiver', 'ScheduledExecutorService', 'purgeExpired()',
    'token instanceof OrderedReceiverFinishToken', 'Interception.handled(null)',
]:
    if token not in finish_interceptor:
        errors.append(f'ordered finishReceiver interceptor missing: {token}')

receiver_tokens = (ROOT / 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/receiver/OrderedReceiverTokenRegistry.java').read_text()
for token in [
    'MAX_ACTIVE = 256', 'MAX_TIMEOUT_MS = 10_000L', 'CompletableFuture',
    'cancelSession(', 'cancelInstance(', 'ORDERED_RECEIVER_LATE_COMPLETION',
    'ORDERED_RECEIVER_IDENTITY_MISMATCH', 'ORDERED_RECEIVER_RESULT_REJECTED',
]:
    if token not in receiver_tokens:
        errors.append(f'ordered Receiver token authority missing: {token}')
if 'Thread.sleep' in receiver_tokens or 'while (' in receiver_tokens:
    errors.append('ordered Receiver completion must not use polling or busy-wait')

framework_handler = (ROOT / 'sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkIdentityInvocationHandler.java').read_text()
if 'FrameworkCallInterceptor' not in framework_handler or '.intercept(' not in framework_handler:
    errors.append('Framework proxy does not expose runtime call interception')

runner = (ROOT / 'tools/static_android_compile.py').read_text()
for test in [
    'ManifestBroadcastDispatcherSelfTest', 'OrderedReceiverTokenRegistrySelfTest',
    'ReceiverLifecycleCoordinatorSelfTest', 'OrderedReceiverPendingResultBridgeSelfTest',
    'FrameworkCallInterceptorSelfTest',
]:
    if test not in runner:
        errors.append(f'broadcast bridge self-test is not executed: {test}')

if errors:
    raise SystemExit('\n'.join(f'FAIL {error}' for error in errors))
print('PASS implicit, ordered and PendingResult broadcast architecture checks')
