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
 'NO_HISTORY','FORWARD_RESULT','EXCLUDE_FROM_RECENTS','RETAIN_IN_RECENTS','NEW_DOCUMENT')
ledger=require('sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedger.java',
 'validateForwardResult','retireNoHistoryCaller','runningTasks(','recentTasks(','moveTaskToFront(',
 'removeTask(','checkpoint()','restore(ActivityTaskCheckpoint','adoptRestoredProcessGeneration')
store=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityTaskCheckpointStore.java',
 'CRC32','ATOMIC_MOVE','quarantineCorrupt','MAX_FILE_BYTES','ACTIVITY_TASK_CHECKPOINT_CRC_INVALID')
runtime=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/BrokerActivityRuntime.java',
 'configureCheckpointStore','taskOperation(','ActivityTaskRequest.QUERY_RUNNING',
 'ActivityTaskRequest.QUERY_RECENT','ActivityTaskRequest.MOVE_TO_FRONT',
 'ActivityTaskRequest.REMOVE_TASK','persistCheckpoint')
broker=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java',
 'activityRuntime.configureCheckpointStore','activityTaskOperation(ActivityTaskRequest request)',
 'ActivityTaskContractFailure.from(request, error)')
require('sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IRuntimeBroker.aidl',
 'ActivityTaskResult activityTaskOperation(in ActivityTaskRequest request);')
require('sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/ActivityTaskRequest.java',
 'implements Parcelable','QUERY_RUNNING','MOVE_TO_FRONT','protocolVersion')
require('sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/ActivityTaskResult.java',
 'implements Parcelable','SandboxError','List<ActivityTaskSnapshot>')
require('sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/ActivityTaskSnapshot.java',
 'implements Parcelable','baseComponentName','moveToFrontCount')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity/ActivityTaskContractFailure.java',
 'ACTIVITY_TASK_FORBIDDEN','ACTIVITY_TASK_INVALID_REQUEST','ActivityTaskResult.failure')
require('sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/activity/ActivityTaskLedgerSelfTest.java',
 'testForwardResultChain','testNoHistoryAndRecentTaskPolicy','testRunningRecentMoveAndRemoveQueries',
 'testCheckpointRestoreDropsTransportAndPreservesState')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/activity/ActivityTaskCheckpointStoreSelfTest.java',
 'corrupt checkpoint should fail closed','checkpoint codec round trip changed state')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/activity/ActivityTaskContractSelfTest.java',
 'typed task result lost payload','task mutation without taskId must fail')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/activity/BrokerActivityRuntimeSelfTest.java',
 'running-task query should expose owned task','Broker restart should restore persisted task')
require('docs/fixes/m4-t15-activity-task-virtualization.md',
 'SOURCE/HOST PASS. DEVICE NOT TESTED.','Dead Binder/route authority','Known limitations')
require('docs/comparisons/M4_T15_VA_NBB_COMPARISON.md',
 'Device evidence','M4-T16','not equivalent')
require('docs/M4_T15_STAGE_REPORT.md',
 'SOURCE/HOST PASS CANDIDATE','Subsequent iteration plan','Remaining uncertainty')
runner=text('tools/static_android_compile.py')
for test in ['ActivityTaskLedgerSelfTest','ActivityTaskCheckpointStoreSelfTest',
             'ActivityTaskContractSelfTest','BrokerActivityRuntimeSelfTest']:
 if runner.count(test)<1: errors.append(f'static compiler does not execute {test}')
if len(broker.splitlines()) > 1375:
 errors.append(f'RuntimeBrokerService exceeded bounded M4-T15 growth: {len(broker.splitlines())} lines')
if len(runtime.splitlines()) > 380:
 errors.append(f'BrokerActivityRuntime unexpectedly large: {len(runtime.splitlines())} lines')
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
print(f'PASS M4-T15 Activity/Task virtualization checks (ledger lines={len(ledger.splitlines())}, broker lines={len(broker.splitlines())})')
