package com.warden.controlledsandbox.runtime.protocol;

public final class RuntimeKeys {
    public static final String PROTOCOL = "protocol";
    public static final String SESSION_ID = "sessionId";
    public static final String GENERATION = "generation";
    public static final String PACKAGE_NAME = "packageName";
    public static final String VIRTUAL_USER_ID = "virtualUserId";
    public static final String VIRTUAL_UID = "virtualUid";
    public static final String PERMISSIONS = "permissions";
    public static final String PACKAGE_STATE = "packageState";
    public static final String PACKAGE_UNIVERSE = "packageUniverse";
    public static final String PACKAGE_RESOURCE_TARGET = "packageResourceTarget";
    public static final String PACKAGE_RESOURCE_APK_FD = "packageResourceApkFd";
    public static final String PACKAGE_RESOURCE_SPLIT_FDS = "packageResourceSplitFds";
    public static final String VIRTUAL_SYSTEM_SERVICE_BINDER = "virtualSystemServiceBinder";
    public static final String RUNTIME_BROKER_BINDER = "runtimeBrokerBinder";
    public static final String RUNTIME_STORAGE_BINDER = "runtimeStorageBinder";
    public static final String PROCESS_SLOT = "processSlot";
    /** Debug/RD only: occupy N low slots (0..N-1) before preparing the requested process. */
    public static final String SLOT_PAD_COUNT = "slotPadCount";
    /** Debug/RD only: occupy every other slot so the prepared process receives this slot. */
    public static final String SLOT_TARGET = "slotTarget";
    public static final String PROCESS_NAME = "processName";
    public static final String ISOLATED_PROCESS = "isolatedProcess";
    public static final String ISOLATED_CAPABILITY_TOKEN = "isolatedCapabilityToken";
    /** Broker-owned admission Binder used by isolated workers to register their platform UID. */
    public static final String ISOLATED_PEER_ADMISSION = "isolatedPeerAdmission";
    public static final String ISOLATED_PLATFORM_PID = "isolatedPlatformPid";
    public static final String ISOLATED_PLATFORM_UID = "isolatedPlatformUid";
    /** Binder-transferred file capabilities used by platform isolated_app workers. */
    public static final String ISOLATED_APK_FD = "isolatedApkFd";
    public static final String ISOLATED_APK_ENTRY_NAME = "isolatedApkEntryName";
    public static final String ISOLATED_MATERIALIZED_APK_FD = "isolatedMaterializedApkFd";
    public static final String ISOLATED_MATERIALIZED_APK_PATH = "isolatedMaterializedApkPath";
    public static final String ISOLATED_DATA_ROOT_FD = "isolatedDataRootFd";
    public static final String ISOLATED_NATIVE_LIBRARY_FD = "isolatedNativeLibraryFd";
    public static final String ISOLATED_NATIVE_LIBRARY_FDS = "isolatedNativeLibraryFds";
    public static final String ISOLATED_NATIVE_LIBRARY_ENTRY_NAMES = "isolatedNativeLibraryEntryNames";
    public static final String ISOLATED_SPLIT_FDS = "isolatedSplitFds";
    public static final String ISOLATED_SPLIT_ENTRY_NAMES = "isolatedSplitEntryNames";
    public static final String ISOLATED_PROJECTION_FDS = "isolatedProjectionFds";
    public static final String ISOLATED_FRAMEWORK_SERVICE_RELAYS = "isolatedFrameworkServiceRelays";
    public static final String ISOLATED_SESSION_COUNT = "isolatedSessionCount";
    public static final String ISOLATED_SLOT_CAPACITY = "isolatedSlotCapacity";
    public static final String ISOLATED_SLOT_USED = "isolatedSlotUsed";
    public static final String APK_PATH = "apkPath";
    public static final String APK_SHA256 = "apkSha256";
    public static final String BASE_APK_SHA256 = "baseApkSha256";
    public static final String SPLIT_NAMES = "splitNames";
    public static final String SPLIT_TYPES = "splitTypes";
    public static final String SPLIT_CONFIG_FOR = "splitConfigFor";
    public static final String SPLIT_USES = "splitUses";
    public static final String SPLIT_PATHS = "splitPaths";
    public static final String SPLIT_SHA256S = "splitSha256s";
    public static final String SHARED_LIBRARIES = "sharedLibraries";
    public static final String APK_VERSION_CODE = "apkVersionCode";
    public static final String PACKAGE_REVISION = "packageRevision";
    public static final String NATIVE_LIBRARY_DIR = "nativeLibraryDir";
    public static final String NATIVE_ABI = "nativeAbi";
    public static final String NATIVE_CODE_PRESENT = "nativeCodePresent";
    public static final String NATIVE_GUEST_TRUST = "nativeGuestTrust";
    public static final String NATIVE_EXECUTION_MODE = "nativeExecutionMode";
    public static final String NATIVE_EXECUTION_PROFILE = "nativeExecutionProfile";
    public static final String HOSTILE_CAPABILITY_BROKER = "hostileCapabilityBroker";
    public static final String APPLICATION_CLASS = "applicationClass";
    public static final String COMPONENT_CLASS = "componentClass";
    public static final String DATA_ROOT = "dataRoot";
    public static final String STORAGE_OPERATION = "storageOperation";
    public static final String STORAGE_NAME = "storageName";
    public static final String STORAGE_TARGET_NAME = "storageTargetName";
    public static final String STORAGE_DEVICE_PROTECTED = "storageDeviceProtected";
    public static final String STORAGE_SOURCE_DEVICE_PROTECTED = "storageSourceDeviceProtected";
    public static final String STORAGE_TARGET_DEVICE_PROTECTED = "storageTargetDeviceProtected";
    public static final String STORAGE_DATA = "storageData";
    public static final String STORAGE_EXISTS = "storageExists";
    public static final String STORAGE_SUCCESS = "storageSuccess";
    public static final String ROUTE_TOKEN = "routeToken";
    public static final String ROUTE_EXPIRES_AT = "routeExpiresAtMs";
    public static final String ACTIVITY_TOKEN = "activityToken";
    public static final String TASK_ID = "taskId";
    public static final String ACTIVITY_ACTION = "activityAction";
    /** True when the virtual Activity ledger created a new virtual task for this launch. */
    public static final String CREATED_NEW_TASK = "createdNewTask";
    public static final String HOST_TASK_REBIND_REQUIRED = "hostTaskRebindRequired";
    public static final String ACTIVITY_EVENT = "activityEvent";
    public static final String ACTIVITY_LAUNCH_MODE = "activityLaunchMode";
    public static final String ACTIVITY_FLAGS = "activityFlags";
    public static final String TASK_AFFINITY = "taskAffinity";
    public static final String DOCUMENT_LAUNCH_MODE = "documentLaunchMode";
    public static final String DOCUMENT_KEY = "documentKey";
    public static final String CALLER_TASK_ID = "callerTaskId";
    /** Broker-to-client handoff for launches issued by a Framework-managed Activity. */
    public static final String ACTIVITY_FRAMEWORK_HOST = "activityFrameworkHost";
    public static final String HOST_ACTIVITY_INTENT = "hostActivityIntent";
    public static final String HOST_ACTIVITY_CLASS = "hostActivityClass";
    public static final String HOST_ACTIVITY_FLAGS = "hostActivityFlags";
    public static final String RESULT_WHO = "resultWho";
    public static final String ACTIVITY_RESULT_KEY = "activityResultKey";
    public static final String INTENT_SENDER_TOKEN = "intentSenderToken";
    public static final String RESULT_CODE = "activityResultCode";
    public static final String RESULT_INTENT_ACTION = "activityResultIntent.action";
    public static final String RESULT_INTENT_DATA = "activityResultIntent.data";
    public static final String RESULT_INTENT_TYPE = "activityResultIntent.type";
    public static final String RESULT_INTENT_COMPONENT = "activityResultIntent.component";
    public static final String RESULT_INTENT_FLAGS = "activityResultIntent.flags";
    public static final String RESULT_INTENT_CLIP = "activityResultIntent.clip";
    public static final String RESULT_INTENT_EXTRA_PREFIX = "activityResultIntent.extra.";
    public static final String REQUEST_CODE = "requestCode";
    public static final String SAVED_STATE_VERSION = "savedStateVersion";
    public static final String SAVED_STATE_PREFIX = "savedState.";
    /** Opaque Parcel-marshalled Bundle from the real ActivityThread callback. */
    public static final String SAVED_STATE_PAYLOAD = "savedStatePayload";
    /** Opaque PersistableBundle payload from the three-argument ActivityThread callback. */
    public static final String SAVED_STATE_PERSISTABLE_PAYLOAD = "savedStatePersistablePayload";
    public static final String CONFIGURATION_TOKEN = "configurationToken";
    public static final String HANDLES_CONFIGURATION = "handlesConfiguration";
    public static final String REMOVED_ACTIVITY_COUNT = "removedActivityCount";
    public static final String REMOVED_ACTIVITY_TOKENS = "removedActivityTokens";
    public static final String ACTIVITY_COUNT = "activityCount";
    public static final String TASK_COUNT = "taskCount";
    public static final String RESTORED_ACTIVITY_COUNT = "restoredActivityCount";
    public static final String SERVICE_STATE = "serviceState";
    public static final String SERVICE_RESTART_MODE = "serviceRestartMode";
    public static final String SERVICE_START_COUNT = "serviceStartCount";
    public static final String SERVICE_CONNECTION_COUNT = "serviceConnectionCount";
    public static final String SERVICE_LAST_START_ID = "serviceLastStartId";
    public static final String SERVICE_RECORD_COUNT = "serviceRecordCount";
    public static final String SERVICE_FOREGROUND = "serviceForeground";
    public static final String SERVICE_FOREGROUND_REQUESTED = "serviceForegroundRequested";
    public static final String SERVICE_RECOVERY = "serviceRecovery";
    public static final String SERVICE_REDELIVERED = "serviceRedelivered";
    public static final String SERVICE_START_ID = "serviceStartId";
    public static final String SERVICE_STOPPED_BY_START_ID = "serviceStoppedByStartId";
    public static final String SERVICE_CONNECTION_BINDER = "serviceConnectionBinder";
    public static final String SERVICE_CONNECTION_DEATH_TRACKED = "serviceConnectionDeathTracked";
    public static final String SERVICE_FOREGROUND_STATE = "serviceForegroundState";
    public static final String SERVICE_FOREGROUND_REQUESTED_AT_MS = "serviceForegroundRequestedAtMs";
    public static final String SERVICE_FOREGROUND_DEADLINE_MS = "serviceForegroundDeadlineMs";
    public static final String SERVICE_FOREGROUND_PROMOTED_AT_MS = "serviceForegroundPromotedAtMs";
    public static final String SERVICE_FOREGROUND_PROMOTION_TIMEOUT_MS = "serviceForegroundPromotionTimeoutMs";
    public static final String SERVICE_FOREGROUND_DECLARED_TYPE_MASK = "serviceForegroundDeclaredTypeMask";
    public static final String SERVICE_FOREGROUND_REQUESTED_TYPE_MASK = "serviceForegroundRequestedTypeMask";
    public static final String SERVICE_FOREGROUND_ACTIVE_TYPE_MASK = "serviceForegroundActiveTypeMask";
    public static final String SERVICE_FOREGROUND_NOTIFICATION_ID = "serviceForegroundNotificationId";
    public static final String SERVICE_FOREGROUND_NOTIFICATION_TAG = "serviceForegroundNotificationTag";
    public static final String SERVICE_FOREGROUND_REMOVE_NOTIFICATION = "serviceForegroundRemoveNotification";
    /** The real ActivityThread Service callback observed a successful foreground promotion. */
    public static final String SERVICE_FOREGROUND_OBSERVED = "serviceForegroundObserved";
    public static final String SERVICE_FOREGROUND_BACKGROUND_ALLOWED = "serviceForegroundBackgroundAllowed";
    public static final String SERVICE_FOREGROUND_EXEMPTION_REASON = "serviceForegroundExemptionReason";
    public static final String SERVICE_FOREGROUND_TERMINAL_REASON = "serviceForegroundTerminalReason";
    public static final String SERVICE_FOREGROUND_EXPIRED_COUNT = "serviceForegroundExpiredCount";
    public static final String FRAMEWORK_SERVICE_EVENT = "frameworkServiceEvent";
    public static final String FRAMEWORK_SERVICE_FOREGROUND = "frameworkServiceForeground";
    public static final String FRAMEWORK_SERVICE_OWNED = "frameworkOwnedService";
    public static final String SERVICE_START_RESULT = "serviceStartResult";
    public static final String PENDING_INTENT_TOKEN_ID = "pendingIntentTokenId";
    public static final String PENDING_INTENT_SENDER_PERMISSION = "pendingIntentSenderPermission";
    public static final String PENDING_INTENT_FILL_IN = "pendingIntentFillIn";
    public static final String PENDING_INTENT_FLAGS_MASK = "pendingIntentFlagsMask";
    public static final String PENDING_INTENT_FLAGS_VALUES = "pendingIntentFlagsValues";
    /** Per-send result code supplied to the PendingIntent target, not an Intent flag value. */
    public static final String PENDING_INTENT_RESULT_CODE = "pendingIntentResultCode";
    /** True when the Bundle also carries the actual merged Intent delivered by IIntentSender. */
    public static final String PENDING_INTENT_DELIVERED_INTENT = "pendingIntentDeliveredIntent";
    public static final String PENDING_INTENT_SENDER_BINDER = "pendingIntentSenderBinder";
    public static final String STATUS = "status";
    public static final String ERROR_TYPE = "errorType";
    public static final String ERROR_MESSAGE = "errorMessage";

    public static final String CONNECTION_ID = "connectionId";
    public static final String SERVICE_BIND_FLAGS = "serviceBindFlags";
    public static final String BINDER = "binder";
    public static final String RECEIVER_ID = "receiverId";
    public static final String RECEIVER_ACTIONS = "receiverActions";
    public static final String RECEIVER_EXPORTED = "receiverExported";
    public static final String RECEIVER_PERMISSION = "receiverPermission";
    public static final String RECEIVER_DYNAMIC_INSTANCE = "receiverDynamicInstance";
    public static final String RECEIVER_PRIORITY = "receiverPriority";
    public static final String RECEIVER_CATEGORIES = "receiverCategories";
    public static final String RECEIVER_DATA_RULE_COUNT = "receiverDataRuleCount";
    public static final String RECEIVER_DATA_RULE_PREFIX = "receiverDataRule.";
    public static final String RECEIVER_DATA_PATH_PREFIX = "receiverDataPathPrefix";
    public static final String RECEIVER_DATA_PATH_PATTERN = "receiverDataPathPattern";
    public static final String RECEIVER_MANIFEST = "receiverManifest";
    public static final String RECEIVER_PROCESS_STARTED = "receiverProcessStarted";
    public static final String RECEIVER_ACTIVATION_KEY = "receiverActivationKey";
    /** Explicit opt-in for a manifest Receiver to cross the real ActivityThread boundary. */
    public static final String FRAMEWORK_RECEIVER_ROUTE = "frameworkReceiverRoute";
    public static final String FRAMEWORK_RECEIVER_ENVELOPE = "frameworkReceiverEnvelope";
    public static final String FRAMEWORK_RECEIVER_ROUTE_ID = "frameworkReceiverRouteId";
    public static final String BROADCAST_CATEGORIES = "broadcastCategories";
    public static final String BROADCAST_SCHEME = "broadcastScheme";
    public static final String BROADCAST_HOST = "broadcastHost";
    public static final String BROADCAST_PORT = "broadcastPort";
    public static final String BROADCAST_PATH = "broadcastPath";
    public static final String BROADCAST_MIME_TYPE = "broadcastMimeType";
    public static final String BROADCAST_REQUIRED_RECEIVER_PERMISSION = "broadcastRequiredReceiverPermission";
    public static final String BROADCAST_ORDERED = "broadcastOrdered";
    public static final String BROADCAST_MATCHED_COUNT = "broadcastMatchedCount";
    public static final String BROADCAST_DELIVERED_COUNT = "broadcastDeliveredCount";
    public static final String BROADCAST_FAILED_COUNT = "broadcastFailedCount";
    public static final String BROADCAST_DELIVERY_FAILURES = "broadcastDeliveryFailures";
    public static final String BROADCAST_RESULT_CODE = "broadcastResultCode";
    public static final String BROADCAST_RESULT_DATA = "broadcastResultData";
    public static final String BROADCAST_RESULT_EXTRAS = "broadcastResultExtras";
    public static final String BROADCAST_ABORT = "broadcastAbort";
    public static final String BROADCAST_CLEAR_ABORT = "broadcastClearAbort";
    public static final String BROADCAST_ABORTED = "broadcastAborted";
    public static final String BROADCAST_STOP_ON_FAILURE = "broadcastStopOnFailure";
    public static final String BROADCAST_PRIORITY = "broadcastPriority";
    public static final String BROADCAST_PAYLOAD_BYTES = "broadcastPayloadBytes";
    public static final String BROADCAST_RECEIVER_TIMEOUT_MS = "broadcastReceiverTimeoutMs";
    /**
     * Framework-owned Receiver routes may wait behind an ActivityThread callback while the
     * guest is finishing an Activity launch transaction. This bounded queue grace is distinct
     * from an unbounded Receiver wait and is used only for the real ActivityThread transport.
     */
    public static final long FRAMEWORK_RECEIVER_DISPATCH_TIMEOUT_MS = 30_000L;
    public static final String BROADCAST_PENDING_ASYNC = "broadcastPendingAsync";
    public static final String BROADCAST_CHAIN_TIMEOUT_MS = "broadcastChainTimeoutMs";
    public static final String BROADCAST_CHAIN_DEADLINE_MS = "broadcastChainDeadlineMs";
    public static final String BROADCAST_SKIPPED_COUNT = "broadcastSkippedCount";
    public static final String BROADCAST_TIMED_OUT_COUNT = "broadcastTimedOutCount";
    public static final String BROADCAST_TERMINAL_REASON = "broadcastTerminalReason";
    public static final String BROADCAST_ABORT_SOURCE = "broadcastAbortSource";
    public static final String ORDERED_RECEIVER_TOKEN = "orderedReceiverToken";
    public static final String ORDERED_RECEIVER_DEADLINE_MS = "orderedReceiverDeadlineMs";
    public static final String ORDERED_RECEIVER_COMPLETION_BINDER = "orderedReceiverCompletionBinder";
    public static final String ORDERED_RECEIVER_ACCEPTED = "orderedReceiverAccepted";
    public static final String ORDERED_RECEIVER_STATE = "orderedReceiverState";

    public static final String URI = "uri";
    public static final String URI_FLAGS = "uriFlags";
    public static final String URI_GRANT_ID = "uriGrantId";
    public static final String URI_GRANT_TTL_MS = "uriGrantTtlMs";
    public static final String URI_GRANT_EXPIRES_AT = "uriGrantExpiresAtMs";
    public static final String URI_GRANT_ONE_TIME = "uriGrantOneTime";
    public static final String URI_GRANT_TARGET_SESSION_ID = "uriGrantTargetSessionId";
    public static final String URI_GRANT_TARGET_GENERATION = "uriGrantTargetGeneration";
    public static final String URI_GRANT_CONSUMED_ONE_TIME = "uriGrantConsumedOneTime";
    public static final String URI_CHECK_PID = "uriCheckPid";
    public static final String URI_CHECK_UID = "uriCheckUid";
    public static final String URI_PERMISSION_RESULT = "uriPermissionResult";
    public static final String CALLER_PACKAGE_NAME = "callerPackageName";
    public static final String CALLER_VIRTUAL_USER_ID = "callerVirtualUserId";
    public static final String CALLER_SESSION_ID = "callerSessionId";
    public static final String CALLER_GENERATION = "callerGeneration";
    public static final String TARGET_PACKAGE_NAME = "targetPackageName";
    public static final String INTENT_COMPONENT_PACKAGE = "intentComponentPackage";
    public static final String INTENT_COMPONENT_CLASS = "intentComponentClass";
    public static final String INTENT_EXTRAS = "intentExtras";
    /** Opaque Guest Intent Parcel preserving fields not represented by the bounded projection. */
    public static final String INTENT_WIRE_PAYLOAD = "intentWirePayload";
    public static final String TARGET_VIRTUAL_USER_ID = "targetVirtualUserId";

    /** Internal, signature-protected Host -> native Companion Provider relay marker. */
    public static final String CROSS_ABI_PROVIDER_RELAY = "crossAbiProviderRelay";
    /** Host-authorized Provider permission decision carried to the native Companion. */
    public static final String CROSS_ABI_PROVIDER_PERMISSION_BASIS =
            "crossAbiProviderPermissionBasis";

    public static final String PROVIDER_PROJECTION = "providerProjection";
    public static final String PROVIDER_SELECTION = "providerSelection";
    public static final String PROVIDER_SELECTION_ARGS = "providerSelectionArgs";
    public static final String PROVIDER_SORT_ORDER = "providerSortOrder";
    /** API 26+ ContentProvider.query() arguments, projected to bounded primitives only. */
    public static final String PROVIDER_QUERY_ARGS = "providerQueryArgs";
    /** Correlation id for cancellation while Provider.query() is still executing. */
    public static final String PROVIDER_QUERY_ID = "providerQueryId";
    /** Broker-owned IProviderQueryCancellation channel installed for one in-flight query. */
    public static final String PROVIDER_QUERY_CANCEL_CHANNEL = "providerQueryCancelChannel";
    public static final String PROVIDER_VALUES = "providerValues";
    public static final String PROVIDER_BULK_VALUE_COUNT = "providerBulkValueCount";
    public static final String PROVIDER_BULK_VALUE_PREFIX = "providerBulkValue.";
    public static final String PROVIDER_EXPORTED = "providerExported";
    public static final String PROVIDER_METHOD = "providerMethod";
    public static final String PROVIDER_ARGUMENT = "providerArgument";
    public static final String PROVIDER_EXTRAS = "providerExtras";
    public static final String PROVIDER_RESULT = "providerResult";
    public static final String PROVIDER_AUDIT_ID = "providerAuditId";
    public static final String PROVIDER_PERMISSION_BASIS = "providerPermissionBasis";
    public static final String PROVIDER_FILE_MODE = "providerFileMode";
    public static final String PROVIDER_MIME_TYPE = "providerMimeType";
    public static final String PROVIDER_STREAM_TYPES = "providerStreamTypes";
    public static final String PROVIDER_FILE_OPTIONS = "providerFileOptions";
    public static final String PROVIDER_BATCH_COUNT = "providerBatchCount";
    public static final String PROVIDER_BATCH_TYPE = "providerBatchType";
    public static final String PROVIDER_BATCH_OPERATION_PREFIX = "providerBatchOperation.";
    public static final String PROVIDER_BATCH_RESULT_PREFIX = "providerBatchResult.";
    public static final String PROVIDER_BATCH_RESULT_COUNT = "providerBatchResultCount";
    public static final String PROVIDER_BATCH_FAILURE_INDEX = "providerBatchFailureIndex";
    public static final String PROVIDER_BATCH_EXPECTED_COUNT = "providerBatchExpectedCount";
    public static final String PROVIDER_BATCH_AFFECTED_ROWS = "providerBatchAffectedRows";
    public static final String PROVIDER_BATCH_ESTIMATED_BYTES = "providerBatchEstimatedBytes";
    /** ContentProviderOperation flags retained across the Guest/Broker transport. */
    public static final String PROVIDER_BATCH_YIELD_ALLOWED = "providerBatchYieldAllowed";
    public static final String PROVIDER_BATCH_EXCEPTION_ALLOWED = "providerBatchExceptionAllowed";
    /** API 30+ ContentProviderOperation.call() payload and back-reference map. */
    public static final String PROVIDER_BATCH_EXTRAS = "providerBatchExtras";
    public static final String PROVIDER_BATCH_EXTRAS_BACK_REFERENCES =
            "providerBatchExtrasBackReferences";
    /** Optional ContentProviderResult.extras source key for each extra back-reference. */
    public static final String PROVIDER_BATCH_EXTRAS_BACK_REFERENCE_SOURCES =
            "providerBatchExtrasBackReferenceSources";
    /** API 30+ ContentProviderResult projections. */
    public static final String PROVIDER_BATCH_RESULT_EXTRAS = "providerBatchResultExtras";
    public static final String PROVIDER_BATCH_RESULT_EXCEPTION_TYPE =
            "providerBatchResultExceptionType";
    public static final String PROVIDER_BATCH_RESULT_EXCEPTION_MESSAGE =
            "providerBatchResultExceptionMessage";
    /** String-keyed map: ContentValues key -> previous batch result index. */
    public static final String PROVIDER_BATCH_VALUES_BACK_REFERENCES =
            "providerBatchValuesBackReferences";
    /** Optional ContentProviderResult.extras source key for each value back-reference. */
    public static final String PROVIDER_BATCH_VALUES_BACK_REFERENCE_SOURCES =
            "providerBatchValuesBackReferenceSources";
    /** Stringified integer-keyed map: selection-args index -> previous batch result index. */
    public static final String PROVIDER_BATCH_SELECTION_BACK_REFERENCES =
            "providerBatchSelectionBackReferences";
    /** Optional ContentProviderResult.extras source key for each selection back-reference. */
    public static final String PROVIDER_BATCH_SELECTION_BACK_REFERENCE_SOURCES =
            "providerBatchSelectionBackReferenceSources";

    public static final String OBSERVER_ID = "providerObserverId";
    public static final String OBSERVER_CALLBACK = "providerObserverCallback";
    public static final String OBSERVER_NOTIFY_DESCENDANTS = "providerObserverNotifyDescendants";
    public static final String OBSERVER_DELIVER_SELF = "providerObserverDeliverSelf";
    public static final String OBSERVER_CHANGE_FLAGS = "providerObserverChangeFlags";
    public static final String OBSERVER_MATCHED_COUNT = "providerObserverMatchedCount";
    public static final String OBSERVER_DELIVERED_COUNT = "providerObserverDeliveredCount";
    public static final String OBSERVER_FAILURES = "providerObserverFailures";

    public static final String FILE_TOKEN = "providerFileToken";
    public static final String FILE_DESCRIPTOR = "providerFileDescriptor";
    public static final String ASSET_FILE_DESCRIPTOR = "providerAssetFileDescriptor";
    public static final String FILE_DESCRIPTOR_KIND = "providerFileDescriptorKind";
    public static final String FILE_START_OFFSET = "providerFileStartOffset";
    public static final String FILE_DECLARED_LENGTH = "providerFileDeclaredLength";
    public static final String FILE_EXPIRES_AT = "providerFileExpiresAt";
    public static final String FILE_TTL_MS = "providerFileTtlMs";
    public static final String FILE_OWNER_SESSION_ID = "providerFileOwnerSessionId";
    public static final String FILE_OWNER_GENERATION = "providerFileOwnerGeneration";

    public static final String CURSOR_TOKEN = "cursorToken";
    public static final String CURSOR_COLUMNS = "cursorColumns";
    public static final String CURSOR_TOTAL_ROWS = "cursorTotalRows";
    public static final String CURSOR_OFFSET = "cursorOffset";
    public static final String CURSOR_PAGE_SIZE = "cursorPageSize";
    public static final String CURSOR_ROWS_RETURNED = "cursorRowsReturned";
    public static final String CURSOR_END_REACHED = "cursorEndReached";
    public static final String CURSOR_EXPIRES_AT = "cursorExpiresAt";
    public static final String CURSOR_PAGE_SEQUENCE = "cursorPageSequence";
    public static final String CURSOR_NEXT_SEQUENCE = "cursorNextSequence";
    public static final String CURSOR_NEXT_OFFSET = "cursorNextOffset";
    public static final String CURSOR_PAGE_BYTES = "cursorPageBytes";
    public static final String CURSOR_TTL_MS = "cursorTtlMs";
    public static final String CURSOR_OWNER_SESSION_ID = "cursorOwnerSessionId";
    public static final String CURSOR_OWNER_GENERATION = "cursorOwnerGeneration";
    public static final String CURSOR_EXTRAS = "cursorExtras";
    public static final String CURSOR_NOTIFICATION_URI = "cursorNotificationUri";
    public static final String CURSOR_ROW_PREFIX = "cursorRow.";

    private RuntimeKeys() { }
}
