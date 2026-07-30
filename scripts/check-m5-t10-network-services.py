#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return path.read_text(encoding="utf-8-sig")


def require(rel: str, *tokens: str) -> str:
    value = text(rel)
    for token in tokens:
        if token not in value:
            errors.append(f"{rel} missing: {token}")
    return value


require("docs/plans/M5_T10_NETWORK_SERVICES.md", "ConnectivityManager", "DnsResolver",
        "VpnManager", "BLOCKED", "STATIC", "HOST", "documentation-reserved")
require("docs/M5_T10_DEVELOPMENT_REPORT.md", "Source status: PASS",
        "Production status: PARTIAL", "Device evidence: 0", "read-only VA/NBB")
require("docs/comparisons/M5_T10_VA_NBB_COMPARISON.md", "VirtualApp", "NewBlackbox",
        "Device evidence remains 0", "IDnsResolverProxy")
require("README.md", "## M5-T10 Connectivity, DNS, Proxy/VPN and Java network-services source baseline")
require("docs/ROADMAP.md", "## M5-T10 Connectivity, DNS, Proxy/VPN and Java network-services baseline")

contracts = (
    "VirtualNetworkSnapshot", "VirtualDnsRecordSnapshot", "VirtualDnsProfileSnapshot",
    "VirtualProxyProfileSnapshot", "VirtualVpnProfileSnapshot",
    "VirtualConnectivityProfileSnapshot", "VirtualNetworkServiceProfileSnapshot",
)
for name in contracts:
    require(f"sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/{name}.aidl",
            f"parcelable {name};")
    require(f"sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/{name}.java",
            "Parcelable")

require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualNetworkSnapshot.java",
        "WIFI", "CELLULAR", "ETHERNET", "VPN", "networkId", "dnsServers", "routes")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualDnsProfileSnapshot.java",
        "PRIVATE_DNS_OFF", "PRIVATE_DNS_AUTOMATIC", "PRIVATE_DNS_HOSTNAME",
        "allowRawQueries", "record(String hostname")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualProxyProfileSnapshot.java",
        "NONE", "STATIC", "PAC", "allowGuestOverride")
require("sandbox-contract/src/main/java/com/warden/controlledsandbox/contract/VirtualVpnProfileSnapshot.java",
        "DISCONNECTED", "CONNECTING", "CONNECTED", "maximumSessions", "allowEstablish")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IPackageManagementSession.aidl",
        "getNetworkServiceProfile", "setNetworkServiceProfile", "resetNetworkServiceProfile")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceSession.aidl",
        "getNetworkServiceProfile")
require("sandbox-contract/src/main/aidl/com/warden/controlledsandbox/contract/IVirtualSystemServiceObserver.aidl",
        "onNetworkServiceProfileChanged")

require("app/src/main/java/com/warden/controlledsandbox/VirtualNetworkServiceDefaults.java",
        "192.0.2.", "MODE_STATIC", "VirtualNetworkServiceProfileSnapshot")
require("app/src/main/java/com/warden/controlledsandbox/VirtualNetworkServiceStore.java",
        "NETWORK_PROFILE_VERSION_CONFLICT", "VirtualNetworkServiceStorePersistence")
require("app/src/main/java/com/warden/controlledsandbox/VirtualNetworkServiceStorePersistence.java",
        "CRC32", ".corrupt", "ATOMIC_MOVE")
require("app/src/main/java/com/warden/controlledsandbox/PackageManagementService.java",
        "getNetworkServiceProfile", "setNetworkServiceProfile", "resetNetworkServiceProfile",
        "notifyNetworkProfileChanged")
require("app/src/main/java/com/warden/controlledsandbox/VirtualSystemServiceStore.java",
        "onNetworkServiceProfileChanged")

require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/identity/GuestNetworkState.java",
        "VIRTUAL_NETWORK_CALLBACK_LIMIT_REACHED", "VIRTUAL_VPN_SESSION_LIMIT_REACHED",
        "releaseCallback", "releaseVpnSession", "clearVpnSessions")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/NetworkServiceInvocationInterceptor.java",
        'case "connectivity"', 'case "dnsresolver"', 'case "vpn"',
        "getnetworkcapabilities", "getlinkproperties", "unregisternetworkcallback",
        "VIRTUAL_CONNECTIVITY_MUTATION_DENIED", "VIRTUAL_DNS_RAW_QUERY_DENIED",
        "VIRTUAL_VPN_ESTABLISH_DENIED")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkNetworkObjectFactory.java",
        "networkArray", "capabilities", "linkProperties", "networkInfo", "proxyInfo")
require("sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/core/FrameworkHooks.java",
        'attempt("connectivity"', 'attempt("dnsResolver"', 'attempt("vpn"')
for hook in ("ConnectivityServiceHook", "DnsResolverServiceHook", "VpnManagerServiceHook"):
    require(f"sandbox-framework/src/main/java/com/warden/controlledsandbox/framework/service/{hook}.java",
            "ReflectiveServiceHook")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/NetworkServiceProxyReadiness.java",
        "VIRTUAL_NETWORK_SERVICE_PROXY_REQUIRED", '"connectivity"', '"dnsResolver"', '"vpn"')
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/guest/GuestRuntimeEnvironment.java",
        "NetworkServiceProxyReadiness.require")
require("sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/systemservice/RemoteVirtualSystemServiceAuthority.java",
        "onNetworkServiceProfileChanged", "getNetworkServiceProfile")

require("app/src/testHarness/java/com/warden/controlledsandbox/VirtualNetworkServiceStoreSelfTest.java",
        "NETWORK_PROFILE_VERSION_CONFLICT", "corrupt network file quarantined",
        "virtual users receive isolated network defaults",
        "DNS server hostnames are rejected before framework projection")
require("sandbox-framework/src/testHarness/java/com/warden/controlledsandbox/framework/core/NetworkServiceVirtualizationSelfTest.java",
        "active network is virtualized", "network callback is released",
        "synthetic DNS answer is dispatched", "missing DNS record returns NXDOMAIN",
        "VPN session limit is enforced", "HOST connectivity mode passes through",
        "failed network callback registration rolls back ownership",
        "AAAA query never receives an IPv4 A-record fallback",
        "STATIC proxy remains virtualized when Connectivity mode is HOST")
require("sandbox-runtime/src/testHarness/java/com/warden/controlledsandbox/runtime/guest/NetworkServiceProxyReadinessSelfTest.java",
        "missing DNS resolver hook blocks launch", "PASS M5-T10 network-service proxy readiness self-test")

runner = text("tools/static_android_compile.py")
for test in ("VirtualNetworkServiceStoreSelfTest", "NetworkServiceVirtualizationSelfTest",
             "NetworkServiceProxyReadinessSelfTest"):
    if test not in runner:
        errors.append(f"static Android compiler does not execute {test}")

for root in ("app", "sandbox-contract", "sandbox-domain", "sandbox-framework", "sandbox-runtime",
             "sandbox-native", "sandbox-native-companion"):
    for path in (ROOT / root).rglob("*"):
        if not path.is_file() or path.suffix not in {".java", ".kt", ".cpp", ".h", ".aidl"}:
            continue
        value = path.read_text(encoding="utf-8", errors="ignore")
        if "com.lody.virtual" in value or "top.niunaijun.blackbox" in value:
            errors.append(f"product source imports/reference-copies upstream namespace: {path.relative_to(ROOT)}")

try:
    preflight = json.loads(text("verification/m5-t10-source-preflight.json"))
    if preflight.get("stage") != "M5-T10": errors.append("M5-T10 preflight stage is incorrect")
    if preflight.get("sourceStatus") != "pass": errors.append("M5-T10 source status must be pass")
    if preflight.get("productionStatus") != "partial": errors.append("M5-T10 production status must remain partial")
    if preflight.get("androidBuildStatus") != "blocked-toolchain":
        errors.append("M5-T10 Android build status must remain blocked-toolchain")
    services = preflight.get("networkServices", {})
    if services.get("modes") != ["BLOCKED", "STATIC", "HOST"]:
        errors.append("M5-T10 mode contract is incorrect")
    if services.get("isolationKey") != ["packageName", "virtualUserId"]:
        errors.append("M5-T10 isolation key is incorrect")
    persistence = services.get("persistence", {})
    for key in ("atomic", "bounded", "crcVerified", "corruptStateQuarantine", "optimisticVersioning"):
        if persistence.get(key) is not True: errors.append(f"M5-T10 persistence evidence missing: {key}")
    for domain in ("connectivity", "dns", "proxy", "vpn"):
        item = services.get(domain, {})
        if item.get("source") != "complete-for-stage": errors.append(f"{domain} source status is incorrect")
        if item.get("device") != "not-tested": errors.append(f"{domain} device status must be not-tested")
    for value in preflight.get("deviceEvidence", {}).values():
        if value != 0: errors.append("M5-T10 device evidence must remain zero")
except Exception as exc:
    errors.append(f"invalid M5-T10 preflight: {exc}")

try:
    matrix = json.loads(text("verification/m3-source-capability-matrix.json"))
    capabilities = matrix.get("capabilities", [])
    if len(capabilities) != 113:
        errors.append(f"M5-T10 must preserve the frozen 113 capability categories, found {len(capabilities)}")
    if Counter(item.get("sourceStatus") for item in capabilities) != Counter({"complete": 113}):
        errors.append("all frozen capability categories must remain source-complete")
    if Counter(item.get("productionStatus") for item in capabilities) != Counter(
            {"wired": 110, "partial": 2, "not-applicable": 1}):
        errors.append("M5-T10 must not rewrite frozen production counters")
    if Counter(item.get("deviceStatus") for item in capabilities) != Counter(
            {"not-tested": 109, "not-applicable": 3, "blocked": 1}):
        errors.append("M5-T10 must not invent device evidence")
except Exception as exc:
    errors.append(f"invalid capability matrix: {exc}")

if "check-m5-t10-network-services.py" not in text("scripts/verify-all.sh"):
    errors.append("verify-all.sh missing M5-T10 gate")

if errors:
    print("FAIL M5-T10 network-service checks", file=sys.stderr)
    for error in errors:
        print(" - " + error, file=sys.stderr)
    raise SystemExit(1)
print("PASS M5-T10 network-service checks: Connectivity/DNS/Proxy/VPN source expanded; device limits remain explicit")
