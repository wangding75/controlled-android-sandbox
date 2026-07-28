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
 'restore(ActivityTaskCheckpoint','adoptRestoredProcessGeneration')
store=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityTaskCheckpointStore.java',
 'LEGACY_SCHEMA','CURRENT_SCHEMA','CRC32','ATOMIC_MOVE','quarantineCorrupt','MAX_FILE_BYTES',
 'ACTIVITY_TASK_CHECKPOINT_SCHEMA_UNSUPPORTED')
runtime=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/BrokerActivityRuntime.java',
 'ActivityLaunchSpecFactory.create','ActivityTaskOperationDispatcher','clearMismatchedRevision',
 'clearPackageInstance','persistCheckpoint')
dispatcher=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityTaskOperationDispatcher.java',
 'QUERY_RUNNING','QUERY_RECENT','MOVE_TO_FRONT','MOVE_TO_BACK','REMOVE_TASK',
 'FINISH_AFFINITY','FINISH_AND_REMOVE_TASK','packageRevision()')
factory=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityLaunchSpecFactory.java',
 'DocumentLaunchMode.ALWAYS','DocumentLaunchMode.INTO_EXISTING','LaunchFlags.NEW_DOCUMENT',
 'LaunchFlags.MULTIPLE_TASK')
broker=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java',
 'activityRuntime.clearMismatchedRevision','activityRuntime.clearPackageInstance',
 'activityTaskOperation(ActivityTaskRequest request)')
require('sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IRuntimeBroker.aidl',
 'ActivityTaskResult activityTaskOperation(in ActivityTaskRequest request);')
require('sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/ActivityTaskRequest.java',
 'implements Parcelable','MOVE_TO_BACK','FINISH_AFFINITY','FINISH_AND_REMOVE_TASK',
 'activityToken','protocolVersion')
require('sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/ActivityTaskSnapshot.java',
 'implements Parcelable','packageRevision','documentLaunchMode','documentKey')
require('sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedgerSelfTest.java',
 'testLaunchFlagValidationMatrix','testDocumentLaunchModes','testFinishMoveBackAndRevisionCleanup',
 'testForwardResultChain','testCheckpointRestoreDropsTransportAndPreservesState')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/activity/ActivityTaskCheckpointStoreSelfTest.java',
 'schema-1 checkpoint must remain readable','corrupt checkpoint should fail closed')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/activity/ActivityTaskContractSelfTest.java',
 'finish operation without Activity token must fail','typed task projection lost package revision')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/activity/BrokerActivityRuntimeSelfTest.java',
 'MOVE_TO_BACK should reorder','finishAndRemoveTask should remove')
require('docs/plans/M4_T15_DEVELOPMENT_PLAN.md',
 'B1：LaunchMode','B2：Result','B3：Framework')
require('docs/M4_T15_B1_DEVELOPMENT_REPORT.md',
 'SOURCE/HOST PASS CANDIDATE','schema 1','设备证据仍为 0')
runner=text('tools/static_android_compile.py')
for test in ['ActivityTaskLedgerSelfTest','ActivityTaskCheckpointStoreSelfTest',
             'ActivityTaskContractSelfTest','BrokerActivityRuntimeSelfTest']:
 if runner.count(test)<1: errors.append(f'static compiler does not execute {test}')
if len(broker.splitlines()) > 1375:
 errors.append(f'RuntimeBrokerService exceeded bounded M4-T15 growth: {len(broker.splitlines())} lines')
if len(runtime.splitlines()) > 330:
 errors.append(f'BrokerActivityRuntime should remain extracted: {len(runtime.splitlines())} lines')
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
