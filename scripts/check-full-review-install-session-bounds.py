#!/usr/bin/env python3
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1]
errors=[]
def read(rel):
 p=ROOT/rel
 if not p.is_file(): errors.append('missing '+rel); return ''
 return p.read_text(encoding='utf-8-sig')
store=read('app/src/main/java/com/warden/controlledsandbox/PackageInstallSessionStore.java')
for token in ('MAX_ACTIVE_SESSIONS = 64','INSTALL_SESSION_QUOTA_EXCEEDED','activeSessionCount()'):
 if token not in store: errors.append('install-session store missing '+token)
aidl=read('sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl')
methods=[line.strip() for line in aidl.splitlines() if line.strip().endswith(');') or line.strip()=='void close();']
if 'InstallSessionPage listInstallSessionsPage(in VirtualPageRequest request);' not in methods:
 errors.append('paged install-session AIDL method missing')
if methods[-1] != 'InstallSessionPage listInstallSessionsPage(in VirtualPageRequest request);':
 errors.append('paged install-session method must be appended to preserve transaction IDs')
session=read('app/src/main/java/com/warden/controlledsandbox/PackageManagementSession.java')
for token in ('listInstallSessionsPage(VirtualPageRequest request)','installSessionPager.page','installSessionPager.legacy'):
 if token not in session: errors.append('management session missing '+token)
client=read('app/src/main/java/com/warden/controlledsandbox/PackageServiceClient.java')
if 'listInstallSessionsPage' not in client: errors.append('client does not consume paged API')
runner=read('tools/static_android_compile.py')
if runner.count('com.warden.controlledsandbox.InstallSessionPagingSelfTest') != 1:
 errors.append('paging regression is not executed exactly once')
verify=read('scripts/verify-all.sh')
if 'python3 scripts/check-full-review-install-session-bounds.py' not in verify:
 errors.append('verify-all missing install-session bounds gate')
if errors:
 print('FAIL full-review install-session bounds',file=sys.stderr)
 for e in errors: print(' - '+e,file=sys.stderr)
 raise SystemExit(1)
print('PASS install-session quota and append-only paged Binder API')
