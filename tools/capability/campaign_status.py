"""Fixed status enumerations for T57-R03 capability campaign artifacts."""

from __future__ import annotations

CAPABILITY_STATUS = frozenset(
    {
        "PASS",
        "PARTIAL",
        "GAP",
        "UNVERIFIED",
        "BLOCKED_ENV",
        "NOT_APPLICABLE",
        "EXPECTED",
        "NOT_PROVEN",
    }
)

IMPLEMENTATION_STATUS = frozenset(
    {
        "PASS",
        "PARTIAL",
        "GAP",
        "UNVERIFIED",
        "BLOCKED_ENV",
        "NOT_APPLICABLE",
        "EXPECTED",
    }
)

VA_PRO_EQUIVALENT_STATUS = frozenset(
    {
        "NOT_PROVEN",
        "PASS",
        "PARTIAL",
        "GAP",
        "UNVERIFIED",
        "BLOCKED_ENV",
        "NOT_APPLICABLE",
    }
)

ISSUE_CLASSIFICATION = frozenset(
    {
        "CURRENT_DEFECT",
        "ARCHITECTURE_GAP",
        "COMPATIBILITY_GAP",
        "TEST_EVIDENCE_GAP",
        "KNOWN_DEBT",
        "EXPECTED_BEHAVIOR",
        "ENVIRONMENT_BLOCKED",
        "APP_OR_SDK_SPECIFIC",
        "NEEDS_REPRODUCTION_AND_CLASSIFICATION",
        "NEEDS_GATE_AND_CODE_REVIEW",
    }
)

ISSUE_SEVERITY = frozenset({"CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"})

ISSUE_STATUS = frozenset(
    {
        "RECORDED",
        "OPEN",
        "KEEP_AS_IS",
        "UNCHANGED",
        "NOT_PROVEN",
        "BLOCKED",
        "FIXED",
    }
)

CAS_STATUS = frozenset(
    {
        "CAS_ALREADY_COVERS",
        "NEEDS_TEST",
        "GAP",
        "IMPLEMENTED",
        "NOT_APPLICABLE",
        "UNVERIFIED",
    }
)

AUDIT_CLASSIFICATION = frozenset(
    {
        "PASS",
        "KNOWN_ISSUE",
        "EXPECTED_WARNING",
        "NEW_REGRESSION",
        "UNVERIFIED",
        "FAIL",
        "ENVIRONMENT_BLOCKED",
    }
)

MATURITY_LEVEL = frozenset(
    {
        "RD_BASELINE",
        "ANDROID_MATRIX",
        "OEM_COMMERCIAL_MATRIX",
    }
)

REQUIRED_CAPABILITY_FIELDS = (
    "id",
    "title",
    "implementation_status",
    "static_status",
    "rd_api32_status",
    "api33_status",
    "api34_status",
    "api35_status",
    "api36_status",
    "oem_status",
    "commercial_app_status",
    "va_pro_equivalent",
    "evidence",
    "known_gaps",
    "last_verified_commit",
)

REQUIRED_ISSUE_FIELDS = (
    "issue_id",
    "title",
    "capability",
    "classification",
    "severity",
    "status",
    "discovered_by",
    "evidence",
    "blocks_current_campaign",
    "fix_policy",
    "notes",
)

REQUIRED_CORPUS_FIELDS = (
    "id",
    "category",
    "source",
    "summary",
    "source_reference",
    "commercial_release_signal",
    "cas_evidence",
    "cas_mapping",
    "cas_status",
    "required_test",
    "rd_status",
    "android_matrix_status",
    "oem_status",
    "va_pro_equivalence_effect",
)

REQUIRED_CAPABILITY_IDS = (
    "activity_framework",
    "service_framework",
    "bind_application_loaded_apk_component_factory",
    "pending_intent_intent_sender",
    "manifest_package_parser",
    "virtual_pms_package_visibility",
    "multi_process_process_name_slot",
    "provider_transport",
    "classloader",
    "native_loader_jni_io",
    "process_death_recovery",
    "package_lifecycle_clear_delete_reinstall",
    "system_service_virtualization",
    "android_oem_compatibility",
)

BASELINE_COMMIT = "9e5d3e73628d80872c21776897898493925c7a97"
CAMPAIGN_ID = "T57-R03-01"
RD_INSTANCE_NAME = "RD测试"
FORBIDDEN_STATUS_WORDS = (
    "done",
    "basically done",
    "should work",
    "mostly supported",
)
