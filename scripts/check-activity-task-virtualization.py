#!/usr/bin/env python3
from pathlib import Path
import json, sys
ROOT=Path(__file__).resolve().parents[1]
errors=[]

def text(rel):
 p=ROOT/rel
 if not p.is_file(): errors.append(f"missing required file: {rel}"); return ""
 return p.read_text(encoding='utf-8')

def require(rel,*tokens):
 c=text(rel)
 for token in tokens:
  if token not in c: errors.append(f"{rel} missing evidence: {token}")
 return c

flags=require('sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/activity/LaunchFlags.java',
 'NO_HISTORY','FORWARD_RESULT','NEW_TASK','MULTIPLE_TASK','CLEAR_TOP','CLEAR_TASK',
 'NEW_DOCUMENT','REORDER_TO_FRONT')
require('sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/activity/DocumentLaunchMode.java',
 'INTO_EXISTING','ALWAYS','NEVER')
ledger=require('sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedger.java',
 'validateLaunchFlagCombinations','findDocumentTask','finishAffinity(','finishAndRemoveTask(',
 'moveTaskToBack(','clearPackageRevision(','TASK_REVISION_MISMATCH','checkpoint()',
 'restore(ActivityTaskCheckpoint','adoptRestoredProcessGeneration',
 'registerActivityResult','captureRollbackState','restoreRollbackState')
store=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityTaskCheckpointStore.java',
 'LEGACY_SCHEMA','CURRENT_SCHEMA','CRC32','DurableAtomicFile.replacePrepared','quarantineCorrupt','MAX_FILE_BYTES',
 'ACTIVITY_TASK_CHECKPOINT_SCHEMA_UNSUPPORTED','PREVIOUS_SCHEMA')
runtime=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/BrokerActivityRuntime.java',
 'ActivityLaunchSpecFactory.create','ActivityTaskOperationDispatcher','clearMismatchedRevision',
 'clearPackageInstance','persistCheckpoint','ActivityResultOperationDispatcher')
dispatcher=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityTaskOperationDispatcher.java',
 'QUERY_RUNNING','QUERY_RECENT','MOVE_TO_FRONT','MOVE_TO_BACK','REMOVE_TASK',
 'FINISH_AFFINITY','FINISH_AND_REMOVE_TASK','packageRevision()')
factory=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityLaunchSpecFactory.java',
 'DocumentLaunchMode.ALWAYS','DocumentLaunchMode.INTO_EXISTING','LaunchFlags.NEW_DOCUMENT',
 'LaunchFlags.MULTIPLE_TASK')
broker=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java',
 'activityRuntime.clearMismatchedRevision','activityRuntime.clearPackageInstance',
 'activityTaskOperation(ActivityTaskRequest request)',
 'activityResultOperation(ActivityResultRequest request)')
require('sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IRuntimeBroker.aidl',
 'ActivityTaskResult activityTaskOperation(in ActivityTaskRequest request);',
 'ActivityResultResult activityResultOperation(in ActivityResultRequest request);')
require('sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/ActivityTaskRequest.java',
 'implements Parcelable','MOVE_TO_BACK','FINISH_AFFINITY','FINISH_AND_REMOVE_TASK',
 'activityToken','protocolVersion')
require('sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/ActivityTaskSnapshot.java',
 'implements Parcelable','packageRevision','documentLaunchMode','documentKey')
require('sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/activity/ResultIntentSnapshot.java',
 'MAX_EXTRAS','clipDescription','extras')
require('sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/ActivityResultRequest.java',
 'implements Parcelable','REGISTER','FINISH','DRAIN','registryKey')
result_dispatcher=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityResultOperationDispatcher.java',
 'registerActivityResult','finishWithResult','drainActivityResults','captureRollbackState')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityCheckpointTransaction.java',
 'captureRollbackState','restoreRollbackState','persistCheckpoint')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestActivityResultBridge.java',
 'launchForResult','intentSenderToken','ActivityResultRequest.DRAIN','toIntent')
framework_tasks=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/ActivityTaskFrameworkInterceptor.java',
 'getTasks','getRecentTasks','getAppTasks','moveTaskToFront','removeTask',
 'moveActivityTaskToBack','finishActivityAffinity','finishActivityAndRemoveTask',
 'getTaskForActivity','VIRTUAL_ACTIVITY_FRAMEWORK_TOKEN_UNKNOWN')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestActivityTaskClient.java',
 'IRuntimeBroker.Stub.asInterface','activityTaskOperation','VIRTUAL_TASK_BROKER_UNAVAILABLE',
 'VIRTUAL_TASK_RESPONSE_IDENTITY_MISMATCH')
projector=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/AndroidTaskInfoProjector.java',
 'RunningTaskInfo','RecentTaskInfo','android.app.IAppTask','ParceledListSlice',
 'VIRTUAL_TASK_PROJECTION_UNAVAILABLE','attachInterface')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestFrameworkCallRouter.java',
 'activityTasks.intercept','activityTasks.close')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestPackageSpec.java',
 'runtimeBrokerBinder','RUNTIME_BROKER_BINDER')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/StubActivityBase.java',
 'bindActivityTaskHost','consumeActivityTaskFinalized','moveHostTaskToBack',
 'finishHostAffinity','finishHostAndRemoveTask')
require('sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedgerSelfTest.java',
 'testLaunchFlagValidationMatrix','testDocumentLaunchModes','testFinishMoveBackAndRevisionCleanup',
 'testForwardResultChain','testCheckpointRestoreDropsTransportAndPreservesState',
 'testRegistryResultIntentAndIntentSender','testCheckpointRestoresPendingResultOwnership',
 'testExactRollbackState')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/activity/ActivityTaskCheckpointStoreSelfTest.java',
 'schema-1 checkpoint must remain readable','schema-2 checkpoint must preserve revision',
 'corrupt checkpoint should fail closed')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/activity/ActivityTaskContractSelfTest.java',
 'finish operation without Activity token must fail','typed task projection lost package revision')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/activity/BrokerActivityRuntimeSelfTest.java',
 'MOVE_TO_BACK should reorder','finishAndRemoveTask should remove',
 'failed restore must roll back partial ledger state','failed cleanup checkpoint must restore')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/ActivityTaskFrameworkInterceptorSelfTest.java',
 'running-task query is intercepted','AppTask Binder exposes only virtual task',
 'cross-package AppTask query rejected','Broker query failure throws instead of passing to host delegate')
require('docs/plans/M4_T15_DEVELOPMENT_PLAN.md',
 'B1：LaunchMode','B2：Result','B3：Framework')
require('docs/M4_T15_B1_DEVELOPMENT_REPORT.md',
 'PASS — SOURCE/HOST VERIFIED','schema 1','设备证据仍为 0')
require('docs/M4_T15_B2_DEVELOPMENT_REPORT.md',
 'PASS — SOURCE/HOST VERIFIED','ActivityResultIntentSnapshot','事务回滚','设备证据仍为 0')
require('docs/M4_T15_B3_DEVELOPMENT_REPORT.md',
 'PASS — SOURCE/HOST VERIFIED','IAppTask','不允许以宿主真实任务结果作为兼容回退','设备证据：0')
require('docs/M4_T15_STAGE_REPORT.md',
 'PASS — SOURCE/HOST VERIFIED','能力条目 | 96','M4-T16：系统调度与通知深化',
 '设备证据完成度 | 0.0%')
runner=text('tools/static_android_compile.py')
for test in ['ActivityTaskLedgerSelfTest','ActivityTaskCheckpointStoreSelfTest',
             'ActivityTaskContractSelfTest','ActivityResultContractSelfTest',
             'BrokerActivityRuntimeSelfTest','ActivityTaskFrameworkInterceptorSelfTest']:
 if runner.count(test)<1: errors.append(f'static compiler does not execute {test}')
if len(broker.splitlines()) > 1375:
 errors.append(f'RuntimeBrokerService exceeded bounded M4-T15 growth: {len(broker.splitlines())} lines')
if len(runtime.splitlines()) > 330:
 errors.append(f'BrokerActivityRuntime should remain extracted: {len(runtime.splitlines())} lines')
if len(result_dispatcher.splitlines()) > 180:
 errors.append(f'ActivityResultOperationDispatcher unexpectedly large: {len(result_dispatcher.splitlines())} lines')
if len(framework_tasks.splitlines()) > 360:
 errors.append(f'ActivityTaskFrameworkInterceptor unexpectedly large: {len(framework_tasks.splitlines())} lines')
if len(projector.splitlines()) > 280:
 errors.append(f'AndroidTaskInfoProjector unexpectedly large: {len(projector.splitlines())} lines')
if len(dispatcher.splitlines()) > 180:
 errors.append(f'ActivityTaskOperationDispatcher unexpectedly large: {len(dispatcher.splitlines())} lines')
matrix=ROOT/'verification/m3-source-capability-matrix.json'
if matrix.is_file():
 ids={x.get('id') for x in json.loads(matrix.read_text()).get('capabilities',[])}
 for expected in ['framework.activity-launch-flag-semantics','framework.activity-result-forwarding',
                  'runtime.activity-task-checkpoint','runtime.activity-task-query-contract',
                  'framework.activity-recent-task-policy']:
  if expected not in ids: errors.append(f'capability matrix missing {expected}')
else: errors.append('missing capability matrix')
if errors:
 print('FAIL M4-T15 Activity/Task virtualization checks',file=sys.stderr)
 for e in errors: print(' - '+e,file=sys.stderr)
 raise SystemExit(1)
print(f'PASS M4-T15 Activity/Task virtualization checks (ledger lines={len(ledger.splitlines())}, broker lines={len(broker.splitlines())}, runtime lines={len(runtime.splitlines())})')
