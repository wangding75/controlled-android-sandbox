from pathlib import Path
import hashlib
import html
import json
import re
from collections import Counter

ROOT = Path(r'D:\github\controlled-android-sandbox')
NBB = Path(r'D:\github\t57-reference-sources\NewBlackbox')
VA = Path(r'D:\github\t57-reference-sources\VirtualApp')
OUT = Path(__file__).resolve().parent / 'delivery'
OUT.mkdir(parents=True, exist_ok=True)
HEAD = '40f629d03bbb0927f171a0e318c242398d8a141d'
SOURCES = {}


def source(sid, root, relative, anchor, note):
    path = root / relative
    assert path.is_file(), str(path)
    content = path.read_text(encoding='utf-8-sig')
    lines = content.splitlines()
    line = next((i for i, value in enumerate(lines, 1) if anchor in value), None)
    assert line is not None, (sid, anchor)
    SOURCES[sid] = dict(id=sid, path=path.as_posix(), line=line, anchor=anchor,
                        note=note, sha256=hashlib.sha256(path.read_bytes()).hexdigest())


C = 'sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/'
F = 'sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/'
A = 'app/src/main/java/com/warden/controlledsandbox/'
D = 'sandbox-domain/src/main/java/com/warden/controlledsandbox/domain/'
N = 'Bcore/src/main/java/top/niunaijun/blackbox/'
V = 'VirtualApp/lib/src/main/java/com/lody/virtual/'

source('C01', ROOT, A+'ApkImportManager.java', 'class ApkImportManager', 'APK 导入、修订目录和解析入口；研究包安装行为的 CAS 定位点。')
source('C02', ROOT, A+'VirtualPackageStateBuilder.java', 'private static void appendHostSharedLibraries(', '读取宿主 SharedLibraryInfo，供安装依赖解析。')
source('C03', ROOT, A+'VirtualPackageStateBuilder.java', 'private Map<String, HostSharedLibraryProjection> hostSharedLibraryProjections(', '当前 HEAD 新增的共享库提供包与文件投影。')
source('C04', ROOT, C+'guest/GuestSharedLibraryPathResolver.java', 'static List<String> resolvedSharedLibraryFiles(', '合并虚拟提供包和 authority 批准的宿主提供包路径；不是尚未实现。')
source('C05', ROOT, C+'guest/GuestApplicationInfoFactory.java', 'setOptionalField(info, "sharedLibraryFiles"', '把同一依赖集合投影到 ApplicationInfo。')
source('C06', ROOT, C+'guest/GuestRuntimeEnvironment.java', 'ClassLoader frameworkLoader = loader.definingLoader();', '平台 defining loader → AppComponentFactory → LoadedApk → Application → Provider → onCreate。')
source('C07', ROOT, C+'guest/GuestLoadedApkBridge.java', 'static GuestLoadedApkBridge install(', '安装真实 LoadedApk 的 loader、资源与 Application 投影。')
source('C08', ROOT, C+'guest/GuestClassLoader.java', 'public final class GuestClassLoader', '普通 PathClassLoader 包装与 isolated InMemoryDexClassLoader 路径。')
source('C09', ROOT, C+'guest/GuestContext.java', 'private void appendApi36WebViewAssets(', 'API36 WebView 提供包资源路径补充；需要真实 provider 资源验证。')
source('C10', ROOT, C+'guest/GuestIntentResolver.java', 'Target resolveOne(', '先虚拟 PMS，再允许的宿主系统 Service；剩余 miss 仍抛 NO_GUEST_*_MATCH。')
source('C11', ROOT, C+'guest/HostPackageManagerBridge.java', 'private static Object invoke(', '当前按方法名选首个反射方法，并按参数类型推导 flags/userId；需签名审计。')
source('C12', ROOT, C+'guest/GuestContextComponentRouter.java', 'synchronized boolean bindService(', '当前新增宿主 ServiceConnection relay；与虚拟 Service 生命周期分流。')
source('C13', ROOT, C+'guest/GuestContentProviderFrameworkInterceptor.java', 'ProviderInfo hostProvider = HostPackageManagerBridge.resolveContentProvider(', '当前新增 exported system Provider 分流；未匹配 authority 仍统一抛异常。')
source('C14', ROOT, F+'packagemanager/PackageManagerInvocationHandler.java', 'class PackageManagerInvocationHandler', '包可见性和查询返回投影，与实际启动/绑定需一致。')
source('C15', ROOT, F+'core/FrameworkIdentityInvocationHandler.java', 'class FrameworkIdentityInvocationHandler', '系统调用身份重写与框架拦截入口。')
source('C16', ROOT, F+'core/FrameworkHooks.java', 'class FrameworkHooks', '系统服务代理安装与恢复入口；不是以类数衡量兼容性。')
source('C17', ROOT, C+'guest/WebViewProfileManager.java', 'static synchronized Profile install(', 'WebView 首次初始化前设置进程级 dataDirectorySuffix。')
source('C18', ROOT, C+'guest/GuestWebViewProviderServiceBridge.java', 'class GuestWebViewProviderServiceBridge', 'WebView 提供包的系统 Service 路由。')
source('C19', ROOT, F+'core/GmsCompatibilityBoundary.java', 'public enum Status', '明确只给出 BASIC_BOUNDARY / DEFERRED_GMS_RUNTIME，不是完整 GMS。')
source('C20', ROOT, 'sandbox-native/src/main/cpp/native_hook.cpp', 'bool NativeHookRuntime::refresh()', '按已加载 ELF 遍历与 PLT/GOT 重定位刷新；区分翻译 ABI。')
source('C21', ROOT, 'sandbox-native/src/main/cpp/native_interceptors.cpp', 'extern "C" long controlled_syscall(', 'libc syscall 入口也有代理；inline SVC 不受同一 PLT 改写约束。')
source('C22', ROOT, 'sandbox-native/src/main/cpp/native_loader.cpp', 'DLEXT_RELRO_FLAGS_CONFLICT', 'Native loader 对 DLEXT/RELRO 的有界支持，须验证目标实际加载路径。')
source('C23', ROOT, A+'NativeAbiRoutePlanner.java', 'static Route route(', '64 位主包、32 位 companion 分流；不提供 CPU 指令翻译。')
source('C24', ROOT, 'sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/ProcessSlotContract.java', 'ORDINARY_SLOT_COUNT', '普通 64 槽 + isolated 16 槽。')
source('C25', ROOT, C+'broker/RuntimeGuestConnectionPool.java', 'class RuntimeGuestConnectionPool', 'Guest Binder 保持与进程 owner。')
source('C26', ROOT, D+'session/SessionRegistry.java', 'class SessionRegistry', 'session/generation/回收状态的权威记录。')
source('C27', ROOT, C+'protocol/RuntimeIntentWireCodec.java', 'MAX_ROUTED_INTENT_PAYLOAD_BYTES', '当前已有有界大 Intent 路由传输；旧超大事务问题应先复现，不从零重做。')
source('C28', ROOT, C+'protocol/RebindableServiceConnector.java', 'class RebindableServiceConnector', '连接 epoch、超时、晚到回调和回收。')
source('C29', ROOT, C+'guest/GuestActivityThreadServiceBridge.java', 'class GuestActivityThreadServiceBridge', '普通 Service 使用 ActivityThread 消息与真实 token。')
source('C30', ROOT, F+'routing/VirtualPendingIntentRegistry.java', 'class VirtualPendingIntentRegistry', '虚拟 PendingIntent 身份、代次和发送路由。')
source('C31', ROOT, F+'identity/VirtualSystemServiceAuthority.java', 'interface VirtualSystemServiceAuthority', 'Account/Alarm/Job/通知命名空间等有界权威合同。')
source('C32', ROOT, C+'broker/GuestRecoveryPrewarmCoordinator.java', 'class GuestRecoveryPrewarmCoordinator', 'PREPARING 与预热失败恢复边界。')
source('C33', ROOT, 'sandbox-companion32/src/main/java/com/warden/controlledsandbox/companion32/NativeCompanionService.java', 'class NativeCompanionService', '32 位伴生运行入口，只在设备 ABI 与本轮范围允许时验收。')
source('T01', ROOT, 'tools/verification/run_rd_smoke.py', 'def _parser()', '现存验证入口，支持 --only-case / --no-diagnostic-retry / --arm64-physical。')
source('T02', ROOT, 'tools/verification/run_api33_capabilities.py', 'def _parser()', '跨 API 能力矩阵入口，须读取每 case 的 errors 与缺失 marker。')
source('T03', ROOT, 'tools/verification/test_harness.py', 'import', '现存 harness 自检入口；本轮没有运行设备测试。')
source('N01', NBB, N+'app/BActivityThread.java', 'public synchronized void handleBindApplication(', '从虚拟 PMS 取 ApplicationInfo，创建 Context/LoadedApk、初始化 native/IO、构造 Application。')
source('N02', NBB, N+'core/system/pm/PackageManagerCompat.java', 'private static void fixJar(', '这里将 sharedLibraryFiles 设置为 Apache legacy 路径集合，不是完整 Trichrome 通用解法。')
source('N03', NBB, N+'fake/service/IActivityManagerProxy.java', 'public static class GetContentProvider', '系统 authority 分流、虚拟 provider 进程和 holder 替换；Service 有虚拟/宿主两条路径。')
source('N04', NBB, N+'fake/service/IPackageManagerProxy.java', 'public static class ResolveService', '虚拟 PMS 无结果时回真实 PMS；另有构造 Play 商店结果与权限默认值的分支，不能视为业务成功。')
source('N05', NBB, N+'core/system/BProcessManagerService.java', 'public ProcessRecord startProcessLocked(', '按虚拟 uid/process 管理 ProcessRecord，初始化 client 并监听 Binder death。')
source('N06', NBB, N+'core/system/am/ActivityStack.java', 'class ActivityStack', 'ActivityRecord/系统任务和代理 Intent 路由。')
source('N07', NBB, N+'core/system/am/ActiveServices.java', 'class ActiveServices', '已启动和绑定 Service 记录与宿主代理服务。')
source('N08', NBB, N+'core/IOCore.java', 'public void enableRedirect(', '在 Guest 启动前配置私有目录、外部目录和 proc 路径重定向。')
source('N09', NBB, 'Bcore/src/main/cpp/Hook/UnixFileSystemHook.cpp', 'void UnixFileSystemHook::init(', 'JNI UnixFileSystem 方法替换；个别 Hook 失败继续，不构成全部 IO 通过证据。')
source('N10', NBB, N+'fake/hook/HookManager.java', 'public void init()', '注册系统服务和厂商代理，仅注册数量不能证明注入。')
source('N11', NBB, N+'fake/hook/ClassInvocationStub.java', 'public void injectHook()', 'getWho() 返回 null 即提前退出，不创建 Proxy、不绑定方法。')
source('N12', NBB, N+'fake/service/IXiaomiAttributionSourceProxy.java', 'protected Object getWho()', '该类 getWho() 返回 null；其命名和内部方法不等于实际 Hook 到小米对象。')
source('N13', NBB, N+'fake/service/IXiaomiMiuiServicesProxy.java', 'protected Object getWho()', '同样返回 null；不能据此宣称小米兼容已完成。')
source('N14', NBB, N+'core/GmsCore.java', 'class GmsCore', 'GMS/GSF/商店组件集合与安装管理参考，不证明账号或网络业务通过。')
source('N15', NBB, N+'fake/service/IJobServiceProxy.java', 'class IJobServiceProxy', 'Job 系统边界参考。')
source('N16', NBB, N+'fake/service/INotificationManagerProxy.java', 'class INotificationManagerProxy', 'Notification 系统边界参考。')
source('N17', NBB, N+'core/env/AppSystemEnv.java', 'public static boolean isOpenPackage(String packageName)', '显式外部包可见集合，包括若干 WebView 提供包。')
source('N18', NBB, 'Bcore/build.gradle', "abiFilters 'arm64-v8a'", '当前 Gradle 构建 ABI 只有 ARM64/ARMv7；README 的 x86 与构建配置不能混为一谈。')
source('N19', NBB, 'Bcore/src/main/cpp/Hook/FileSystemHook.cpp', 'void FileSystemHook::init()', '这里只解析 open/open64 原函数并打日志，没有安装 new_open/new_open64 的代码。')
source('N20', NBB, 'Bcore/src/main/cpp/Hook/RuntimeHook.cpp', 'void RuntimeHook::init(', '按 API 区分 nativeLoad JNI 签名；函数体记录日志后调用原加载函数。')
source('V01', VA, V+'client/VClientImpl.java', 'mInitialApplication = LoadedApk.makeApplication.call', 'LoadedApk 创建 Application，安装 providers，再调用 Application.onCreate。')
source('V02', VA, V+'server/pm/parser/PackageParserEx.java', 'if (ps.dependSystem)', 'dependSystem 分支从未 Hook 的宿主 PMS 投影 sharedLibraryFiles。')
source('V03', VA, V+'client/hook/proxies/am/MethodProxies.java', 'static class BindService extends', 'Service 按虚拟解析结果分流；provider holder/Binder 调用见同文件 GetContentProvider。')
source('V04', VA, V+'client/hook/proxies/pm/MethodProxies.java', 'return "queryIntentServices";', '虚拟结果与符合可见性条件的外部服务查询组合。')
source('V05', VA, V+'server/am/VActivityManagerService.java', 'ProcessRecord startProcessIfNeedLocked(', '进程记录、真实 client 连接、四大组件调度。')
source('V06', VA, V+'server/am/ActivityStack.java', 'class ActivityStack', '服务端 ActivityRecord、任务栈和代理启动。')
source('V07', VA, V+'client/NativeEngine.java', 'class NativeEngine', 'Java/native 重定向与运行时初始化入口。')
source('V08', VA, 'VirtualApp/lib/src/main/jni/Foundation/IOUniformer.cpp', 'void hook_dlopen(', '旧版 libc/内部 linker 符号 Hook；不是现代 Android 的原样实现模板。')
source('V09', VA, V+'client/core/InvocationStubManager.java', 'class InvocationStubManager', '旧版系统服务代理注册参考。')
source('V10', VA, 'VirtualApp/lib/build.gradle', 'compileSdkVersion 26', '开源源码时代与默认构建 ABI：SDK26、ARMv7/x86，不能当作现代 VA PRO。')
source('V11', VA, 'README.md', '2017年12月份停止更新', '明确开源代码截止 2017；商业更新历史是公开声明。')
source('V12', VA, 'README.md', '2026年7月17号', '用户指定外部目录的商业日志更新至 2026-08-04，条目 713。')
source('E01', ROOT, 'reports/t57-r03/c6/REALAPP_COMPAT_01_CHROME_QUARK_REPORT.md', 'REAL_APP_COMPAT_01', '历史商业报告：导入修复、Chrome projection 失败、Quark 首帧后 smoke 失败。')
source('E02', ROOT, 'out/verification/realapp-compat-02-realapps-final-20260907/chrome-launch/debug-command-result.json', '"status": "FAIL"', '最新留存 Chrome 启动结果：NPE；没有精确栈不能定根因。')
source('E03', ROOT, 'out/verification/realapp-compat-02-realapps-final-20260907/quark-launch/debug-command-result.json', '"firstFrameDrawn": true', '最新留存 Quark 3013 ms 首帧；没有长于首帧的业务闭环证明。')
source('E04', ROOT, 'out/verification/realapp-compat-02-s01s10-20260907/run.json', '"start_head"', '10/10 核心用例；头信息仍为 51c53726，不能单靠目录名绑定 40f629d0。')
source('E05', ROOT, 'out/verification/realapp-compat-01-capability-final-20260907/capability-matrix.json', '"run_id"', '12 项中 8 PASS/2 FAIL/1 limitation/1 out-of-scope；两个 FAIL 是必需 marker 缺失。')
source('E06', ROOT, 'reports/t57-r03/c6/C6_T02C_ARM64_PHYSICAL_DYNAMIC_VALIDATION_REPORT.md', 'MODEL=25019PNF3C', '已留存小米 Android16/API36、ARM64-only、4KB、HyperOS OS3.0.306 报告。')
source('E07', ROOT, 'reports/t57-r03/c6/C6_T01G_CROSS_API_CLOSURE_REPORT.md', 'C6-T01G-R02', 'API32–36 历史 50/50；API37 最终图形环境 BLOCKED_ENV，不把早期结论当最终结论。')
source('E08', ROOT, 'docs/review/KNOWN_ISSUES.yaml', 'issue_id: KI-R03-057', '大 Intent/进程 ownership/窗口与恢复历史问题，含已改代码但未完整收口项目。')
source('E09', ROOT, 'docs/plans/CAS_VA_PRO_CATCH_UP_EXECUTION_PROGRESS.md', '下一任务：', '旧账本首页未跟随 REALAPP-COMPAT-02 更新；本计划要求迁移而不是静默覆盖历史。')
source('E10', ROOT, 'docs/compat/T57_R03_ANDROID_OEM_MATRIX.yaml', 'environment_id: OEM_XIAOMI_HYPEROS', '仍写环境不可用，与新真机报告不同步。')
source('E11', ROOT, 'docs/capability/CAPABILITY_REGISTRY.yaml', 'baseline_commit:', '能力登记基线与 HEAD/新矩阵不同步，必须保留分环境状态。')

TASKS = []


def task(tid, title, priority, dependencies, effort, environment, basis, cas, work, acceptance,
         deliverable, legacy='', condition='', long_run=False):
    TASKS.append(dict(id=tid, phase=1 if tid.startswith('P1') else 2, title=title,
        priority=priority, dependencies=dependencies.split() if dependencies else [],
        effort_person_days=effort, environment=environment, reference_ids=basis.split(),
        cas_ids=cas.split(), work=work, acceptance=acceptance, deliverable=deliverable,
        legacy_mapping=legacy, condition=condition, long_run=long_run,
        status='条件触发' if condition else '待执行'))


def refs(ids):
    return '；'.join(f'[{sid}]({SOURCES[sid]["path"]}:{SOURCES[sid]["line"]})' for sid in ids)


def write(name, text):
    (OUT/name).write_text(text, encoding='utf-8')


task('P1-01', '冻结当前源码与证据，修正执行基线', 'P0', '', [0.5, 1],
 '工作站；API35/36 AVD 的短基线', 'N18 V10 V11 V12', 'T01 T02 E02 E03 E04 E05 E07 E09 E10 E11',
 ['冻结 CAS/NBB/VA 提交、工作区差异、APK SHA256、构建参数与设备环境；保留旧回执。',
  '将 REALAPP-COMPAT-02 的 15 文件改动与实际 APK/运行记录对应；现有 run.json 的 parent HEAD 单独标注。',
  '把旧能力表、OEM 表、下一任务、缺失脚本路径与最新源码对齐；本新计划采用两阶段排序，旧任务 ID 保留映射。'],
 ['每条已有 PASS 能追到源码状态、APK 哈希和运行元数据；无法绑定者标为历史证据。',
  'Chrome 当前为启动 FAIL/NPE，Quark 为首帧 PASS、业务 smoke 待证；两个 capability FAIL 保留。',
  '所有新任务引用的本地文件存在；先在一个可用 AVD 跑 S01–S04，失败定位后再往下。'],
 'baseline.json、证据冲突清单、旧新任务映射、短基线回执', 'C0/C6 事实源；REALAPP-COMPAT-02')

task('P1-02', '建立每次修改前的参考源码决策单与最小复现库', 'P0', 'P1-01', [0.5, 1],
 '源码研究；JVM/native fixture；AVD', 'N01 N03 N04 N05 N08 V01 V02 V03 V04 V05 V08 V12', 'C01 C06 C10 C13 T01',
 ['每个修复先写“CAS 失败路径→NBB/VA 调用路径→可观察行为→Android 合同/独立复现→采用或不采用理由”。',
  '优先搭建共享库提供包、可选缺失 Service/Provider、跨进程 Service、重入 Provider、动态加载的可移植 fixture；先原生直装确认期望。',
  'VA PRO 只有日志的能力标为实现未知，先补可运行参考或独立复现再设计；不得由功能名臆造实现。'],
 ['每个产品修改提交都有决策单、至少一条已阅读的 NBB/VA 源码定位、差异说明及能失败的前置复现。',
  '不得复制参考实现，不得以吞异常、伪造权限/账号/回调、增大超时或重复启动替代闭环。',
  '与具体 App 无关的 fixture 可在 AVD 执行；只在 ARM/OEM 出现的差异明确转入对应 P2 任务。'],
 '逐任务 reference-decision.md、fixture 规格与原生基准', '落实用户“所有改动先分析其他沙箱”要求')

task('P1-03', '收敛共享库的包信息、文件与资源投影', 'P0', 'P1-02', [1, 2],
 'API35/36 AVD；提供包+消费包 fixture', 'V02 N01 N02', 'C02 C03 C04 C05 C07',
 ['沿 VA dependSystem 的宿主 PMS 投影路径核对 CAS 已有实现；记录 NBB fixJar 不能覆盖 Trichrome 的限制。',
  '验证库名/版本/证书、provider 包与 base/splits 来源；让 PMS、ApplicationInfo、dex、资源与 native 候选路径保持同一依赖版本。',
  '覆盖缺失、版本不符、更新失效、多个版本、必选/可选依赖；只修改复现失败的分支。'],
 ['fixture 原生与 CAS 均能读取提供包的类、资源和测试资产；共享库升级后旧路径不能继续被使用。',
  '不匹配版本/证书或不存在的提供包得到明确安装/依赖错误；普通宿主私有目录不可被引入。',
  '已有 sharedLibraryFiles/providerSourceFiles 不重复建设；真实 Chrome/ARM64 通过留给 P2-02。'],
 '共享库用例矩阵、路径/版本对照、修复与回归证据', 'REALAPP-COMPAT-01/02 Chrome 依赖链')

task('P1-04', '定位并修复启动期 Application/LoadedApk/Context 断点', 'P0', 'P1-03', [1, 3],
 'API35/36 AVD；AppComponentFactory 与 Provider bootstrap fixture', 'N01 V01', 'C06 C07 C08 C09 E02',
 ['按 NBB/VA 的初始化先后顺序核对 CAS 的 factory、defining loader、LoadedApk、attachBaseContext、Provider 和 Application.onCreate。',
  '补齐首次异常完整栈与阶段快照；最新 Chrome 的 Object.getClass() NPE 在没有栈前保持“根因未知”。',
  '复现 Application 内启动组件、Provider 重入、空字段与多 Context；可移植问题在 AVD 修复，ARM/OEM 专有栈移交 P2-02。'],
 ['每进程每代 Application attach/onCreate 各一次，Provider 初始化次序可核对；无重复 Application。',
  '构造异常保留原始 cause/阶段，清理后再次显式启动可恢复；不能补一个 null guard 就判完成。',
  'factory/资源/native loader fixture 完成 3 冷+3 热；不能在 AVD 复现的 Chrome 栈有明确 P2 移交项。'],
 'bootstrap 时序、NPE 首错栈、复现 fixture、阶段验收记录', 'REALAPP-COMPAT-02；C1-T01/ClassLoader')

task('P1-05', '统一 Service 查询、所属进程和未找到的返回语义', 'P0', 'P1-02', [1, 2],
 'API32/35/36 AVD；虚拟与外部 Service fixture', 'N03 N04 V03 V04', 'C10 C12 C14 C29',
 ['研究虚拟 PMS 命中和宿主系统 Service 分流；对齐 queryIntentServices、resolveService、getServiceInfo 和实际 bind/start。',
  '将“确实不存在/合法可选服务”“被策略拒绝”“权限不足”“解析或签名错误”分别编码；按具体 Context API 合同返回。',
  '覆盖显式/包约束/隐式 Intent、MIME null、enabled/exported、processName、回调 executor、unbind 与进程死亡。'],
 ['合法缺失 Service 的 bindService 按平台返回 false，且不会产生 NO_GUEST_SERVICE_MATCH 导致的未捕获异常。',
  '越权目标仍拒绝；查到的同名虚拟包不得误绑到宿主安装；真实 onServiceConnected/onNullBinding/disconnect 和清理可观察。',
  '3 个 API 的正例/负例一致；夸克最新系统服务绑定由 P2-03 检验。'],
 'Service owner/返回语义矩阵、回调证据、相关修复', 'REALAPP-COMPAT-01/02 Quark；C1-T02')

task('P1-06', '收敛 Provider 外部路由、空结果与授权生命周期', 'P0', 'P1-02', [1, 3],
 'API35/36 AVD；同包/跨包/缺失 Provider fixture', 'N03 V03 V04', 'C13 C15 C31',
 ['沿 VA/NBB 的 resolve authority→启动 owner→holder→IContentProvider 路径区分虚拟、系统和不可见目标。',
  '核对当前 HOST_SYSTEM 直通的身份/权限/用户边界；缺失 provider 的 acquire 返回应与平台空结果一致。',
  '验证 stable/unstable client、双 authority、递归访问、Cursor/FD、URI grant、observer 和死亡后的重新获取。'],
 ['正例 query/call/openFile 的真实内容可核验；缺失 authority 允许 SDK 正常处理 null；未导出/越权访问不被放开。',
  '重入不同 provider 不死锁，同一 provider 不重复创建；关闭/换代后不能复活旧 holder、grant 或 observer。',
  '两虚拟用户的数据不串；执行 10 次 acquire/use/release 后受管 Cursor/FD/observer 租约回到基线。'],
 'Provider 类型与权限矩阵、重入/资源回收用例、修改依据', 'REALAPP-COMPAT-02；C1-T04；KI-T57-013')

task('P1-07', '替换按方法名猜参数的 PMS 反射适配', 'P0', 'P1-05 P1-06', [0.5, 2],
 '源码签名审计；API32–36 AVD 定向调用', 'N04 V04 V09', 'C11 C14',
 ['审计 HostPackageManagerBridge.invoke 当前“第一个同名方法+参数类型推导”逻辑。',
  '对实际支持 API 的 IPackageManager 记录完整方法签名、flags 位宽、userId、resolvedType 与返回形态；根据已核对的合同适配。',
  '将方法缺失、调用异常和正常空结果分离，保存选中签名；小米新增签名留 P2-07。'],
 ['同名重载、int/long flags、null MIME、ParceledListSlice 和至少两个 userId 的测试不串参数。',
  '不支持的签名给出结构化诊断，不静默当作“目标不存在”。',
  'AVD 每个声明支持的 API 实测 query/resolve Provider/Service；未执行 API 不标 PASS。'],
 'PMS 签名表、适配与回归、未知签名处理合同', 'REALAPP-COMPAT-02 新桥接审计')

task('P1-08', '验证系统调用中的包名、UID、权限和 Attribution', 'P1', 'P1-07', [1, 2],
 'API33/35/36 AVD，平台权限正反例', 'N04 N10 V09 V12', 'C14 C15 C16 C31',
 ['以参考代理中的调用方身份修正为线索，逐方法记录 Guest 身份、物理调用身份及回包投影。',
  '覆盖 public manager/隐藏 Binder 两条入口，Permission/AppOps/Attribution chain、权限撤销与缓存刷新。',
  '以真实服务返回验证支持状态；区分 Hook 已注册、已注入、被实际调用、业务结果成功。'],
 ['允许路径取得真实数据/回调，拒绝路径无数据且错误符合合同；不存在全局“权限已授予”默认值。',
  '两虚拟用户的虚拟值隔离；送系统服务的 UID/包名与调用者一致，返回 Guest 的身份符合策略。',
  '关键 Hook 故障可识别并不会伪报 PASS；小米特有参数继续 P2-07。'],
 '方法/参数/结果四列对照表、权限正反例结果', 'C2-T02/C2-T07；VA 日志 653/669')

task('P1-09', '收敛首帧、嵌套 Activity 和终态结果', 'P1', 'P1-04', [1, 2],
 'API35/36 AVD；任务栈 fixture', 'N06 V06 N01 V01', 'C06 C07 C27 E08',
 ['按参考 ActivityRecord 与真实任务栈流程核对嵌套 Activity handoff、结果回传和宿主窗口。',
  '将 requestId/session/generation 与最终可见 Guest 窗口、首帧和持久终态关联，识别旧窗口假通过。',
  '先复现旧 KI-052/053/062/063/065，对已修复状态补证，不重新套用历史 patch。'],
 ['单次启动有唯一终态；嵌套跳转、singleTask、Back、横竖屏和 Activity result 各 3 次通过。',
  '无新帧/黑屏/旧窗口/缺失终态的负例必须 FAIL；仅 resumed 或存在进程不能 PASS。',
  '3 冷+3 热均有真实首帧及对应 requestId；未增大 SLO、未自动重试。'],
 '首帧与任务栈定向报告、遗留 KI 状态依据', 'C1-T01；C4-R03；KI-R03-052/053/062/063/065')

task('P1-10', '验证大 Intent 的受控传输和 Binder 载荷边界', 'P1', 'P1-09', [0.5, 1.5],
 'AVD；大 Bundle/自定义 Parcelable fixture', 'N06 V06', 'C27 C25 E08',
 ['对照 NBB/VA 服务端 ActivityRecord 与代理 Intent 的边界，核查 CAS 当前已有路由 payload 能力。',
  '使用历史 270–309 KB 量级及边界值复现，检查 extras 不被重复附加到 Binder envelope。',
  '验证接近/超过当前 1 MiB 路由上限、错误 classloader、过期/跨代 handle 与清理。'],
 ['可接受载荷 round-trip 内容/Parcelable 等价，无 TransactionTooLargeException 和静默截断。',
  '超限明确拒绝；跨用户/跨代/重复使用受限句柄失败；结束后载荷与 FD 不泄漏。',
  '仅复现确认的缺口产生产品改动，当前已支持的路径只补验收。'],
 '载荷边界测试、旧 KI-057 当前结论', 'KI-R03-057；C4-R03')

task('P1-11', '修复或补证进程 owner、失败清理与短恢复', 'P1', 'P1-05 P1-06 P1-09', [1, 2],
 'API35/36 AVD；少量故障注入', 'N05 V05', 'C24 C25 C26 C28 C32 E08',
 ['从参考 ProcessRecord/client/death 流程核对 retained Binder、PREPARING→READY/FAILED 与 generation fencing。',
  '分别注入 Guest Java crash、native abort、Broker death、prepare 失败、晚到连接；不要把 killProcess 等同 LMK。',
  '用短场景验证恢复与终态持久化；MuMu host LOW_MEMORY 的系统行为留末尾专项，不先跑全天矩阵。'],
 ['每类故障 3 次，旧 PID/代次被回收，下一次显式恢复产生新代次；无永久 PREPARING、旧回调或孤儿 lease。',
  '原始失败与恢复是两条结果；恢复成功不能覆盖第一次失败。',
  '保留普通 64+isolated16 合同；高槽位边界小用例现在做，完整耗尽/压力留 P2-90。'],
 '短恢复矩阵、资源/终态对照、KI 状态更新', 'C1-T07；KI-R03-058/064/066–069')

task('P1-12', '定位调度链路的缺失回调与 PendingIntent 行为', 'P1', 'P1-08 P1-11', [1, 2],
 'API35/36 AVD；Alarm/Job/FGS/Notification fixture', 'N07 N15 N16 V03 V05 V12', 'C29 C30 C31 T02 E05',
 ['复查最新能力矩阵缺失的 ALARM、JOB_CALLBACK、FGS_STOP、CAMPAIGN marker，逐层区分未调度、未回调和采集遗漏。',
  '参考 Service/Job 与系统持有者路由，验证通知点击/取消、PendingIntent creator、更新/取消标志和代次。',
  '短定时、FGS 起停、Job completion/cancel、权限拒绝先在 AVD 完成；熄屏 Doze 与 HyperOS 留 P2-08。'],
 ['同轮 5 次真实 Alarm/Job/FGS 回调及停止确认，不能以 schedule 返回值代替。',
  'Notification/SystemUI 点击命中正确用户和包；注销/换代不再触发旧回调。',
  '缺 marker 的 run 保留 FAIL；修复后对应 case errors 为空，再跑受影响的 S05/S08/S10。'],
 '调度缺失 marker 根因与真实回调记录', 'CAP-SCHEDULING-NOTIFICATION-ALARM-JOB-FGS；C1-T05/C2-T05')

task('P1-13', '补齐分包、动态加载和包升级的短回归', 'P1', 'P1-03 P1-10 P1-11', [1, 2],
 'API34/35/36 AVD；split/插件 fixture', 'N02 N08 V02 V07 V12', 'C01 C02 C08 C20 C23',
 ['根据参考包投影/路径处理和 VA 商业日志 splitNames/只读 DEX 线索核查 CAS 现有路径。',
  '覆盖 base+config+feature splits、资源限定、更新/回滚/清理/重装、签名连续性、动态 dex 与非标准 so 容器。',
  '动态加载失败按 API 合同和原生直装结果复现，不把所有 lib/*.so 都强制当 ELF，也不猜测加固算法。'],
 ['每类包生命周期 3 轮：代码、资源、类加载器、native ABI 与版本一致；双用户数据按操作合同处理。',
  'API34+ 动态代码只读要求的正反例正确；篡改/版本/签名/缺 split 被明确拒绝。',
  '既有 split fixture 基线不退化；只在 ARM 加固 App 上存在的问题有 P2-04/05 的复现坐标。'],
 '包/分包/加载生命周期矩阵', 'C1-T06；KI-R03-051；VA 日志 557/671')

task('P1-14', '验证 WebView 多进程和 GMS 的现有边界', 'P1', 'P1-04 P1-06 P1-08', [1, 2],
 'API35/36 AVD；含 WebView 的 fixture', 'N01 N14 N17 V01 V12', 'C09 C17 C18 C19',
 ['参考在 Application 之前配置 WebView suffix 和提供包路径；验证 CAS 资源、renderer 服务与 profile 生命周期。',
  '执行 HTML/JS、输入、文件选择、页面导航、renderer 退出恢复与两用户 Cookie/localStorage。',
  'GMS 仅验证当前 BASIC_BOUNDARY 的真实能力；无运行时或登录证据时保持 DEFERRED，不生成成功结果。'],
 ['页面 JS 回传指定值，输入/导航可操作；两个用户 Cookie/localStorage 隔离，renderer 恢复不重复 Application。',
  'API36 提供包资产可读；加载失败返回实际阶段，未初始化 WebView 的 App 不被误报成功。',
  '明确区分 Chrome 自身浏览器引擎与嵌入 WebView；完整 GMS 按 P2-10 条件处理。'],
 'WebView 页面/renderer/profile 矩阵；GMS 支持边界', 'C2/C6 WebView；REALAPP-COMPAT-02')

task('P1-15', '验证 Native 路径、JNI、FD 和 16 KB 的通用部分', 'P1', 'P1-13', [1, 2],
 '原生 x86_64 AVD 4 KB；已有 x86_64 16 KB 环境短测', 'N08 N09 V07 V08 V12', 'C20 C21 C22 C23 E05',
 ['比较 CAS PLT/GOT、NBB JNI/IO、VA 旧 libc/linker Hook 的拦截范围，记录 API 和 ABI 前提。',
  '跑真实 file/proc/FD、late dlopen/android_dlopen_ext/JNI 的正常路径；保留 raw SVC 与 custom loader 的边界。',
  '单独复现 NATIVE-ADV-010 done 缺失：未完成用例与已知绕过是不同结果；不直接重分类为 limitation。'],
 ['正常路径 JNI 回值、文件内容、动态库来源和 FD 回收一致；ELF/ZIP 对齐审核通过。',
  '16 KB 短测记录实际 page size；x86_64 或 MuMu 翻译结果不外推为 ARM64。',
  'EXPLICITLY_TRUSTED/BEST_EFFORT 限制保留；debug seccomp POC 和 raw SVC 边界没有伪报生产隔离。'],
 'native 正常路径矩阵、adversarial 缺失 marker 定位、ABI 边界清单', 'C3/C6-T02A/B；CAP-NATIVE-ADVERSARIAL-BOUNDARY')

task('P1-16', '关闭模拟器阶段，产出小米候选版本', 'GATE', 'P1-03 P1-04 P1-05 P1-06 P1-07 P1-08 P1-09 P1-10 P1-11 P1-12 P1-13 P1-14 P1-15', [0.5, 1],
 'API35/36 主 AVD；API32/33/34 受影响用例', 'N01 N03 V01 V03', 'T01 T02 E07 E11',
 ['先完成精简现有 build/unit/source/harness checks，再在 API35/36 跑 S01–S10 和受影响 capability。',
  'API32/33/34 只跑本轮修改涉及的入口；完整重复矩阵放 P2-90。',
  '不能原生运行 ARM 商业包的 AVD 只验等价 fixture；形成剩余“必须真机”列表与固定 APK 哈希。'],
 ['API35/36 核心 20/20、必需 capability 无未解释 FAIL，全部通用复现已闭环。',
  '若标记转交真机，必须说明不可替代的 ARM/OEM 条件、已完成 AVD 子用例和 P2 任务 ID。',
  'API37 环境问题不阻塞当前 API36 小米目标，仍登记为未验证；未执行完整压力/8 小时测试。'],
 'P1-GATE 回执、候选 APK manifest、P2 移交清单', '新阶段一门禁；不等于 VA PRO 等价')

task('P2-01', '建立小米真机与首批商业样本的可复现基线', 'P0', 'P1-16', [0.5, 1],
 '目标小米真机；默认沿用已记录的 25019PNF3C', 'N18 V10 V12', 'C23 T01 E06',
 ['动态发现当前 adb serial，读取 model/fingerprint/API/ABI/page size/WebView provider/系统依赖版本和设置。',
  '冻结 Chrome、Trichrome、夸克及扩展样本的合法来源、版本、base/splits、签名与哈希；先直装或用已安装原版验证同一操作。',
  '安装 P1 候选 Host 与 ARM64 fixture，先跑 S01–S04；系统安装授权、锁屏、网络与产品失败分别记录。'],
 ['新证据对应真实 Xiaomi/ARM64/API 与实际 page size；不复用历史无线 serial。',
  '首批 Chrome/Quark 的原生基准可操作，CAS build/APK 哈希与 P1 候选一致。',
  'ARM64-only 设备不安装 32 位 companion；设备条件变化时先修正 lane，不伪报产品缺陷。'],
 'xiaomi-baseline.json、样本清单、原生基准视频/截图与日志', 'C6-T02C 已有工作复用；进入新商业 App 阶段')

task('P2-02', '完成 Chrome/Trichrome 的 ARM64 启动与浏览闭环', 'P0', 'P2-01', [1, 4],
 '目标小米 ARM64；冻结 Chrome+Trichrome 同版本组合', 'V01 V02 V08 N01 N02', 'C02 C03 C04 C05 C06 C07 C09 C20 C22 E02',
 ['采集最新 NPE 完整 Java/native 栈，识别最后成功阶段；把 AVD 未复现的断点在原生与 CAS 同设备对照。',
  '沿提供包 metadata→sharedLibraryFiles→资源→平台 loader→android_dlopen_ext/RELRO→JNI→renderer 逐段验证。',
  '若出现新缺陷，重新填参考决策单，并回到最小 fixture；不把增加宿主全目录白名单作为通用解法。'],
 ['3 冷+3 热启动有唯一 Application、可见首帧，随后连续 10 分钟可操作。',
  '完成启动页、输入 URL、打开两张测试网页、新建/切换/关闭标签、返回与重新前台；网页 JS/内容校验成功。',
  '当前 NPE 与共享库错误消失，renderer/native 库来源明确；无未捕获异常/ANR/静默崩溃重启。'],
 'CHROME_BASIC_SMOKE=PASS 回执、真实栈闭环、路径与操作证据', 'REALAPP-COMPAT-02 的最高优先续接')

task('P2-03', '完成夸克首帧之后的 Service/Provider 与浏览闭环', 'P0', 'P2-01', [1, 3],
 '目标小米；夸克冻结版本，先沿用 10.15.5.1130/1130 若仍有原包', 'N03 N04 V03 V04 V12', 'C10 C11 C12 C13 C15 E03',
 ['根据真实 Intent/action/component 和 authority 核对三类 owner；重放已出现的 OneTrack/idprovider 与后台服务调用。',
  '记录厂商服务存在/缺失、权限和 returned binder/callback，验证合法缺失路径不会再导致 SDK 线程未捕获异常。',
  '执行网页导航、搜索/输入、标签、返回、前后台和一次文件选择/测试文件下载；每个新问题先回参考源码分析。'],
 ['3 冷+3 热，首帧后至少 10 分钟；操作期间及结束采集窗口都无崩溃/ANR。',
  '对应存在的系统服务完成真实连接；不存在服务和 provider 得到平台允许的缺省结果而不放宽越权访问。',
  'QUARK_FIRST_FRAME 与 QUARK_BASIC_SMOKE 分开为 PASS；不能只引用 3013 ms 首帧。'],
 'QUARK_BASIC_SMOKE=PASS、owner/回调/权限矩阵', 'REALAPP-COMPAT-01/02')

task('P2-04', '用钉钉验证复杂 SDK、WebView 与多进程调用', 'P1', 'P2-02 P2-03', [1, 3],
 '目标小米；仓库旧样本钉钉 7.8.10/1178 或重新冻结的版本', 'N01 N05 N07 V01 V03 V05 V12', 'C06 C08 C12 C13 C17 C25 C29',
 ['沿现有钉钉样本链路核对类加载、Service/Receiver、内置 WebView、文件/媒体入口；版本变化先更新样本基准。',
  '使用测试账号时只执行冻结的非破坏性路径；无需账号的首屏和输入路径先完成。',
  '对每个通用缺陷回补 AVD fixture；不用某版本包名触发的默认成功绕过。'],
 ['3 冷+3 热，10 分钟操作无崩溃/ANR；登录界面可输入、隐私/权限页面可交互、WebView 页面可展示。',
  '测试账号可用时再验登录后页面、测试文件选择和拍摄预览；未执行登录必须保留 NOT_TESTED，不称业务登录通过。',
  '后台组件/退出恢复可追踪；旧 KI-061 Receiver 锁问题若未复现，标为本样本未观察而非旧矩阵全关闭。'],
 'DINGTALK 分功能回执、版本范围和可用级别', 'C4 历史样本；KI-R03-060/061')

task('P2-05', '用红果与番茄验证资源、播放器和页面生命周期', 'P1', 'P2-02 P2-03', [1, 3],
 '目标小米；各自冻结 APK，沿用已有 corpus 获取来源', 'N01 N06 N08 V01 V06 V07 V12', 'C01 C06 C08 C09 C13 C20',
 ['红果与番茄分别建 case，复查历史加包、类加载、Provider readiness 与页面切换问题。',
  '红果执行首页、详情、可公开访问的视频播放/暂停/前后台；番茄执行首页、书页、翻页、返回、重新打开。',
  '原生同样无法访问的内容/账号/网络问题单列，仍完成不依赖该服务的 UI 路径；不替换为随机低版本获得通过。'],
 ['两 App 各 3 冷+3 热、10 分钟；每 App 单独报告全部冻结操作结果。',
  '无未捕获异常、黑屏、重复 Application、资源找不到和不可恢复的播放/页面错误。',
  '未完成的媒体/内容步骤保留受阻状态；两 App 的成功不能互相替代。'],
 'HONGGUO 与 FANQIE 独立 smoke 报告', 'C4-R03/R05；KI-R03-055/059/062')

task('P2-06', '完成相机、音频、定位、文件与媒体的真机功能验证', 'P1', 'P2-02 P2-03', [1, 3],
 '目标小米真实摄像头/麦克风/定位与系统文件选择器', 'N10 N16 V09 V12', 'C13 C15 C16 C31',
 ['先按样本实际调用对照 NBB/VA 对应服务代理，再执行 Camera1/2、AudioRecord/MediaRecorder、Location、MediaStore/SAF。',
  '分别验授权、拒绝、运行中撤销、前后台切换和释放；真实传感器数据与虚拟策略数据按当前配置判断。',
  '将 OEM binder 签名/厂商 manager 差异归入 P2-07；不要用 fixture 的日志替代 App 内预览/录制结果。'],
 ['每种已纳入产品范围的硬件能力至少 3 次成功，文件/图像/音频可读回；授权拒绝后无越权数据。',
  '停止或权限撤销后摄像头/麦克风指示状态及资源按系统合同释放；下一 App 可重新使用。',
  '商业 App 的相关入口可以返回主流程；暂不支持的硬件能力明确标记，不能掩盖为零值成功。'],
 '硬件/媒体权限矩阵、真实输出文件与资源释放证据', 'C2-T03/04/06；C3-T03')

task('P2-07', '只修复实测触发的 HyperOS 身份与组件差异', 'P1', 'P2-01', [1, 3],
 '目标 HyperOS fingerprint；AVD 作为通用回归', 'N03 N10 N11 N12 N13 V03 V09 V12', 'C10 C11 C12 C13 C15 C16',
 ['从 P2-02/03/04/05/06 的首错证据收集 OEM 签名、calling package/UID、Attribution 子类、provider holder 和系统可见性差异。',
  '明确 NBB null-base 小米代理未注入，研究真正有目标对象的代理路径；结合实测平台合同决定最小适配。',
  '能按 API/签名适配的做通用逻辑；只在证据要求时限定 OEM 条件，不捕获全部异常后返回成功。'],
 ['每个补丁有原生/原 CAS/修复 CAS 三列结果、真实被调用的 hook 证据和受影响 App 步骤复测。',
  'UID/权限真实校验仍生效；AVD 对应入口不回退；没有未说明的包名特判。',
  '未出现 OEM-specific defect 时本任务以“研究完成、无需代码修改”关闭，不能为凑任务添加 Hook。'],
 'HyperOS 差异表、最小修复或无需修改的证据', 'C7-T01/02 的 Xiaomi 子集')

task('P2-08', '完成短时前后台、通知点击和进程恢复', 'P1', 'P2-03 P2-06 P2-07', [1, 2],
 '小米默认系统设置；短时锁屏/网络切换/一次重启', 'N05 N07 N15 N16 V03 V05 V12', 'C25 C26 C28 C29 C30 C31 C32',
 ['记录 HyperOS 电池/自启动/通知权限条件，先用默认设置测试；额外设置组合单独记录，不修改后抹去基线。',
  '执行 Home/最近任务返回、通知点击、5 分钟熄屏后恢复、Wi-Fi 切换、Host/Guest 单独死亡和一次设备重启后启动。',
  '按 Job/Alarm/FGS 真实平台约束判断回调；系统不保证的后台任务不承诺常驻或固定时刻触发。'],
 ['每类短恢复至少 3 次（整机重启 1 次），有明确退出原因、新代次、可交互首帧和正确用户。',
  '通知点击回到正确实例，不能串用户/旧代次；无永久 PREPARING、残留录音/相机或持久失联。',
  '默认设置下受限的功能明确写入支持范围；8 小时驻留留 P2-91。'],
 '小米短恢复/通知/后台矩阵与设置快照', 'C1-T05/07；C7 Xiaomi；不复用 MuMu LMK 结论')

task('P2-09', '按硬件与目标需求验收 32 位伴生和跨宽度', 'P2', 'P2-01', [1, 3],
 '实际支持相应 32 位 ABI 的独立设备；当前 ARM64-only 小米仅验拒绝路径', 'N18 V10 V12', 'C23 C24 C33',
 ['按参考主包/扩展包进程分工核对 CAS 32 位 companion 的安装、身份、artifact 与 Binder 通道。',
  '触发前确认目标 APK 仅 32 位且设备支持该 ABI；不从 nativeAbi 名字推断硬件能力。',
  '支持设备上验证跨包 Binder/Intent/FD、退出恢复和签名匹配；当前小米做早期可解释拒绝。'],
 ['当前只报告 arm64-v8a 的小米遇到 32 位独占 APK，明确 UNSUPPORTED_ABI，不尝试装 companion 来制造失败。',
  '若条件触发，32 位代码必须在匹配 ABI 设备实际执行，JNI/回调/跨宽度功能各 3 次；无 pointer-width 截断。',
  '条件不成立时保留 NOT_APPLICABLE，不计为跨宽度 PASS，也不阻塞首批 64 位 App。'],
 'ABI 分流与 companion 条件回执', 'C6-T02D/跨宽度',
 condition='仅当首批目标包含 32 位独占 APK 且有支持该 ABI 的设备；否则保留为条件项')

task('P2-10', '按目标需求完成真实 GMS/账号依赖', 'P2', 'P2-03 P2-07', [2, 5],
 '具有匹配 GMS/GSF/商店运行时的设备或 AVD，最终必须复核目标小米', 'N14 N04 V09 V12', 'C19 C14 C15 C31',
 ['目标流程确实依赖 GMS 时再启用；先研究参考 Account/GMS 包集合和真实服务依赖，冻结版本与安装形态。',
  '明确选择支持的调用范围（例如服务可用性检查、测试账号登录、目标推送路径），不把全部 GMS 作为单一通过项。',
  '对返回值、签名/包身份、后台连接和实际服务响应建最小用例；当前 BASIC_BOUNDARY 不能当成 GMS 运行时。'],
 ['所选 GMS API 有真实服务响应；测试账号登录成功时才能声明登录支持。',
  '没有运行时/网络/账号时如实受阻，不能构造 token 或 Play PackageInfo 替代结果。',
  '若首批业务不依赖 GMS，此项延期且在功能范围中说明，不阻塞国内浏览器基础路径。'],
 'GMS 命名用例与范围回执', 'VA PRO 谷歌能力差距',
 condition='仅在首批 App 的必需业务流程依赖 GMS 时触发')

task('P2-11', '按声明范围补 ARM64 16 KB 与 API37 环境', 'P2', 'P2-01 P1-15', [1, 3],
 '真实 ARM64 16 KB 设备/受支持环境；独立稳定 API37 AVD', 'N18 V08 V10 V12', 'C20 C22 C23 E06 E07',
 ['把 ABI、page size、API 分成独立坐标；不要从 API35/36/37 版本推断 page size。',
  '仅在本次支持声明要求时补环境；API37 先解决记录中的 emulator graphics 环境，环境未完成前不改 CAS 凑通过。',
  '分别验证 native ELF/zip、加载/RELRO、页面/媒体和被新增 API 影响的服务；不要刷目标小米 ROM 来满足矩阵。'],
 ['每个新增支持坐标都有实际 metadata、核心用例和目标调用路径记录。',
  'x86_64 16 KB PASS 不外推 ARM64 16 KB；VA PRO README 的 Android17 声明不是 CAS 证据。',
  '当前 API36/4KB 小米需求不要求该组合时，保留 DEFERRED，不阻塞该设备可用结论。'],
 '额外平台组合支持表或明确延期记录', 'C6-T01F/G、C6-T02B 的剩余组合',
 condition='只在发布范围要求 ARM64 16 KB 或 API37 时触发')

task('P2-12', '冻结可用版本与短验收，准备最终长测', 'GATE', 'P2-02 P2-03 P2-04 P2-05 P2-06 P2-07 P2-08', [0.5, 1],
 '目标小米；相同 APK 哈希；debug 与拟交付构建', 'N01 N15 V01 V12', 'T01 T02 C16 C23 E08',
 ['固定首批与扩展样本、通过级别、设备与必需功能；逐项关闭前面的功能缺陷，条件任务决定是否触发并登记。',
  '拟交付构建跑每 App 1 冷+1 热和关键路径，检查 native 打包、反射/回调类保留；不以 debug PASS 代表所有构建。',
  '预登记长测脚本、固定 case 坐标、性能门槛、缺失记录处理和恢复规则；不在结果出来后调整通过线。'],
 ['首批 Chrome/Quark 均 BASIC_SMOKE PASS，扩展样本逐个标注实际通过功能；选为必需的流程不得留 FAIL/NOT_TESTED。',
  '无目标范围内 P0/P1 开放缺陷；候选版本和 APK 哈希锁定，测试矩阵/恢复规则审定并可自动校验。',
  '已触发的 P2-09/10/11 同样必须完成；未触发者有明确范围说明。此处仍不宣称长稳已通过。'],
 '候选版本清单、支持范围、短验收回执、长测配置', '首批小米可用门禁；C6-T03/C7 子集')

task('P2-90', '末尾执行大批量回归、压力与同设备比较', 'TAIL', 'P2-12', [0.5, 1.5],
 '小米 + 已支持 AVD；预计数小时，按任务坐标断点续接', 'N05 N06 V05 V06 V12', 'T01 T02 C24 C25 C26 C27 E08 E09',
 ['功能开发完成后才跑大矩阵：默认 5 App×2虚拟用户×冷/热各50次×2轮，合计2000次启动；只对已冻结且必需的样本计算。',
  '完成 API32–36 完整回归、C4 旧待关门矩阵映射、槽位高位/耗尽及 Provider/FD 压力；顺序执行，避免环境互相挤压。',
  '同设备直装作主要基准；有可运行且版本已冻结的 NBB/授权 VA PRO 时再加 A/B。旧 VA2017 不作为 API36 通过基准。',
  '统计原始失败率、恢复率、冷/热 p50/p95、PSS、FD 与 Binder；phase 超时终止本次子进程树，再按未执行坐标续跑。'],
 ['必需矩阵所有坐标有唯一记录，无缺失、覆盖原始失败或事后补造 PASS；支持范围内目标用例全通过。',
  '系统杀进程要保留原因和独立恢复结果；恢复不改原始成功率；明确产品失败/环境受阻，环境受阻不计 PASS。',
  '默认性能目标：冷热首帧 p95 ≤ max(直装同场景p95×3, 5秒)，且不放宽旧任务已冻结的更严格 SLO；未达标则优化后重测受影响项。',
  '负载卸除后租约归零，FD/进程数回到允许基线；没有可用 VA PRO 时只声明本机矩阵通过，不声明等价。'],
 '完整回归/压力/A-B 回执与指标表；C4 历史欠账逐项关闭或保留原因',
 'C4-R05；完整 C1/C2/C4 回归；C6/C7 末尾验收', long_run=True)

task('P2-91', '最后执行 8 小时稳定性测试并交付总回执', 'TAIL_LAST', 'P2-90', [0.5, 1],
 '已锁定候选 APK 的小米真机；8 小时净执行，另留准备/归档时间', 'N05 V05 V12', 'T01 T02 C25 C26 E08 E09',
 ['所有功能开发与前置矩阵完成后，按固定场景混合浏览、视频/阅读、前后台、通知回到 App 和空闲；覆盖已声明支持的 App。',
  '稳定负载下每分钟采集时间戳、PID/代次、进程存活、CPU/PSS、FD、热状态和错误；每 15 分钟完成一次可验证交互检查点。',
  '中断、掉线、温控/网络环境阻断与实际执行时长分开，不能以墙钟过了 8 小时当 PASS；补跑规则在 P2-12 冻结。',
  '出现产品缺陷立即保留首错，修复后从相应短回归重新进入，最终候选版本要有完整同版本 8 小时证据；归档并输出最终支持范围。'],
 ['净运行≥8小时；每15分钟检查点全部完成，分钟级采样覆盖率≥99%，缺口可解释。',
  '无非预期 Java/native crash、ANR、不可恢复黑屏/会话失联、数据串用户或损坏；不依靠自动重启掩盖故障。',
  '按同负载空闲 checkpoint 对比：末小时 PSS p95 ≤ max(首个稳定小时PSS p95×1.2, 首个稳定小时+50MiB)；释放后受管租约归零、FD 较基线增加≤5且无持续增长。',
  '最终回执同时列出商业操作通过范围、原始失败/恢复、性能/资源曲线、未覆盖条件项和准确 APK/源码哈希；无证据的 VA PRO 能力不写等价。'],
 'SOAK_8H 回执、最终支持矩阵与全部证据索引；此任务为最后执行项',
 'KI-R03-032 长稳债务；按本次用户要求恢复到最终尾部', long_run=True)


def expand_refs(text):
    def replace(match):
        sid = match.group(1)
        assert sid in SOURCES, sid
        return refs([sid])
    return re.sub(r'\{\{([A-Z]\d+)\}\}', replace, text)


analysis = expand_refs((Path(__file__).parent/'analysis_template.md').read_text(encoding='utf-8'))
write('01_源码差异分析.md', analysis)

phase_totals = {}
for phase in (1, 2):
    required = [t for t in TASKS if t['phase'] == phase and not t['condition'] and not t['long_run']]
    phase_totals[phase] = [sum(t['effort_person_days'][i] for t in required) for i in (0, 1)]

intro = f'''# CAS 小米商业 App：两阶段执行任务书

制定日期：2026-09-08。源码基线：`{HEAD}`。状态：**计划已制定，以下任务尚未执行**。

任务总数：{len(TASKS)}。阶段一 16 项；阶段二 14 项（其中 3 项按实际需求触发）；最后两项是大规模回归和 8 小时稳定性测试。

## 执行目标与默认样本

首批必达为 **Chrome + 夸克在目标小米上启动、可交互并完成 BASIC_SMOKE**。仓库已有钉钉、红果、番茄作为扩展回归样本，版本、所需账号和本轮必需功能在 P1-01/P2-01 固定；若指定业务流程必须登录，登录不能用首屏成功替代。版本漂移和样本缺失要单列，不能换一个“更容易过”的包。

默认设备沿用历史 Xiaomi 25019PNF3C / Android16 API36 / ARM64-only / 4KB 的定位线索。开始执行必须重新读设备信息，不能沿用历史无线 serial。AVD 是通用功能开发环境；MuMu 皮肤不等于 HyperOS，ABI 翻译也不等于原生 ARM64。

## 执行顺序

1. 从 P1-01 开始校正当前 HEAD 与验收基线，随后 P1-02 建参考决策单。之后优先 P1-03/04 的共享库与启动、P1-05/06/07 的 Service/Provider/PMS，再补通用生命周期与短回归。
2. P1-16 完成后进入小米阶段；先 P2-01，再让 Chrome 和夸克的首帧后流程闭环。遇到 OEM 参数问题立即转 P2-07，修完返回原任务；不要求等其他 App 全通过才处理 OEM。
3. P2-09/10/11 为条件项：未触发不占主路径，触发后必须在 P2-12 前完成。这个条件不能被用来绕过本轮已经冻结为必需的功能。
4. 功能修复、短真机验收和候选版本冻结之后，才执行 P2-90 大规模回归；P2-91 的 **8 小时净运行稳定性测试是最后一项**，总回执归档包含在该任务内。

编号表示默认优先顺序，依赖表示开工条件。每次只改一个已定位的行为问题；可独立的源码研究/fixture 准备无需等真机。阶段一中确实无法在 AVD 重现的 ARM/OEM 子项，需写明阻碍和 P2 接收任务；不能假装通过，也不能停止所有独立通用工作等待设备。

## 每项修改统一前置与完成定义

- **先研究再改：** 每个产品修改都先完成 CAS 失败路径与 NBB/VA 参考路径、平台合同/独立复现、采用/不采用理由。参考只有 README 功能声明时，明确实现未知，补证后再设计。
- **证据来源分开：** 当前源码、历史留存运行、当前版本实测、VA PRO 商业声明分别记录。现有实现和仍缺验收不可合并为“没实现”。
- **返回语义真实：** 允许、不存在、越权、环境不可用、适配器失败各自按合同处理；不得一律吞异常/伪造权限/伪造回调/默认成功，也不得为通过临时扩大权限范围。
- **首错保留：** 正常用例 `attempt=1`，关闭诊断重试；故障恢复独立记账。只有经过预登记的恢复场景才能执行恢复，不能把恢复成功覆盖最初失败。
- **每次验收绑定：** CAS commit、dirty diff 或 clean 状态、APK SHA256、样本 base/splits/签名/版本、device fingerprint/API/ABI/page size、case/request/session/generation、日志/截图/实际回调、真实业务后置条件。
- **测试按改动范围：** 先失败 fixture、修复、该 fixture + 受影响主干；代码未变且无新疑点不重复跑大矩阵。全量/耗尽/长压/长稳统一在最后两个任务。
- **关闭任务：** 决策单、实现（或有证据说明无需修改）、该任务全部必需验收、证据索引与旧 KI 映射齐备。任何被选为必需的 App/流程仍 FAIL、受阻或 NOT_TESTED 时都不能关闭相应门禁。

本任务书是本次用户要求的新执行排序。旧 `docs/plans` 账本与 KI 的正式迁移安排在 P1-01，本轮没有修改它们，也没有执行旧任务书中的提交/推送动作。原来 8 小时测试被移除的历史保留；本次按用户要求恢复为尾部 P2-91。

## 工期与资源预算

估算以熟悉 CAS 的 1 名开发者、1 人日=8 小时为单位，含针对性修复和短回归，不是工期承诺。阶段一必需工作约 **{phase_totals[1][0]:g}–{phase_totals[1][1]:g} 人日**；阶段二必需功能工作约 **{phase_totals[2][0]:g}–{phase_totals[2][1]:g} 人日**。复现已经通过的项目只补证，取下限；发现新的 ARM/OEM/商业 SDK 根因则重估该任务。

3 个条件项各有独立估算，不预先加入本机主路径。P2-90 的执行墙钟可能达到数小时至一天以上，取决于冻结样本与每例操作时长；P2-91 另需完整 8 小时净运行。长测配置/预算提前准备，长测本身最后执行。这里提出的次数与性能/资源阈值是新验收建议，不是历史通过事实；执行前冻结，不允许看结果后改线。

## 任务总览

| ID | 任务 | 优先级 | 依赖 | 估算人日 | 状态 |
|---|---|---|---|---|---|
'''

for t in TASKS:
    intro += f'| {t["id"]} | {t["title"]} | {t["priority"]} | {", ".join(t["dependencies"]) or "无"} | {t["effort_person_days"][0]:g}–{t["effort_person_days"][1]:g} | {t["status"]} |\n'

plan = intro
for phase in (1, 2):
    plan += f'\n## 阶段{phase}：' + ('模拟器 / AVD 优先开发' if phase == 1 else '小米真机完成与最终长测') + '\n'
    for t in [v for v in TASKS if v['phase'] == phase]:
        plan += f'\n### {t["id"]} · {t["title"]}\n\n'
        plan += f'**状态：** {t["status"]}　**优先级：** {t["priority"]}　**预计：** {t["effort_person_days"][0]:g}–{t["effort_person_days"][1]:g} 人日\n\n'
        plan += f'**环境：** {t["environment"]}\n\n**依赖：** {", ".join(t["dependencies"]) or "无"}\n\n'
        if t['condition']:
            plan += f'**触发条件：** {t["condition"]}\n\n'
        plan += '**先读的参考源码：** ' + refs(t['reference_ids']) + '\n\n'
        for sid in t['reference_ids']:
            plan += f'- {sid}：{SOURCES[sid]["note"]}\n'
        plan += '\n**CAS/证据定位：** ' + refs(t['cas_ids']) + '\n\n**执行内容：**\n\n'
        plan += ''.join(f'{i}. {v}\n' for i, v in enumerate(t['work'], 1))
        plan += '\n**验收标准（必需项全部满足）：**\n\n'
        plan += ''.join(f'- [ ] {v}\n' for v in t['acceptance'])
        plan += f'\n**产物：** {t["deliverable"]}\n\n**旧任务/问题映射：** {t["legacy_mapping"] or "新任务"}\n'

plan += '''
## 现存命令入口（本轮只核对，不执行）

在 `D:/github/controlled-android-sandbox` 的 PowerShell 中执行。下面使用已探测到的 bundled Python，避免本机 `python` launcher 当前访问路径失败。ADB 与 SDK 路径沿用项目配置；serial 必须在每次开工时动态核对。

```powershell
$PlanPython = 'C:\\Users\\wangding\\.cache\\codex-runtimes\\codex-primary-runtime\\dependencies\\python\\python.exe'
.\\gradlew.bat projects
.\\gradlew.bat assembleDebug
.\\gradlew.bat test
& $PlanPython scripts/check-architecture.py
& $PlanPython scripts/check-contracts.py
& $PlanPython tools/static_android_compile.py
& $PlanPython -m unittest tools.verification.test_harness
```

定向 AVD 示例（将 `<动态核对的serial>` 换成已验证设备，不要把占位符原样执行）：

```powershell
& $PlanPython tools/verification/run_rd_smoke.py --api36 --serial '<动态核对的serial>' --run-id P1-targeted --no-diagnostic-retry --only-case S03-cold-launch-first-frame
```

`--only-case` 之前必须满足 Host/fixture 已安装、Guest 已导入的前置条件；第一次可以执行 S01–S04 或完整核心套件。设备安装和运行会改变测试环境，应在正式开始执行本计划后进行。

小米核心示例：

```powershell
& $PlanPython tools/verification/run_rd_smoke.py --arm64-physical --serial '<动态核对的serial>' --run-id P2-xiaomi-core --expected-page-size 4096 --no-diagnostic-retry
& $PlanPython tools/verification/run_api33_capabilities.py --arm64-physical --serial '<动态核对的serial>' --run-id P2-xiaomi-capabilities --expected-page-size 4096
```

若本轮设备页大小不是 4096，应按实际 lane 修改参数，不能为通过伪造 metadata。`--skip-build` 只在同一源码/APK 的构建证据已经存在并另附回执时使用。新增共享库/商业 smoke 用例需要在对应 P1 任务中实现；上面的现存命令不自动代表 Chrome/Quark 业务 smoke。

## 每个修复任务的回执模板

```text
TASK_ID / STATUS / SCOPE
CAS_HEAD / DIRTY_DIFF_HASH / HOST_APK_SHA256 / FIXTURE_APK_SHA256
REFERENCE_PROJECT / REFERENCE_COMMIT / FILE:LINE / METHOD
REFERENCE_BEHAVIOR / CAS_BEFORE / CHOSEN_CONTRACT / REJECTED_ALTERNATIVES
REPRO_CASE / NATIVE_BASELINE / EXPECTED / ACTUAL_BEFORE / ACTUAL_AFTER
DEVICE_FINGERPRINT / API / ABI / PAGE_SIZE / SETTINGS
APP_PACKAGE / VERSION / BASE_SPLITS_SHA256 / DEPENDENCY_PROVIDER_VERSIONS
REQUEST_ID / SESSION_ID / GENERATION / FIRST_FAILURE
REQUIRED_CASES_PASS_FAIL / RAW_FAILURES / RECOVERY_RESULTS / NOT_TESTED
REGRESSIONS / EVIDENCE_PATHS / KI_MAPPING / NEXT_TASK
```

新任务结束只更新真实达到的级别：SOURCE_ANALYZED → FIXTURE_PASS → DEVICE_CORE_PASS → APP_BASIC_SMOKE_PASS → NAMED_BUSINESS_FLOW_PASS → FINAL_MATRIX_PASS → SOAK_8H_PASS。VA_PRO_EQUIVALENT 保持 NOT_PROVEN，除非另有同设备、同样本、同业务范围的实际对照证据。
'''
write('02_两阶段执行任务书.md', plan)

source_index = f'''# 源码与证据定位索引

日期：2026-09-08。CAS：`{HEAD}`。共 {len(SOURCES)} 个定位条目（同文件不同入口分别计数）。所有路径与行号均由本地文件实际解析；SHA256 对应分析时的原始文件字节。

这是研究定位，不是产品实现的复制清单。`C`=CAS 当前源码；`N`=用户指定 NBB；`V`=用户指定 VA/商业 README；`T`=现存测试工具；`E`=留存报告/原始证据。

| ID | 路径与行号 | 本次观察 |
|---|---|---|
'''
for sid, s in SOURCES.items():
    source_index += f'| {sid} | [{s["path"]}:{s["line"]}]({s["path"]}:{s["line"]}) | {s["note"]} |\n'
source_index += '\n## VA PRO 商业日志具体条目行号\n\n'
changelog_ids = {713,711,710,709,708,707,706,705,704,700,686,677,676,648,637,600,572,555,671,557,669,653,522,482,627,596,510,566,449,448,539,296,185,182,50,443,442,441,440,396,388,386,283,200,69,59}
changelog = []
for i, line in enumerate((VA/'README.md').read_text(encoding='utf-8-sig').splitlines(), 1):
    m = re.match(r'^\s*(\d+)、', line)
    if m and int(m.group(1)) in changelog_ids:
        item = dict(number=int(m.group(1)), path=(VA/'README.md').as_posix(), line=i)
        changelog.append(item)
        source_index += f'- [条目 {item["number"]}（README:{i}）]({item["path"]}:{i})\n'
assert {x['number'] for x in changelog} == changelog_ids, changelog_ids - {x['number'] for x in changelog}
source_index += '\n## 文件哈希\n\n哈希只用于绑定本地研究输入，不代表相应源码/运行结果已经通过当前产品验收。\n\n'
for sid, s in SOURCES.items():
    source_index += f'- {sid}：`{s["sha256"]}`\n'
write('04_源码证据索引.md', source_index)

data = dict(schema_version=1, generated_at='2026-09-08', cas_head=HEAD,
    scope='两阶段计划；未执行开发或设备测试',
    baselines=dict(cas=str(ROOT), nbb_commit='89b59836c66f173756a4ae258cf379a957649820',
                   va_commit='802a82c2b9c15a1990e75eee1e9fe07168854772',
                   reference_normalization='CRLF/LF normalized: NBB no differences; VA README.md only'),
    tasks=TASKS, sources=SOURCES, va_pro_changelog=changelog,
    mandatory_effort_person_days=phase_totals,
    long_tests_order=['P2-90', 'P2-91'])
write('03_任务与证据索引.json', json.dumps(data, ensure_ascii=False, indent=2))


def e(value):
    return html.escape(str(value), quote=True)


def list_html(values):
    return '<ol>' + ''.join('<li>'+e(v)+'</li>' for v in values) + '</ol>'


def refs_html(ids):
    result = []
    for sid in ids:
        s = SOURCES[sid]
        result.append(f'<li><a href="{e(Path(s["path"]).as_uri())}" title="{e(s["path"])}">{sid} · {e(Path(s["path"]).name)}:{s["line"]}</a> — {e(s["note"])}</li>')
    return '<ul>' + ''.join(result) + '</ul>'


cards = []
for t in TASKS:
    search = ' '.join([t['id'],t['title'],t['environment'],t['legacy_mapping']]+t['work']+t['acceptance']).lower()
    condition = f'<p class="condition"><b>触发条件：</b>{e(t["condition"])}</p>' if t['condition'] else ''
    cards.append(f'''<details class="task" data-phase="{t['phase']}" data-search="{e(search)}" data-long="{str(t['long_run']).lower()}" id="{t['id']}">
<summary><span class="tid">{t['id']}</span><span class="taskname">{e(t['title'])}</span><span class="pill">{e(t['priority'])}</span><span class="state">{e(t['status'])}</span></summary>
<div class="detail"><div class="metadata"><span><b>环境</b> {e(t['environment'])}</span><span><b>依赖</b> {e(', '.join(t['dependencies']) or '无')}</span><span><b>估算</b> {t['effort_person_days'][0]:g}–{t['effort_person_days'][1]:g} 人日</span></div>{condition}
<h3>执行内容</h3>{list_html(t['work'])}<h3>验收标准 · 必需项全部满足</h3>{list_html(t['acceptance'])}
<h3>先读的参考源码与方法</h3>{refs_html(t['reference_ids'])}<h3>CAS 与证据定位</h3>{refs_html(t['cas_ids'])}
<p><b>产物：</b>{e(t['deliverable'])}</p><p><b>旧任务/问题映射：</b>{e(t['legacy_mapping'])}</p></div></details>''')

page = '''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>CAS 小米落地执行计划 · 2026-09-08</title>
<style>
:root{--ink:#142a38;--muted:#526772;--line:#dce4e7;--teal:#006e63;--paper:#f3f5f4}*{box-sizing:border-box}body{margin:0;color:var(--ink);background:var(--paper);font-family:"Microsoft YaHei UI","Microsoft YaHei",system-ui,sans-serif;font-size:15px;line-height:1.8}header{background:#112c3b;color:white;padding:44px max(24px,calc((100vw - 1180px)/2)) 34px}header .eyebrow{color:#8ed8c7;font-size:13px;letter-spacing:.08em}h1{font-size:34px;line-height:1.45;margin:12px 0}header p{color:#c7d9df;max-width:920px;margin:0}main{max-width:1228px;margin:auto;padding:24px}a{color:var(--teal);text-underline-offset:3px}.links{display:flex;flex-wrap:wrap;gap:10px 24px;padding:14px 0}.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin:16px 0}.stat{background:#fff;border:1px solid var(--line);border-radius:10px;padding:16px 20px}.stat strong{display:block;font-size:27px;color:var(--teal)}.stat span{color:var(--muted)}.note{padding:18px 22px;background:#e4f1ed;border-left:4px solid #208b73;border-radius:5px;margin:18px 0}.warning{background:#fff3db;border-color:#c88b28}.toolbar{display:flex;flex-wrap:wrap;gap:10px;padding:15px;background:#fff;border:1px solid var(--line);border-radius:9px;position:sticky;top:0;z-index:2;margin:18px 0}input,select,button{font:inherit;border:1px solid #bfccd2;background:#fff;border-radius:6px;padding:7px 11px;color:var(--ink)}input{flex:1;min-width:210px}button{cursor:pointer}button:hover{background:#e9f4f0}.task{background:white;border:1px solid var(--line);border-radius:9px;margin:10px 0;overflow:hidden}.task[open]{border-color:#70b4a4}summary{cursor:pointer;display:flex;align-items:center;gap:14px;padding:17px 20px;list-style:none}summary::before{content:'+';color:var(--teal);font-size:21px;flex-shrink:0}details[open]>summary::before{content:'−'}summary::-webkit-details-marker{display:none}.tid{font-family:Consolas,monospace;color:var(--teal);font-weight:bold;white-space:nowrap}.taskname{flex:1;font-weight:600}.pill{font-size:12px;border-radius:4px;background:#edf2f4;color:#38515f;padding:2px 8px}.state{font-size:12px;color:#697a84;white-space:nowrap}.detail{padding:2px 28px 24px 53px;border-top:1px solid #eef1f2}.metadata{display:grid;gap:4px;padding:14px 0;color:var(--muted);font-size:14px}h3{font-size:16px;margin:20px 0 7px}ol,ul{padding-left:24px;margin:6px 0}li{margin:5px 0;overflow-wrap:anywhere}.condition{background:#fff2d6;padding:12px;border-radius:5px}.small{font-size:13px;color:var(--muted)}footer{margin:30px 0;color:var(--muted);font-size:13px}#count{font-size:13px;color:var(--muted)}.task[hidden]{display:none}@media(max-width:700px){h1{font-size:25px}.stats{grid-template-columns:1fr 1fr}main{padding:14px}.state{display:none}summary{padding:14px;gap:8px}.detail{padding:0 18px 18px}.pill{font-size:10px}}@media print{header{padding:20px}body{background:white}.toolbar,.links{display:none}.task{break-inside:avoid}.detail{display:block}main{max-width:none}.stats{grid-template-columns:repeat(4,1fr)}}
</style></head><body><header><div class="eyebrow">SOURCE REVIEW → AVD → XIAOMI</div><h1>CAS 商业 App 落地执行计划</h1><p>源码分析日期 2026-09-08 · 基线 40f629d0 · 两阶段、逐项源码依据与验收标准。所有任务仍为计划状态；本次没有改产品实现或执行设备测试。</p></header><main>
<nav class="links"><a href="01_源码差异分析.md">源码差异分析</a><a href="02_两阶段执行任务书.md">完整 Markdown 任务书</a><a href="04_源码证据索引.md">源码定位与哈希</a><a href="03_任务与证据索引.json">机器可读任务与证据</a></nav>
<section class="stats"><div class="stat"><strong>30</strong><span>任务 · 逐项可验收</span></div><div class="stat"><strong>16 + 14</strong><span>AVD 阶段 / 小米阶段</span></div><div class="stat"><strong>3</strong><span>按目标需求触发的条件项</span></div><div class="stat"><strong>8 小时</strong><span>稳定性测试安排在最后</span></div></section>
<div class="note"><b>先完成两条商业链路：</b>Chrome 的当前启动 NPE；夸克的首帧后系统 Service/Provider 与可操作 smoke。当前 CAS 已有共享库投影、系统组件分流和真机基础证据，应从当前 HEAD 开始复现。</div>
<div class="note warning"><b>每次修改先读参考源码。</b>记录 NBB/VA 的执行方法、CAS 差异、平台合同与最小复现。VA PRO README 只代表公开能力线索；默认成功、空 Hook 和文件数量均不能作为兼容通过证据。</div>
<p class="small">阶段一先修可在 AVD 验证的通用问题；阶段二完成 Xiaomi/ARM64/HyperOS 和真实商业 App。32 位、GMS、ARM64 16KB/API37 依需求触发。展开任务查看具体操作、依赖、源码和验收。</p>
<div class="toolbar"><select id="phase" aria-label="筛选阶段"><option value="all">全部任务</option><option value="1">阶段一 · AVD 优先</option><option value="2">阶段二 · 小米完成</option><option value="tail">仅最终长测</option></select><input id="search" placeholder="搜索任务 / App / 组件 / 旧 KI" aria-label="搜索任务"><button id="expand">展开筛选结果</button><button id="collapse">收起全部</button></div><div id="count"></div>
''' + '\n'.join(cards) + '''
<footer>此看板为静态计划，可离线筛选。实际执行状态以逐任务回执与正式迁移后的项目账本为准。源码链接指向本机文件，行号显示在链接文字中。最后执行顺序：P2-90 大矩阵 → P2-91 8小时净运行并归档。</footer></main>
<script>
const tasks=[...document.querySelectorAll('.task')],phase=document.querySelector('#phase'),search=document.querySelector('#search');function filter(){const p=phase.value,q=search.value.toLowerCase().trim();let n=0;for(const t of tasks){const show=(p==='all'||t.dataset.phase===p||(p==='tail'&&t.dataset.long==='true'))&&(!q||t.dataset.search.includes(q));t.hidden=!show;if(show)n++}document.querySelector('#count').textContent='显示 '+n+' / '+tasks.length+' 项任务'}phase.addEventListener('change',filter);search.addEventListener('input',filter);document.querySelector('#expand').addEventListener('click',()=>tasks.filter(t=>!t.hidden).forEach(t=>t.open=true));document.querySelector('#collapse').addEventListener('click',()=>tasks.forEach(t=>t.open=false));filter();document.querySelector('#P1-01').open=true;
</script></body></html>'''
write('00_执行计划.html', page)

# Validate the actual deliverables rather than treating successful generation as proof.
ids = [t['id'] for t in TASKS]
assert len(ids) == len(set(ids)) == 30
for i, t in enumerate(TASKS):
    assert all(dep in ids[:i] for dep in t['dependencies']), (t['id'],t['dependencies'])
    assert len(t['work']) >= 3 and len(t['acceptance']) >= 3
    assert t['reference_ids'] and any(s[0] in 'NV' for s in t['reference_ids'])
    assert all(s in SOURCES for s in t['reference_ids']+t['cas_ids'])
    assert t['deliverable'] and t['environment'] and t['effort_person_days']
assert [t['id'] for t in TASKS if t['long_run']] == ['P2-90','P2-91']
assert ids[-2:] == ['P2-90','P2-91']
assert Counter(t['phase'] for t in TASKS) == {1:16,2:14}
assert len([t for t in TASKS if t['condition']]) == 3
assert page.count('<details class="task"') == 30
assert '{{' not in analysis
for s in SOURCES.values():
    p = Path(s['path'])
    assert hashlib.sha256(p.read_bytes()).hexdigest() == s['sha256'], s['id']
    assert s['anchor'] in p.read_text(encoding='utf-8-sig').splitlines()[s['line']-1],s['id']
output_files = [p for p in OUT.iterdir() if p.is_file() and p.name != '05_交付校验.json']
receipt = dict(artifact_validation='PASS', task_count=30, phases={'1':16,'2':14}, conditional_tasks=3,
    source_anchors_verified=len(SOURCES), changelog_entries_verified=len(changelog),
    dependencies='unique, resolved and acyclic; all prerequisites earlier in list',
    long_tasks_last=['P2-90','P2-91'], product_tests_executed=False, product_code_modified=False,
    files=[dict(name=p.name,bytes=p.stat().st_size,sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in sorted(output_files)])
write('05_交付校验.json', json.dumps(receipt,ensure_ascii=False,indent=2))
print(json.dumps(dict(output=str(OUT),tasks=len(TASKS),source_anchors=len(SOURCES),changelog_entries=len(changelog),
    phase_effort=phase_totals,artifact_validation='PASS',file_sizes={p.name:p.stat().st_size for p in OUT.iterdir() if p.is_file()}), ensure_ascii=False))
