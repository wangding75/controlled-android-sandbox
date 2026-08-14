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

registry=require('sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/component/service/ServiceRuntimeRegistry.java',
 'REDELIVER_INTENT','stopStartId(','setForeground(','recovering(','lastStartAction','foreground')
coordinator=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeServiceCoordinator.java',
 'class RuntimeServiceCoordinator','linkToDeath','connectionDied(','recoverSession(',
 'SERVICE_CONNECTION_BINDER','SERVICE_REDELIVERED','SERVICE_START_ID','bestEffortGuestUnbind')
broker=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeBrokerService.java',
 'RuntimeServiceCoordinator serviceCoordinator','serviceCoordinator.applySuccessfulOperation',
 'componentRecoveryCoordinator.recover','serviceCoordinator.disconnectSession','serviceCoordinator.stopSession')
if 'BrokerServiceRuntime serviceRuntime' in broker:
 errors.append('RuntimeBrokerService still owns BrokerServiceRuntime directly')
if len(broker.splitlines()) > 1450:
 errors.append(f'RuntimeBrokerService exceeded bounded runtime growth: {len(broker.splitlines())} lines')
require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/broker/RuntimeComponentRecoveryCoordinator.java',
 'class RuntimeComponentRecoveryCoordinator','services.recoverSession','COMPONENT_RECOVERY_FAILED')
guest=require('sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestComponentRuntime.java',
 'START_FOREGROUND_SERVICE','STOP_SERVICE_START_ID','SET_SERVICE_FOREGROUND',
 'SERVICE_RECOVERY','SERVICE_REDELIVERED','SERVICE_STOPPED_BY_START_ID')
client=require('app/src/main/java/com/warden/controlledsandbox/RuntimeClient.java',
 'BoundServiceLease','SERVICE_CONNECTION_BINDER','startForegroundService','stopServiceStartId','setServiceForeground')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/broker/RuntimeServiceCoordinatorSelfTest.java',
 'connection death did not unbind Guest service','redelivery action missing','service state leaked after session stop')
require('sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/component/service/BrokerServiceRuntimeSelfTest.java',
 'stale start id stopped service','foreground state survived stop','redelivery action lost')
runner=text('tools/static_android_compile.py')
for test in ['BrokerServiceRuntimeSelfTest','RuntimeServiceCoordinatorSelfTest']:
 if runner.count(test)<1: errors.append(f'static compiler does not execute {test}')
if 'START_REDELIVER_INTENT=3' not in runner or 'START_FLAG_REDELIVERY=1' not in runner:
 errors.append('static Android Service stub lacks START_REDELIVER_INTENT')
matrix=ROOT/'verification/m3-source-capability-matrix.json'
if matrix.is_file():
 ids={x.get('id') for x in json.loads(matrix.read_text()).get('capabilities',[])}
 for expected in ['runtime.service-coordinator-split','runtime.service-connection-death-cleanup',
                  'runtime.service-sticky-redelivery-recovery','runtime.foreground-service-state-model']:
  if expected not in ids: errors.append(f'capability matrix missing {expected}')
else: errors.append('missing capability matrix')
if errors:
 print('FAIL M4-T14 Service lifecycle checks',file=sys.stderr)
 for e in errors: print(' - '+e,file=sys.stderr)
 raise SystemExit(1)
print(f'PASS M4-T14 Service lifecycle checks (broker lines={len(broker.splitlines())}, coordinator lines={len(coordinator.splitlines())})')
