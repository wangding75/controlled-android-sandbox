# P1-15 Reference decision — native normal paths, JNI, FD, and page-size boundary

Date: 2026-09-09. This decision is recorded before the P1-15 fixture or runtime change.

## Reference methods actually examined

| Source | Method / observed behavior | CAS counterpart | Decision |
| --- | --- | --- | --- |
| NewBlackbox | `IOCore.enableRedirect` (around lines 107–180) derives package-private data, native-library, external-storage, profile, and proc rules before enabling native I/O. | `GuestRuntimeEnvironment.prepare` supplies immutable guest roots and ABI metadata; `NativePathPolicy`/`NativeFileSystemResolver` apply them before guest-module relocation patching. | Retain the CAS revision-scoped policy; do not import NBB's broad string-map rules or treat its startup ordering as a reason to widen path access. |
| NewBlackbox | `UnixFileSystemHook.init` (around lines 71–115) installs individual JNI `java/io/UnixFileSystem` replacements and deliberately continues after a hook failure. | CAS `NativeHookRuntime` scans guest PLT/GOT relocations and installs replacements from `replacement_for`, while `NativeLibraryLoaderPolicy` validates loader inputs. | A successful individual hook is evidence only for that call path. Preserve CAS's per-symbol audit/failure state; do not convert an optional JNI hook failure into a blanket I/O success. |
| VirtualApp OSS | `NativeEngine.startDexOverride`, `redirectDirectory`, `redirectFile`, and `enableIORedirect` delegate Java path registration and initialize native redirection. | CAS packages immutable, verified base/split/native artifacts and configures the native policy before guest code executes. | Do not adopt VA's mutable APK-path snapshot or its catch-and-return-original-path behavior, which could silently expose host paths. |
| VirtualApp OSS | `IOUniformer::hook_dlopen` / `startUniformer` selects linker internals by historical API level and hooks libc/linker symbols. | CAS `controlled_dlopen` and `controlled_android_dlopen_ext` delegate to `NativeLibraryLoaderPolicy`, which constrains guest/system sources, ELF headers, FD offsets, RELRO, reserved-address alignment, and foreign namespaces. | VA OSS's API-specific private-linker hooks are not a modern template. Keep CAS's public-call PLT/GOT contract and explicitly retain custom-loader/raw-SVC boundaries. |
| VirtualApp README | The public OSS tree is historically old; commercial update claims do not expose an implementation. | No CAS counterpart can be inferred. | VA PRO equivalence remains `NOT_PROVEN`. |

## Android contract and validation boundary

The normal-path proof is intentionally narrow: a guest JNI call must return its actual value; file
contents must remain inside the configured guest projection; `dlopen`/`android_dlopen_ext` inputs
must resolve only from verified guest libraries or the bounded system allowlist; and FD validation
must account for the actual descriptor lifecycle. ELF class, machine, type, and ZIP/FD offset are
independent checks. `android_dlopen_ext` reserved-address alignment is checked against the runtime
page size, not the API level.

The existing native matrix already records that PLT/GOT and libc `syscall()` mediation is
`TRUSTED_COMPAT`, not hostile-process isolation. Raw SVC, handwritten ELF/mmap loaders, foreign
linker namespaces, debug seccomp experiments, and unmodelled FD inheritance therefore remain
explicit boundaries. An x86_64 16 KB result is evidence only for its recorded ABI/page-size
coordinate; it is not ARM64 evidence and is handed to conditional P2-11 if that release coordinate
is requested.

## Reproduction and change boundary

`NATIVE-ADV-010` emits a per-case JSON record from the fixture, but the capability runner requires
the log marker `CASE NATIVE-ADV-010 done`. The historical missing marker is an incomplete
observability contract, not evidence that `/dev/binder` access is a known limitation and not a
reason to change native policy. First reproduce it on the current x86_64/4 KB AVD. If it recurs,
the only permitted code change is to make the test-only adversarial fixture emit completion markers
for every completed case (including `010`) after its own result has been collected. The marker must
not alter case status, open a Binder transaction, add permissions, change `NativeHookRuntime`, or
reclassify a failed/blocked case.

Then run the targeted native normal-path fixture and loader/FD self-tests plus the existing 16 KB
short-test evidence. A production runtime modification is permitted only if a repeatable normal
path contradicts the policy described above; no such contradiction has been established from source
inspection.

## Explicitly rejected changes

* allowing raw SVC, a custom loader, an arbitrary linker namespace, or arbitrary host/system
  libraries in order to manufacture a pass;
* swallowing JNI/loader errors, defaulting a library source to trusted, or claiming FD isolation
  from a metadata ledger;
* deriving a 16 KB or ARM64 claim from API level, x86_64, or MuMu translation; and
* treating the VA OSS README/changelog as evidence of VA PRO behavior.
