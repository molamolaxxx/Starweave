package com.mola.cmd.proxy.app.acp.team;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mola.cmd.proxy.app.acp.AcpRobotParam;
import com.mola.cmd.proxy.app.acp.acpclient.AbstractAcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.AcpClient;
import com.mola.cmd.proxy.app.acp.acpclient.PromptOptions;
import com.mola.cmd.proxy.app.acp.mcpauth.AuthPrincipalContext;
import com.mola.cmd.proxy.app.acp.channel.model.ChannelDeliveryContext;
import com.mola.cmd.proxy.app.acp.schedule.ScheduleTaskManager;
import com.mola.cmd.proxy.app.acp.schedule.model.ScheduleOwnerKey;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventEnvelope;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventSink;
import com.mola.cmd.proxy.app.acp.team.event.TeamEventType;
import com.mola.cmd.proxy.app.acp.team.model.*;
import com.mola.cmd.proxy.app.acp.team.protocol.*;
import com.mola.cmd.proxy.app.acp.team.runtime.TeamRuntime;
import com.mola.cmd.proxy.app.acp.team.talkto.TeamTalkToDispatcher;
import com.mola.cmd.proxy.app.acp.talkto.ContactRemarkResolver;
import com.mola.cmd.proxy.app.acp.talkto.ExternalTalkToGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

/**
 * Team 权威状态的最小生命周期容器。
 *
 * <p>本阶段只实现恢复、占位、查询和统一关闭；create/list/get command
 * 及 AcpClient 启动编排由后续 FT-CMD 项实现。</p>
 */
public final class TeamManager implements AutoCloseable {

    private static final Logger logger =
            LoggerFactory.getLogger(TeamManager.class);

    private final TeamStore store;
    private final TeamClientRegistry clientRegistry;
    private final TeamSourceRobotResolver sourceResolver;
    private final TeamEventSink eventSink;
    private final TeamStartupCoordinator startupCoordinator;
    private final ConcurrentHashMap<String, TeamRuntime> teams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> requestLocks = new ConcurrentHashMap<>();
    private final Object createQuotaLock = new Object();
    private final ConcurrentHashMap<String, TeamTalkToDispatcher> talkToDispatchers =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> receivedTalkToMessages =
            new ConcurrentHashMap<>();
    private final Set<String> revokedGrantCleanupPending = ConcurrentHashMap.newKeySet();
    private final Set<String> revokedGrantCleanupRunning = ConcurrentHashMap.newKeySet();
    private volatile ExternalTalkToGateway externalGateway;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Consumer<String> scheduleCleanup = ignored -> { };
    private volatile ScheduleTaskManager scheduleTaskManager;
    private volatile Consumer<Set<String>> scheduleOrphanCleanup = ignored -> { };
    private volatile IntSupplier scheduleOwnerCount = () -> 0;
    private volatile ToIntFunction<String> scheduleTeamOwnerCount = ignored -> 0;
    private volatile BiPredicate<String, String> memoryDreamTrigger =
            (sourceGroupId, workspacePath) -> false;
    private final TeamHistoryArchiver historyArchiver;
    private final long cleanupTimeoutMillis;
    private final ExecutorService cleanupExecutor;
    private final ConcurrentHashMap<String, Future<List<IOException>>> pendingCleanup =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> autoNewSessionLastChecks =
            new ConcurrentHashMap<>();
    /** First observation time for member BUSY while the authoritative client is READY. */
    private final ConcurrentHashMap<String, Long> scheduleStateMismatchSince =
            new ConcurrentHashMap<>();
    private final TeamResourceReaper resourceReaper;
    private final TeamLimits limits;
    private final TeamMetrics metrics = new TeamMetrics();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private static final long OPERATION_TTL_MILLIS = 24L * 60L * 60L * 1000L;
    private static final long TOMBSTONE_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final long DEFAULT_CLEANUP_TIMEOUT_MILLIS = 30_000L;
    private static final long SCHEDULE_STATE_MISMATCH_GRACE_MILLIS = 30_000L;

    public TeamManager(TeamStore store, TeamClientRegistry clientRegistry) {
        this(store, clientRegistry, spec -> {
            throw new TeamSourceResolutionException(
                    TeamErrorCode.SOURCE_ROBOT_NOT_FOUND, "source resolver is not configured");
        }, TeamEventSink.NOOP, null);
    }

    public TeamManager(TeamStore store, TeamClientRegistry clientRegistry,
                       TeamSourceRobotResolver sourceResolver) {
        this(store, clientRegistry, sourceResolver, TeamEventSink.NOOP, null);
    }

    public TeamManager(TeamStore store, TeamClientRegistry clientRegistry,
                       TeamSourceRobotResolver sourceResolver, TeamEventSink eventSink) {
        this(store, clientRegistry, sourceResolver, eventSink, null);
    }

    public TeamManager(TeamStore store, TeamClientRegistry clientRegistry,
                       TeamSourceRobotResolver sourceResolver, TeamEventSink eventSink,
                       TeamStartupCoordinator startupCoordinator) {
        this(store, clientRegistry, sourceResolver, eventSink, startupCoordinator,
                new TeamHistoryArchiver(), DEFAULT_CLEANUP_TIMEOUT_MILLIS,
                TeamLimits.system());
    }

    TeamManager(TeamStore store, TeamClientRegistry clientRegistry,
                TeamSourceRobotResolver sourceResolver, TeamEventSink eventSink,
                TeamStartupCoordinator startupCoordinator,
                TeamHistoryArchiver historyArchiver, long cleanupTimeoutMillis) {
        this(store, clientRegistry, sourceResolver, eventSink,
                startupCoordinator, historyArchiver, cleanupTimeoutMillis,
                TeamLimits.system());
    }

    TeamManager(TeamStore store, TeamClientRegistry clientRegistry,
                TeamSourceRobotResolver sourceResolver, TeamEventSink eventSink,
                TeamStartupCoordinator startupCoordinator,
                TeamHistoryArchiver historyArchiver, long cleanupTimeoutMillis,
                TeamLimits limits) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.clientRegistry = java.util.Objects.requireNonNull(clientRegistry, "clientRegistry");
        this.sourceResolver = java.util.Objects.requireNonNull(sourceResolver, "sourceResolver");
        this.eventSink = java.util.Objects.requireNonNull(eventSink, "eventSink");
        this.startupCoordinator = startupCoordinator;
        this.historyArchiver = java.util.Objects.requireNonNull(
                historyArchiver, "historyArchiver");
        if (cleanupTimeoutMillis < 1L) {
            throw new IllegalArgumentException("cleanupTimeoutMillis must be positive");
        }
        this.cleanupTimeoutMillis = cleanupTimeoutMillis;
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        this.cleanupExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "team-resource-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        this.resourceReaper = new TeamResourceReaper(this);
    }

    public int recoverPersistedDefinitions() throws IOException {
        return recoverPersistedDefinitions(null);
    }

    public int recoverPersistedDefinitions(String currentTransportGroup) throws IOException {
        ensureOpen();
        List<TeamDefinition> persistedDefinitions = store.loadTeams();
        logger.info("team_recovery action=load root={} persistedTeams={}",
                store.getTeamsRoot(), persistedDefinitions.size());
        int added = 0;
        for (TeamDefinition persistedDefinition : persistedDefinitions) {
            TeamDefinition definition = migrateTransportGroup(
                    persistedDefinition, currentTransportGroup);
            logger.info("team_recovery action=inspect teamId={} ownerChatterId={}"
                            + " state={} version={} transportGroup={}",
                    definition.getTeamId(), definition.getOwnerChatterId(),
                    definition.getState(), definition.getVersion(),
                    definition.getTransportGroup());
            if (definition.getState().isTerminal()) {
                cleanupEphemeralResourcesForDelete(definition.getTeamId());
                clientRegistry.closeTeam(definition.getTeamId());
                store.deleteTeam(definition.getTeamId());
                continue;
            }
            TeamDefinition recovered = definition;
            if (definition.getState() == TeamState.READY
                    || isRetryableFailedTeam(definition)) {
                metrics.recoveryStarted();
                recovered = definition.transitionWithMembers(
                        TeamState.RECOVERING,
                        markMembersStarting(definition.getMembers()),
                        null, System.currentTimeMillis());
                store.saveTeam(recovered);
            }
            TeamRuntime runtime = new TeamRuntime(recovered);
            if (teams.putIfAbsent(definition.getTeamId(), runtime) == null) {
                added++;
                if (recovered.getState() == TeamState.CREATING
                        || recovered.getState() == TeamState.RECOVERING) {
                    if (recovered.getState() == TeamState.RECOVERING) {
                        publishRecoveryEvent(runtime,
                                TeamEventType.TEAM_RECOVERY_STARTED, null);
                    }
                    startMembers(runtime);
                } else if (recovered.getState() == TeamState.DELETING) {
                    runtime.stopAcceptingRequests();
                    cleanupExecutor.submit(() -> resumeDeleting(runtime));
                } else if (recovered.getState() == TeamState.FAILED) {
                    runtime.stopAcceptingRequests();
                }
            }
        }
        return added;
    }

    private static boolean isRetryableFailedTeam(TeamDefinition definition) {
        return definition.getState() == TeamState.FAILED
                && definition.getLastError() != null
                && definition.getLastError().isRetryable();
    }

    private static List<TeamMemberDefinition> markMembersStarting(
            List<TeamMemberDefinition> members) {
        List<TeamMemberDefinition> starting = new ArrayList<>();
        for (TeamMemberDefinition member : members) {
            starting.add(member.getState() == TeamMemberState.SLEEP
                    ? member
                    : member.withState(TeamMemberState.STARTING, null, null));
        }
        return starting;
    }

    private TeamDefinition migrateTransportGroup(TeamDefinition definition,
                                                 String currentTransportGroup)
            throws IOException {
        if (definition.getState().isTerminal()
                || currentTransportGroup == null
                || currentTransportGroup.trim().isEmpty()
                || currentTransportGroup.equals(definition.getTransportGroup())) {
            return definition;
        }
        TeamDefinition migrated = definition.withTransportGroup(
                currentTransportGroup, System.currentTimeMillis());
        store.saveTeam(migrated);
        logger.info("team_recovery action=migrate_transport teamId={} oldGroup={} newGroup={} version={}",
                definition.getTeamId(), definition.getTransportGroup(),
                currentTransportGroup, migrated.getVersion());
        return migrated;
    }

    public void startResourceReaper() {
        resourceReaper.start();
    }

    /**
     * 仅供后续 create 状态机在成功持久化后发布 runtime。
     */
    boolean attachPersistedDefinition(TeamDefinition definition) {
        ensureOpen();
        return teams.putIfAbsent(definition.getTeamId(),
                new TeamRuntime(definition)) == null;
    }

    public Optional<TeamRuntime> getRuntime(String teamId) {
        return Optional.ofNullable(teams.get(teamId));
    }

    public List<TeamDefinition> snapshotDefinitions() {
        List<TeamDefinition> result = new ArrayList<>();
        for (TeamRuntime runtime : teams.values()) {
            result.add(runtime.getDefinition());
        }
        result.sort(Comparator.comparing(TeamDefinition::getTeamId));
        return Collections.unmodifiableList(result);
    }

    /** Makes changed external-channel contacts visible without recreating Team sessions. */
    public void requestAcpHarnessReinjectionForAllClients() {
        for (TeamDefinition definition : snapshotDefinitions()) {
            for (TeamMemberDefinition member : definition.getMembers()) {
                clientRegistry.get(definition.getTeamId(), member.getTeamMemberId())
                        .ifPresent(AcpClient::requestAcpHarnessReinjection);
            }
        }
    }

    public TeamCommandResult create(TeamCreateCommand command, String transportGroup) {
        String requestId = command == null ? "" : command.getRequestId();
        try {
            validateCreate(command, transportGroup);
        } catch (TeamValidationException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        }

        String payloadHash = sha256(gson.toJson(command));
        synchronized (requestLocks.computeIfAbsent(requestId, ignored -> new Object())) {
            try {
                Optional<TeamOperationRecord> existingOperation =
                        store.loadOperation(requestId);
                if (existingOperation.isPresent()) {
                    TeamOperationRecord operation = existingOperation.get();
                    if (!payloadHash.equals(operation.getPayloadHash())) {
                        return TeamCommandResult.error(requestId,
                                TeamErrorCode.IDEMPOTENCY_CONFLICT,
                                "requestId already used with different payload");
                    }
                    if (operation.getResultSnapshot() != null
                            && !operation.getResultSnapshot().trim().isEmpty()) {
                        return TeamCommandResult.fromSnapshotJson(
                                operation.getResultSnapshot());
                    }
                } else {
                    store.saveOperation(new TeamOperationRecord(
                            requestId, TeamOperationRecord.Operation.CREATE,
                            payloadHash, command.getTeamId(),
                            TeamOperationRecord.Status.ACCEPTED, null,
                            System.currentTimeMillis(),
                            System.currentTimeMillis() + 24L * 60L * 60L * 1000L));
                }

                synchronized (createQuotaLock) {
                    Optional<TeamDefinition> persisted =
                            store.loadTeam(command.getTeamId());
                    if (persisted.isPresent()) {
                        TeamDefinition definition = persisted.get();
                        if (!requestId.equals(definition.getCreateRequestId())) {
                            return finishOperation(command, payloadHash,
                                    TeamCommandResult.error(requestId,
                                            TeamErrorCode.IDEMPOTENCY_CONFLICT,
                                            "teamId already exists with another create request"),
                                    TeamOperationRecord.Status.FAILED);
                        }
                        attachPersistedDefinition(definition);
                        return finishOperation(command, payloadHash,
                                TeamCommandResult.success(requestId, "ALREADY_EXISTS",
                                        "Team already created by this request",
                                        definition.getVersion(), definition),
                                TeamOperationRecord.Status.SUCCEEDED);
                    }

                    TeamCommandResult quotaError = quotaError(command);
                    if (quotaError != null) {
                        metrics.createQuotaRejected();
                        logger.warn("team_lifecycle action=create_rejected code=QUOTA_EXCEEDED"
                                        + " teamId={} requestId={} activeTeams={}"
                                        + " activeMembers={} requestedMembers={}",
                                command.getTeamId(), requestId, activeTeamCount(),
                                activeMemberCount(), command.getMembers().size());
                        return finishOperation(command, payloadHash, quotaError,
                                TeamOperationRecord.Status.FAILED);
                    }

                    List<TeamMemberDefinition> members = resolveMembers(
                            command.getMembers(), command.getOwnerChatterId(),
                            command.isMixedPlacement());
                    List<TeamContactRef> roster = roster(command, members);
                    long now = System.currentTimeMillis();
                    TeamDefinition definition = TeamDefinition.creating(
                            command.getTeamId(), command.getOwnerChatterId(),
                            command.getName(), transportGroup, requestId, members,
                            command.isMixedPlacement(), roster,
                            TeamMode.fromWire(command.getMode()),
                            normalizeOptional(command.getCaptainTeamMemberId()), now);
                    store.saveTeam(definition);
                    if (!attachPersistedDefinition(definition)) {
                        throw new IllegalStateException("Team runtime already exists");
                    }
                    TeamCommandResult result = finishOperation(command, payloadHash,
                            TeamCommandResult.success(requestId, "ACCEPTED",
                                    "Team definition persisted; member startup pending",
                                    definition.getVersion(), definition),
                            TeamOperationRecord.Status.SUCCEEDED);
                    TeamRuntime runtime = teams.get(definition.getTeamId());
                    metrics.createAccepted();
                    logger.info("team_lifecycle action=create_accepted teamId={}"
                                    + " requestId={} state={} version={} members={}",
                            definition.getTeamId(), requestId,
                            definition.getState(), definition.getVersion(),
                            definition.getMembers().size());
                    try {
                        eventSink.publish(TeamEventEnvelope.next(runtime, null, null,
                                TeamEventType.TEAM_CREATE_ACCEPTED, definition));
                    } catch (RuntimeException ignored) {
                        // callback 是投影通知，失败不能回滚已持久化的权威 Team 定义。
                    }
                    startMembers(runtime);
                    return result;
                }
            } catch (TeamSourceResolutionException e) {
                logger.warn("team_lifecycle action=create_source_rejected teamId={}"
                                + " requestId={} code={} sources={} error={}",
                        command == null ? "" : command.getTeamId(), requestId,
                        e.getCode(), command == null || command.getMembers() == null
                                ? Collections.emptyList()
                                : command.getMembers().stream()
                                .map(member -> member.getSourceGroupId() + "/"
                                        + member.getSourceRobotId())
                                .collect(java.util.stream.Collectors.toList()),
                        e.getMessage());
                return finishOperationQuietly(command, payloadHash,
                        TeamCommandResult.error(requestId, e.getCode(), e.getMessage()));
            } catch (TeamStore.VersionConflictException e) {
                return finishOperationQuietly(command, payloadHash,
                        TeamCommandResult.error(requestId,
                                TeamErrorCode.VERSION_CONFLICT, e.getMessage()));
            } catch (Exception e) {
                return finishOperationQuietly(command, payloadHash,
                        TeamCommandResult.error(requestId,
                                TeamErrorCode.INTERNAL_ERROR, e.getMessage()));
            }
        }
    }

    public TeamCommandResult delete(TeamDeleteCommand command) {
        String requestId = command == null ? "" : command.getRequestId();
        try {
            validateDelete(command);
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        }
        String payloadHash = sha256(gson.toJson(command));
        synchronized (requestLocks.computeIfAbsent(
                requestId, ignored -> new Object())) {
            try {
                Optional<TeamOperationRecord> existing =
                        store.loadOperation(requestId);
                if (existing.isPresent()) {
                    TeamOperationRecord operation = existing.get();
                    if (operation.getOperation()
                            != TeamOperationRecord.Operation.DELETE
                            || !payloadHash.equals(operation.getPayloadHash())) {
                        return TeamCommandResult.error(requestId,
                                TeamErrorCode.IDEMPOTENCY_CONFLICT,
                                "requestId already used with different operation or payload");
                    }
                    if (operation.getResultSnapshot() != null
                            && !operation.getResultSnapshot().trim().isEmpty()) {
                        return TeamCommandResult.fromSnapshotJson(
                                operation.getResultSnapshot());
                    }
                }

                TeamRuntime runtime = teams.get(command.getTeamId());
                if (runtime == null) {
                    Optional<TeamDefinition> persisted =
                            store.loadTeam(command.getTeamId());
                    if (!persisted.isPresent()) {
                        return TeamCommandResult.error(requestId,
                                TeamErrorCode.NOT_FOUND, "Team not found");
                    }
                    attachPersistedDefinition(persisted.get());
                    runtime = teams.get(command.getTeamId());
                }
                TeamDefinition current = runtime.getDefinition();
                if (!command.getOwnerChatterId().equals(
                        current.getOwnerChatterId())) {
                    return TeamCommandResult.error(requestId,
                            TeamErrorCode.UNAUTHORIZED,
                            "Team does not belong to ownerChatterId");
                }
                if (command.getExpectedVersion() != null
                        && command.getExpectedVersion().longValue()
                        != current.getVersion()
                        && current.getState() != TeamState.DELETING) {
                    return TeamCommandResult.error(requestId,
                            TeamErrorCode.VERSION_CONFLICT,
                            "expectedVersion does not match current Team version");
                }
                if (!existing.isPresent()) {
                    store.saveOperation(new TeamOperationRecord(
                            requestId, TeamOperationRecord.Operation.DELETE,
                            payloadHash, command.getTeamId(),
                            TeamOperationRecord.Status.ACCEPTED, null,
                            System.currentTimeMillis(),
                            System.currentTimeMillis() + OPERATION_TTL_MILLIS));
                }

                runtime.getOperationLock().lock();
                try {
                    current = runtime.getDefinition();
                    if (current.getState().isTerminal()) {
                        return finishDeleteOperation(command, payloadHash,
                                deletedResult(requestId, current,
                                        Collections.emptyList(),
                                        current.getDeletedAt() == null
                                                ? System.currentTimeMillis()
                                                : current.getDeletedAt()
                                                + TOMBSTONE_TTL_MILLIS),
                                TeamOperationRecord.Status.SUCCEEDED);
                    }
                    if (current.getState() != TeamState.DELETING) {
                        runtime.stopAcceptingRequests();
                        TeamDefinition deleting = current.beginDeleting(
                                requestId, System.currentTimeMillis());
                        store.saveTeam(deleting);
                        if (!runtime.publishNextDefinition(deleting)) {
                            throw new IOException(
                                    "Team version changed before DELETING publish");
                        }
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("team", deleting);
                        data.put("previousState", current.getState().name());
                        data.put("expectedVersion", command.getExpectedVersion());
                        publishEvent(runtime,
                                TeamEventType.TEAM_DELETE_ACCEPTED, data);
                    } else if (!requestId.equals(
                            current.getDeleteRequestId())) {
                        return finishDeleteOperation(
                                command, payloadHash,
                                TeamCommandResult.error(requestId,
                                        TeamErrorCode.TEAM_DELETING,
                                        "Team deletion is already owned by another request"),
                                TeamOperationRecord.Status.FAILED);
                    }
                    TeamCommandResult result = finalizeDelete(
                            runtime, requestId);
                    return finishDeleteOperation(command, payloadHash, result,
                            result.isAccepted()
                                    ? TeamOperationRecord.Status.SUCCEEDED
                                    : TeamOperationRecord.Status.FAILED);
                } finally {
                    runtime.getOperationLock().unlock();
                }
            } catch (Exception e) {
                TeamRuntime runtime = teams.get(command.getTeamId());
                TeamError error = TeamError.of(
                        TeamErrorCode.INTERNAL_ERROR, safeMessage(e), true);
                if (runtime != null) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("team", runtime.getDefinition());
                    data.put("error", error);
                    publishEvent(runtime, TeamEventType.TEAM_DELETE_FAILED, data);
                }
                TeamCommandResult result = TeamCommandResult.error(
                        requestId, TeamErrorCode.INTERNAL_ERROR, safeMessage(e));
                metrics.deleteFailed();
                logger.warn("team_lifecycle action=delete_failed teamId={}"
                                + " requestId={} error={}",
                        command.getTeamId(), requestId, safeMessage(e));
                try {
                    return finishDeleteOperation(
                            command, payloadHash, result,
                            TeamOperationRecord.Status.FAILED);
                } catch (IOException ignored) {
                    return result;
                }
            }
        }
    }

    public TeamCommandResult updateRemarks(TeamRemarksUpdateCommand command) {
        String requestId = command == null ? "" : command.getRequestId();
        try {
            validateRemarksUpdate(command);
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        }
        String payloadHash = sha256(gson.toJson(command));
        synchronized (requestLocks.computeIfAbsent(requestId, ignored -> new Object())) {
            try {
                Optional<TeamOperationRecord> existing = store.loadOperation(requestId);
                if (existing.isPresent()) {
                    TeamOperationRecord operation = existing.get();
                    if (operation.getOperation() != TeamOperationRecord.Operation.UPDATE_REMARKS
                            || !payloadHash.equals(operation.getPayloadHash())) {
                        return TeamCommandResult.error(requestId,
                                TeamErrorCode.IDEMPOTENCY_CONFLICT,
                                "requestId already used with different operation or payload");
                    }
                    if (operation.getResultSnapshot() != null
                            && !operation.getResultSnapshot().trim().isEmpty()) {
                        return TeamCommandResult.fromSnapshotJson(
                                operation.getResultSnapshot());
                    }
                }
                TeamRuntime runtime = teams.get(command.getTeamId());
                if (runtime == null) {
                    Optional<TeamDefinition> persisted = store.loadTeam(command.getTeamId());
                    if (!persisted.isPresent()) {
                        return TeamCommandResult.error(requestId,
                                TeamErrorCode.NOT_FOUND, "Team not found");
                    }
                    attachPersistedDefinition(persisted.get());
                    runtime = teams.get(command.getTeamId());
                }
                runtime.getOperationLock().lock();
                try {
                    TeamDefinition current = runtime.getDefinition();
                    if (!command.getOwnerChatterId().equals(current.getOwnerChatterId())) {
                        return TeamCommandResult.error(requestId,
                                TeamErrorCode.UNAUTHORIZED,
                                "Team does not belong to ownerChatterId");
                    }
                    if (current.getState().isTerminal()
                            || current.getState() == TeamState.DELETING) {
                        return TeamCommandResult.error(requestId,
                                TeamErrorCode.TEAM_DELETING,
                                "Team cannot be edited while deleting");
                    }
                    if (command.getExpectedVersion() != null
                            && command.getExpectedVersion().longValue()
                            != current.getVersion()) {
                        return TeamCommandResult.error(requestId,
                                TeamErrorCode.VERSION_CONFLICT,
                                "expectedVersion does not match current Team version");
                    }
                    validateMemberRemarks(current, command.getMemberRemarks());
                    if (!existing.isPresent()) {
                        store.saveOperation(new TeamOperationRecord(requestId,
                                TeamOperationRecord.Operation.UPDATE_REMARKS,
                                payloadHash, command.getTeamId(),
                                TeamOperationRecord.Status.ACCEPTED, null,
                                System.currentTimeMillis(),
                                System.currentTimeMillis() + OPERATION_TTL_MILLIS));
                    }
                    TeamDefinition next = current.withMemberRemarks(
                            command.getMemberRemarks(), System.currentTimeMillis());
                    store.saveTeam(next);
                    if (!runtime.publishNextDefinition(next)) {
                        throw new IOException(
                                "Team version changed before remarks update publish");
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("team", next);
                    publishEvent(runtime, TeamEventType.TEAM_REMARKS_UPDATED, data);
                    TeamCommandResult result = TeamCommandResult.success(requestId,
                            "UPDATED", "Team member remarks updated",
                            next.getVersion(), data);
                    return finishRemarksUpdateOperation(command, payloadHash, result,
                            TeamOperationRecord.Status.SUCCEEDED);
                } finally {
                    runtime.getOperationLock().unlock();
                }
            } catch (IllegalArgumentException e) {
                return TeamCommandResult.error(requestId,
                        TeamErrorCode.VALIDATION_ERROR, e.getMessage());
            } catch (TeamStore.VersionConflictException e) {
                return TeamCommandResult.error(requestId,
                        TeamErrorCode.VERSION_CONFLICT, e.getMessage());
            } catch (Exception e) {
                TeamCommandResult result = TeamCommandResult.error(requestId,
                        TeamErrorCode.INTERNAL_ERROR, safeMessage(e));
                try {
                    return finishRemarksUpdateOperation(command, payloadHash, result,
                            TeamOperationRecord.Status.FAILED);
                } catch (IOException ignored) {
                    return result;
                }
            }
        }
    }

    /** Replaces the local roster and renews every surviving member's session. */
    public TeamCommandResult updateMembers(TeamMembersUpdateCommand command) {
        String requestId = command == null ? "" : command.getRequestId();
        try {
            validateMembersUpdate(command);
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error(requestId, TeamErrorCode.VALIDATION_ERROR,
                    e.getMessage());
        }
        String payloadHash = sha256(gson.toJson(command));
        synchronized (requestLocks.computeIfAbsent(requestId, ignored -> new Object())) {
            try {
                Optional<TeamOperationRecord> previous = store.loadOperation(requestId);
                if (previous.isPresent()) {
                    TeamOperationRecord record = previous.get();
                    if (record.getOperation() != TeamOperationRecord.Operation.UPDATE_MEMBERS
                            || !payloadHash.equals(record.getPayloadHash())) {
                        return TeamCommandResult.error(requestId,
                                TeamErrorCode.IDEMPOTENCY_CONFLICT,
                                "requestId already used with different operation or payload");
                    }
                    if (record.getResultSnapshot() != null
                            && !record.getResultSnapshot().trim().isEmpty()) {
                        return TeamCommandResult.fromSnapshotJson(record.getResultSnapshot());
                    }
                    return TeamCommandResult.error(requestId,
                            TeamErrorCode.VERSION_CONFLICT,
                            "Previous roster update is still being recovered");
                }
                TeamRuntime runtime = teams.get(command.getTeamId());
                if (runtime == null) {
                    return TeamCommandResult.error(requestId, TeamErrorCode.NOT_FOUND,
                            "Team not found");
                }
                runtime.getOperationLock().lock();
                try {
                    TeamDefinition current = runtime.getDefinition();
                    if (!command.getOwnerChatterId().equals(current.getOwnerChatterId())) {
                        return TeamCommandResult.error(requestId, TeamErrorCode.UNAUTHORIZED,
                                "Team does not belong to ownerChatterId");
                    }
                    if (current.isMixedPlacement()) {
                        return TeamCommandResult.error(requestId, TeamErrorCode.VALIDATION_ERROR,
                                "Mixed Team roster must be updated by its coordinator");
                    }
                    if (current.getState() != TeamState.READY || startupCoordinator == null) {
                        return TeamCommandResult.error(requestId, TeamErrorCode.TEAM_NOT_READY,
                                "Team is not READY for roster update");
                    }
                    if (command.getExpectedVersion() == null
                            || command.getExpectedVersion() != current.getVersion()) {
                        return TeamCommandResult.error(requestId, TeamErrorCode.VERSION_CONFLICT,
                                "expectedVersion does not match current Team version");
                    }
                    if (command.getMembers().size() > limits.getMaxMembersPerTeam()) {
                        return TeamCommandResult.error(requestId, TeamErrorCode.QUOTA_EXCEEDED,
                                "Team members exceed maxMembersPerTeam");
                    }
                    Set<String> desiredIds = new HashSet<>();
                    for (TeamMemberCreateSpec spec : command.getMembers()) {
                        desiredIds.add(spec.getTeamMemberId());
                    }
                    Map<String, TeamMemberDefinition> oldById = new LinkedHashMap<>();
                    for (TeamMemberDefinition member : current.getMembers()) {
                        oldById.put(member.getTeamMemberId(), member);
                        AcpClient client = clientRegistry.get(current.getTeamId(),
                                member.getTeamMemberId()).orElse(null);
                        boolean failedWithoutClient = member.getState() == TeamMemberState.ERROR
                                && client == null;
                        if (!failedWithoutClient && (member.getState() != TeamMemberState.READY
                                && member.getState() != TeamMemberState.SLEEP
                                || client == null
                                || client.getState() != AbstractAcpClient.State.READY
                                && client.getState() != AbstractAcpClient.State.SLEEP)) {
                            return TeamCommandResult.error(requestId, TeamErrorCode.MEMBER_BUSY,
                                    "Every active Team member must be READY");
                        }
                    }
                    List<TeamMemberDefinition> desired = new ArrayList<>();
                    for (TeamMemberCreateSpec spec : command.getMembers()) {
                        TeamMemberDefinition old = oldById.get(spec.getTeamMemberId());
                        if (old != null) {
                            if (!old.getSourceGroupId().equals(spec.getSourceGroupId())
                                    || !old.getSourceRobotId().equals(spec.getSourceRobotId())) {
                                throw new IllegalArgumentException(
                                        "Existing teamMemberId cannot change source");
                            }
                            desired.add(old.withRemark(spec.getRemark()).withOrder(spec.getOrder()));
                        } else {
                            desired.add(resolveMembers(Collections.singletonList(spec),
                                    current.getOwnerChatterId(), false).get(0));
                        }
                    }
                    if (current.isCaptainMode()
                            && !desiredIds.contains(current.getCaptainTeamMemberId())) {
                        throw new IllegalArgumentException("Team captain cannot be removed");
                    }
                    if (activeMemberCount() - current.getMembers().size() + desired.size()
                            > limits.getMaxTotalMembers()) {
                        return TeamCommandResult.error(requestId, TeamErrorCode.QUOTA_EXCEEDED,
                                "Team members exceed maxTotalMembers");
                    }
                    List<TeamContactRef> roster = new ArrayList<>();
                    for (TeamMemberDefinition member : desired) {
                        roster.add(TeamContactRef.from(member));
                    }
                    TeamDefinition next = current.withRosterMembers(
                            desired, roster, System.currentTimeMillis());
                    store.saveOperation(new TeamOperationRecord(requestId,
                            TeamOperationRecord.Operation.UPDATE_MEMBERS, payloadHash,
                            command.getTeamId(), TeamOperationRecord.Status.ACCEPTED,
                            null, System.currentTimeMillis(),
                            System.currentTimeMillis() + OPERATION_TTL_MILLIS));
                    store.saveTeam(next);
                    if (!runtime.publishNextDefinition(next)) {
                        throw new IOException("Team version changed before roster publish");
                    }
                    Map<String, String> failures = new LinkedHashMap<>();
                    for (TeamMemberDefinition old : current.getMembers()) {
                        if (desiredIds.contains(old.getTeamMemberId())) continue;
                        TeamTalkToDispatcher dispatcher = talkToDispatchers.get(current.getTeamId());
                        if (dispatcher != null) dispatcher.removeMember(old.getTeamMemberId());
                        if (scheduleTaskManager != null) {
                            scheduleTaskManager.cleanupOwner(ScheduleOwnerKey.team(
                                    current.getOwnerChatterId(), current.getTeamId(),
                                    old.getTeamMemberId(), old.getSourceRobotName()));
                        }
                        AcpClient removed = clientRegistry.remove(current.getTeamId(),
                                old.getTeamMemberId()).orElse(null);
                        if (removed != null) {
                            try { removed.close(); }
                            catch (IOException e) {
                                logger.warn("Removed Team member client close failed, teamId={}, memberId={}",
                                        current.getTeamId(), old.getTeamMemberId(), e);
                            }
                        }
                    }
                    for (TeamMemberDefinition member : desired) {
                        String memberId = member.getTeamMemberId();
                        try {
                            publishMemberState(runtime, memberId, TeamMemberState.STARTING, null);
                            AcpClient old = clientRegistry.remove(current.getTeamId(), memberId)
                                    .orElse(null);
                            if (old != null) {
                                try { old.close(); }
                                catch (IOException e) {
                                    logger.warn("Old Team member client close failed, teamId={}, memberId={}",
                                            current.getTeamId(), memberId, e);
                                }
                            }
                            TeamMemberDefinition starting = findMember(runtime.getDefinition(), memberId);
                            AcpClient replacement = startupCoordinator.replaceMember(
                                    runtime, starting, TeamMemberStartOptions.newSession());
                            if (!clientRegistry.register(current.getTeamId(), memberId, replacement)) {
                                replacement.close();
                                throw new IllegalStateException("duplicate Team member client");
                            }
                            publishMemberState(runtime, memberId, TeamMemberState.READY, null);
                        } catch (Exception e) {
                            failures.put(memberId, safeMessage(e));
                            try {
                                publishMemberState(runtime, memberId, TeamMemberState.ERROR,
                                        TeamError.of(TeamErrorCode.CLIENT_START_FAILED,
                                                safeMessage(e), true));
                            } catch (Exception ignored) { }
                        }
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("team", runtime.getDefinition());
                    data.put("sessionFailures", failures);
                    data.put("sessionsCreated", desired.size() - failures.size());
                    TeamCommandResult result = TeamCommandResult.success(requestId,
                            failures.isEmpty() ? "UPDATED" : "PARTIAL",
                            failures.isEmpty() ? "Team roster and sessions updated"
                                    : "Team roster updated with member session failures",
                            runtime.getDefinition().getVersion(), data);
                    store.saveOperation(new TeamOperationRecord(requestId,
                            TeamOperationRecord.Operation.UPDATE_MEMBERS, payloadHash,
                            command.getTeamId(), TeamOperationRecord.Status.SUCCEEDED,
                            gson.toJson(result), System.currentTimeMillis(),
                            System.currentTimeMillis() + OPERATION_TTL_MILLIS));
                    publishEvent(runtime, TeamEventType.TEAM_MEMBERS_UPDATED, data);
                    return result;
                } finally {
                    runtime.getOperationLock().unlock();
                }
            } catch (TeamSourceResolutionException e) {
                return TeamCommandResult.error(requestId, e.getCode(), e.getMessage());
            } catch (TeamStore.VersionConflictException e) {
                return TeamCommandResult.error(requestId, TeamErrorCode.VERSION_CONFLICT,
                        e.getMessage());
            } catch (IllegalArgumentException e) {
                return TeamCommandResult.error(requestId, TeamErrorCode.VALIDATION_ERROR,
                        e.getMessage());
            } catch (Exception e) {
                return TeamCommandResult.error(requestId, TeamErrorCode.INTERNAL_ERROR,
                        safeMessage(e));
            }
        }
    }

    public TeamCommandResult list(TeamQuery query) {
        try {
            ensureOpen();
            validateQuery(query, false);
            List<TeamDefinition> definitions = snapshotDefinitions();
            List<TeamDefinition> visible = new ArrayList<>();
            long snapshotVersion = 0L;
            for (TeamDefinition definition : definitions) {
                if (query.getOwnerChatterId().equals(definition.getOwnerChatterId())) {
                    if (!grantsActive(definition)) {
                        return TeamCommandResult.error("",
                                TeamErrorCode.TEAM_GRANT_REVOKED,
                                "remote Team sharing grant has been revoked");
                    }
                    visible.add(definition);
                    snapshotVersion = Math.max(snapshotVersion, definition.getVersion());
                }
            }
            logger.info("team_query action=list ownerChatterId={} totalTeams={}"
                            + " matchedTeams={} matchedTeamIds={}",
                    query.getOwnerChatterId(), definitions.size(), visible.size(),
                    visible.stream().map(TeamDefinition::getTeamId)
                            .collect(java.util.stream.Collectors.toList()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("teams", visible);
            data.put("snapshotVersion", snapshotVersion);
            return TeamCommandResult.success("", "OK", "Team list loaded",
                    snapshotVersion, data);
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error("", TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        }
    }

    public TeamCommandResult get(TeamQuery query) {
        try {
            ensureOpen();
            validateQuery(query, true);
            Optional<TeamRuntime> runtime = getRuntime(query.getTeamId());
            if (!runtime.isPresent()) {
                return TeamCommandResult.error("", TeamErrorCode.NOT_FOUND, "Team not found");
            }
            TeamDefinition definition = runtime.get().getDefinition();
            if (!query.getOwnerChatterId().equals(definition.getOwnerChatterId())) {
                return TeamCommandResult.error("", TeamErrorCode.UNAUTHORIZED,
                        "Team does not belong to ownerChatterId");
            }
            if (!grantsActive(definition)) {
                return TeamCommandResult.error("", TeamErrorCode.TEAM_GRANT_REVOKED,
                        "remote Team sharing grant has been revoked");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("team", definition);
            data.put("latestEventSeq", runtime.get().getLatestEventSeq());
            return TeamCommandResult.success("", "OK", "Team loaded",
                    definition.getVersion(), data);
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error("", TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        }
    }

    public TeamClientRegistry getClientRegistry() {
        return clientRegistry;
    }

    /**
     * 获取当前 Team 唯一的 talkTo dispatcher。dispatcher 共享 member inbox，
     * 但路由键始终是 teamId + teamMemberId，不进入普通 robot registry。
     */
    public TeamTalkToDispatcher getOrCreateTalkToDispatcher(String teamId) {
        ensureOpen();
        TeamRuntime runtime = teams.get(teamId);
        if (runtime == null) {
            throw new IllegalArgumentException("Team runtime not found");
        }
        TeamTalkToDispatcher dispatcher = talkToDispatchers.computeIfAbsent(teamId,
                ignored -> new TeamTalkToDispatcher(
                        runtime, clientRegistry, eventSink, this::onMemberState,
                        () -> grantsActive(runtime.getDefinition())));
        ExternalTalkToGateway gateway = externalGateway;
        if (gateway != null) dispatcher.registerExternalGateway(gateway);
        return dispatcher;
    }

    /** Makes opaque channel reply routes available to current and future Team dispatchers. */
    public synchronized void registerExternalGateway(ExternalTalkToGateway gateway) {
        if (gateway == null) return;
        externalGateway = gateway;
        for (TeamTalkToDispatcher dispatcher : talkToDispatchers.values()) {
            dispatcher.registerExternalGateway(gateway);
        }
    }

    public synchronized void unregisterExternalGateway(ExternalTalkToGateway gateway) {
        if (gateway == null || externalGateway != gateway) return;
        externalGateway = null;
        for (TeamTalkToDispatcher dispatcher : talkToDispatchers.values()) {
            dispatcher.unregisterExternalGateway(gateway);
        }
    }

    /**
     * Team 删除/失败回收的显式扩展点；FT-CMD-501 的 delete 状态机将调用同一入口。
     */
    public void cleanupTalkTo(String teamId) {
        TeamTalkToDispatcher dispatcher = talkToDispatchers.remove(teamId);
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    public void setScheduleCleanup(Consumer<String> cleanup) {
        this.scheduleCleanup = cleanup == null ? ignored -> { } : cleanup;
    }

    public void setScheduleTaskManager(ScheduleTaskManager scheduleTaskManager) {
        this.scheduleTaskManager = scheduleTaskManager;
    }

    public void setScheduleOrphanCleanup(
            Consumer<Set<String>> cleanup, IntSupplier ownerCount) {
        setScheduleOrphanCleanup(cleanup, ownerCount, ignored -> 0);
    }

    public void setScheduleOrphanCleanup(
            Consumer<Set<String>> cleanup, IntSupplier ownerCount,
            ToIntFunction<String> teamOwnerCount) {
        this.scheduleOrphanCleanup =
                cleanup == null ? ignored -> { } : cleanup;
        this.scheduleOwnerCount =
                ownerCount == null ? () -> 0 : ownerCount;
        this.scheduleTeamOwnerCount =
                teamOwnerCount == null ? ignored -> 0 : teamOwnerCount;
    }

    /** Team 复用来源 robot MemoryManager 的手动 dream 路由。 */
    public void setMemoryDreamTrigger(BiPredicate<String, String> trigger) {
        this.memoryDreamTrigger = trigger == null
                ? (sourceGroupId, workspacePath) -> false : trigger;
    }

    /**
     * delete 状态机的 schedule/talkTo 统一清理入口。
     * 普通 shutdown 不调用，避免误删需在重启后恢复的持久任务。
     */
    public void cleanupEphemeralResourcesForDelete(String teamId) {
        cleanupTalkTo(teamId);
        scheduleStateMismatchSince.keySet().removeIf(
                key -> key.startsWith(teamId + ":"));
        scheduleCleanup.accept(teamId);
    }

    /**
     * 执行 Team owner 的定时任务：只替换目标 member session，完整重跑
     * initializer 后以 schedule execution 选项发送，避免任务递归创建任务。
     */
    public boolean executeScheduledPrompt(ScheduleOwnerKey owner, String taskId,
                                          String prompt) {
        return executeScheduledPrompt(owner, taskId, null, prompt, null, null);
    }

    public boolean executeScheduledPrompt(ScheduleOwnerKey owner, String taskId,
                                          String groupName, String prompt) {
        return executeScheduledPrompt(owner, taskId, groupName, prompt, null, null);
    }

    public boolean executeScheduledPrompt(ScheduleOwnerKey owner, String taskId,
                                          String groupName, String prompt,
                                          AuthPrincipalContext authPrincipalContext) {
        return executeScheduledPrompt(owner, taskId, groupName, prompt,
                authPrincipalContext, null);
    }

    public boolean executeScheduledPrompt(ScheduleOwnerKey owner, String taskId,
                                          String groupName, String prompt,
                                          AuthPrincipalContext authPrincipalContext,
                                          ChannelDeliveryContext channelDeliveryContext) {
        if (owner == null || !owner.isTeam() || prompt == null
                || prompt.trim().isEmpty() || closed.get()
                || startupCoordinator == null) {
            logScheduleRejection(owner, taskId, "INVALID_OR_CLOSED",
                    null, null, null);
            return false;
        }
        TeamRuntime runtime = teams.get(owner.getTeamId());
        if (runtime == null) {
            logScheduleRejection(owner, taskId, "TEAM_RUNTIME_MISSING",
                    null, null, null);
            return false;
        }
        runtime.getOperationLock().lock();
        try {
            TeamDefinition team = runtime.getDefinition();
            if (!runtime.isAcceptingRequests()) {
                logScheduleRejection(owner, taskId, "TEAM_NOT_ACCEPTING",
                        team, null, null);
                return false;
            }
            if (team.getState()
                    != com.mola.cmd.proxy.app.acp.team.model.TeamState.READY) {
                logScheduleRejection(owner, taskId, "TEAM_NOT_READY",
                        team, null, null);
                return false;
            }
            if (!team.getOwnerChatterId().equals(owner.getOwnerId())) {
                logScheduleRejection(owner, taskId, "OWNER_MISMATCH",
                        team, null, null);
                return false;
            }
            if (!grantsActive(team)) {
                logScheduleRejection(owner, taskId, "GRANT_INACTIVE",
                        team, null, null);
                return false;
            }
            TeamMemberDefinition member =
                    findMember(team, owner.getTeamMemberId());
            if (member == null) {
                logScheduleRejection(owner, taskId, "MEMBER_MISSING",
                        team, null, null);
                return false;
            }
            AcpClient old = clientRegistry.get(
                    team.getTeamId(), member.getTeamMemberId()).orElse(null);
            if (old != null && member.getState() == TeamMemberState.SLEEP
                    && old.getState() == AbstractAcpClient.State.SLEEP) {
                publishMemberState(runtime, member.getTeamMemberId(),
                        TeamMemberState.STARTING, null);
                old.wakeIfSleeping();
                publishMemberState(runtime, member.getTeamMemberId(),
                        TeamMemberState.READY, null);
                team = runtime.getDefinition();
                member = findMember(team, owner.getTeamMemberId());
            }
            String mismatchKey = scheduleMismatchKey(
                    team.getTeamId(), member.getTeamMemberId());
            if (member.getState()
                    != com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.READY) {
                if (member.getState()
                        == com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.BUSY
                        && old != null
                        && old.getState() == AbstractAcpClient.State.READY) {
                    long now = System.currentTimeMillis();
                    Long firstObserved = scheduleStateMismatchSince.putIfAbsent(
                            mismatchKey, now);
                    long mismatchSince = firstObserved == null ? now : firstObserved;
                    if (now - mismatchSince < SCHEDULE_STATE_MISMATCH_GRACE_MILLIS) {
                        logScheduleRejection(owner, taskId,
                                "MEMBER_BUSY_CLIENT_READY_GRACE", team, member, old);
                        return false;
                    }
                    logger.warn("schedule_gate reconciliation=member_ready"
                                    + " taskId={} teamId={} memberId={} sessionId={}"
                                    + " memberState={} clientState={} mismatchMillis={}",
                            taskId, team.getTeamId(), member.getTeamMemberId(),
                            old.getSessionId(), member.getState(), old.getState(),
                            now - mismatchSince);
                    publishMemberState(runtime, member.getTeamMemberId(),
                            com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.READY,
                            null);
                    team = runtime.getDefinition();
                    member = findMember(team, owner.getTeamMemberId());
                    scheduleStateMismatchSince.remove(mismatchKey);
                } else {
                    scheduleStateMismatchSince.remove(mismatchKey);
                    logScheduleRejection(owner, taskId, "MEMBER_NOT_READY",
                            team, member, old);
                    return false;
                }
            } else {
                scheduleStateMismatchSince.remove(mismatchKey);
            }
            if (old == null) {
                logScheduleRejection(owner, taskId, "CLIENT_MISSING",
                        team, member, null);
                return false;
            }
            if (old.getState() != AbstractAcpClient.State.READY) {
                logScheduleRejection(owner, taskId, "CLIENT_NOT_READY",
                        team, member, old);
                return false;
            }

            String boundSessionId = scheduleTaskManager == null ? null
                    : scheduleTaskManager.findGroupSession(owner, groupName);
            AcpClient replacement = old;
            if (boundSessionId == null || !boundSessionId.equals(old.getSessionId())) {
                publishMemberState(runtime, member.getTeamMemberId(),
                        com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.STARTING, null);
                clientRegistry.remove(team.getTeamId(), member.getTeamMemberId());
                try {
                    old.close();
                } catch (IOException ignored) {
                }

                TeamMemberDefinition starting = findMember(
                        runtime.getDefinition(), member.getTeamMemberId());
                TeamMemberStartOptions startOptions = boundSessionId == null
                        ? TeamMemberStartOptions.newSession()
                        : TeamMemberStartOptions.restore(boundSessionId);
                replacement = startupCoordinator.replaceMember(
                        runtime, starting, startOptions);
                if (!clientRegistry.register(
                        team.getTeamId(), member.getTeamMemberId(), replacement)) {
                    replacement.close();
                    throw new IOException("duplicate Team schedule replacement client");
                }
                if (boundSessionId == null && groupName != null
                        && !groupName.trim().isEmpty() && scheduleTaskManager != null) {
                    scheduleTaskManager.bindGroupSession(
                            owner, groupName, replacement.getSessionId());
                }
            }
            publishMemberState(runtime, member.getTeamMemberId(),
                    com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.READY, null);
            publishMemberState(runtime, member.getTeamMemberId(),
                    com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.BUSY, null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("eventType", "SCHEDULE_TRIGGER");
            data.put("taskId", taskId);
            data.put("owner", owner);
            data.put("sessionId", replacement.getSessionId());
            replacement.getHistoryManager().addEventMessage(
                    TeamEventType.SCHEDULE_EVENT.name(),
                    gson.toJsonTree(data).getAsJsonObject());
            eventSink.publish(TeamEventEnvelope.next(runtime,
                    member.getTeamMemberId(), member.getAcpClientId(),
                    TeamEventType.SCHEDULE_EVENT, data));
            replacement.send(prompt, null,
                    replacement.promptOptionsForScheduleExecution(
                            authPrincipalContext, channelDeliveryContext)
                            .setScheduleExecutionId(scheduleTaskManager == null ? null
                                    : scheduleTaskManager.activeExecutionId(owner, taskId)));
            return true;
        } catch (Exception error) {
            logger.warn("schedule_gate result=error taskId={} owner={} message={}",
                    taskId, owner, safeMessage(error), error);
            TeamError teamError = TeamError.of(
                    TeamErrorCode.CLIENT_START_FAILED, safeMessage(error), true);
            try {
                publishMemberState(runtime, owner.getTeamMemberId(),
                        com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.ERROR,
                        teamError);
            } catch (Exception ignored) {
            }
            return false;
        } finally {
            runtime.getOperationLock().unlock();
        }
    }

    /** Observation notifications use the current member session without replacing it. */
    public boolean sendObservation(ScheduleOwnerKey owner, String prompt) throws IOException {
        TeamRuntime runtime = teams.get(owner.getTeamId());
        if (runtime == null || closed.get()) return false;
        runtime.getOperationLock().lock();
        try {
            TeamDefinition team = runtime.getDefinition();
            if (!runtime.isAcceptingRequests() || team.getState() != TeamState.READY
                    || !team.getOwnerChatterId().equals(owner.getOwnerId()) || !grantsActive(team)) return false;
            TeamMemberDefinition member = findMember(team, owner.getTeamMemberId());
            AcpClient client = clientRegistry.get(owner.getTeamId(), owner.getTeamMemberId()).orElse(null);
            if (member == null || client == null || client.getRobotParam() == null
                    || !client.getRobotParam().isObservationEnabled()) return false;
            if (client.getState() == AbstractAcpClient.State.SLEEP) {
                client.wakeIfSleeping();
                publishMemberState(runtime, owner.getTeamMemberId(), TeamMemberState.READY, null);
            }
            if (client.getState() != AbstractAcpClient.State.READY) return false;
            publishMemberState(runtime, owner.getTeamMemberId(), TeamMemberState.BUSY, null);
            boolean accepted = client.trySendObservation(prompt);
            if (!accepted) publishMemberState(runtime, owner.getTeamMemberId(), TeamMemberState.READY, null);
            return accepted;
        } finally { runtime.getOperationLock().unlock(); }
    }

    public TeamCommandResult send(String requestId, TeamMemberCommand command) {
        return sendWithOptions(requestId, command, null);
    }

    public TeamCommandResult sendTask(String requestId, TeamMemberCommand command,
                                      com.mola.cmd.proxy.app.acp.acpclient.PromptOptions options) {
        return sendWithOptions(requestId, command, options);
    }

    /** Interrupts only a BUSY turn whose task identity matches the control event. */
    public TeamCommandResult interruptTask(String requestId, TeamMemberCommand command,
                                           String taskId) {
        MemberRoute route;
        try {
            route = requireMemberRoute(command, false);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
        route.runtime.getOperationLock().lock();
        try {
            route = requireMemberRoute(command, false);
            AcpClient client = requireCancellableClient(route);
            if (!client.isActiveTask(taskId)) {
                return TeamCommandResult.error(requestId, TeamErrorCode.MEMBER_BUSY,
                        "Busy Team turn does not belong to this task");
            }
            client.cancelForQueuedWork();
            return TeamCommandResult.success(requestId, "TASK_INTERRUPT_REQUESTED",
                    "Matching task turn cancellation sent",
                    route.runtime.getDefinition().getVersion(),
                    memberData(route.runtime, route.member, client));
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        } catch (Exception e) {
            return TeamCommandResult.error(requestId, TeamErrorCode.INTERNAL_ERROR,
                    safeMessage(e));
        } finally {
            route.runtime.getOperationLock().unlock();
        }
    }

    private TeamCommandResult sendWithOptions(String requestId, TeamMemberCommand command,
                                              com.mola.cmd.proxy.app.acp.acpclient.PromptOptions options) {
        MemberRoute route;
        try {
            route = requireMemberRoute(command, true);
            ensureGrantActive(route.team);
            requireText(command.getMessage(), "message");
            validateFiles(command.getFiles());
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
        route.runtime.getOperationLock().lock();
        try {
            AcpClient client = requireReadyClient(route);
            publishMemberState(route.runtime, route.member.getTeamMemberId(),
                    com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.BUSY, null);
            com.mola.cmd.proxy.app.acp.acpclient.PromptOptions effective = options == null
                    ? com.mola.cmd.proxy.app.acp.acpclient.PromptOptions.defaults() : options;
            effective.setClientMessageId(command.getClientMessageId());
            client.send(command.getMessage(), command.getFiles(), effective);
            if (command.getClientMessageId() != null) {
                Map<String, Object> accepted = new LinkedHashMap<>();
                accepted.put("messageId", command.getClientMessageId());
                accepted.put("revision", 1L);
                accepted.put("content", command.getMessage());
                accepted.put("sessionId", client.getSessionId());
                List<Map<String, Object>> attachments = new ArrayList<>();
                if (command.getFiles() != null) for (Map<String, String> file : command.getFiles()) {
                    for (String name : file.keySet()) {
                        Map<String, Object> attachment = new LinkedHashMap<>();
                        attachment.put("fileName", name);
                        attachments.add(attachment);
                    }
                }
                accepted.put("attachments", attachments);
                eventSink.publish(TeamEventEnvelope.next(route.runtime,
                        route.member.getTeamMemberId(), route.member.getAcpClientId(),
                        TeamEventType.USER_MESSAGE_ACCEPTED, accepted));
            }
            TeamMemberDefinition busy = findMember(
                    route.runtime.getDefinition(), route.member.getTeamMemberId());
            Map<String, Object> data = memberData(route.runtime, busy, client);
            data.put("queued", true);
            return TeamCommandResult.success(requestId, "QUEUED",
                    "Team message queued", route.runtime.getDefinition().getVersion(), data);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        } catch (IOException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.INTERNAL_ERROR, safeMessage(e));
        } finally {
            route.runtime.getOperationLock().unlock();
        }
    }

    public TeamCommandResult cancel(String requestId, TeamMemberCommand command) {
        MemberRoute route;
        try {
            route = requireMemberRoute(command, false);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
        route.runtime.getOperationLock().lock();
        try {
            // 与 delete/session replace 使用同一屏障；锁内重查，避免检查后进入 DELETING。
            route = requireMemberRoute(command, false);
            AcpClient client = requireCancellableClient(route);
            if (client.getState() == AbstractAcpClient.State.SLEEP) {
                return TeamCommandResult.success(requestId, "NOOP",
                        "Team member is sleeping and has no active prompt",
                        route.runtime.getDefinition().getVersion(),
                        memberData(route.runtime, route.member, client));
            }
            client.cancel();
            return TeamCommandResult.success(requestId, "OK",
                    "Team prompt cancellation sent", route.runtime.getDefinition().getVersion(),
                    memberData(route.runtime, route.member, client));
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        } catch (Exception e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.INTERNAL_ERROR, safeMessage(e));
        } finally {
            route.runtime.getOperationLock().unlock();
        }
    }

    /** 手动触发与来源普通 robot 完全相同的 Memory Dream。 */
    public TeamCommandResult memoryDream(String requestId, TeamMemberCommand command) {
        MemberRoute route;
        try {
            route = requireMemberRoute(command, true);
            ensureGrantActive(route.team);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
        route.runtime.getOperationLock().lock();
        try {
            AcpClient client = requireExistingClient(route);
            if (client.getState() != AbstractAcpClient.State.READY
                    && client.getState() != AbstractAcpClient.State.SLEEP) {
                return TeamCommandResult.error(requestId, TeamErrorCode.MEMBER_BUSY,
                        "Team member memory cannot be organized in the current state");
            }
            boolean triggered = memoryDreamTrigger.test(
                    route.member.getSourceGroupId(), client.getWorkspacePath());
            if (!triggered) {
                return TeamCommandResult.error(requestId,
                        TeamErrorCode.MEMORY_NOT_ENABLED,
                        "Source robot does not have writable memory enabled");
            }
            Map<String, Object> data = memberData(
                    route.runtime, route.member, client);
            data.put("triggered", true);
            data.put("memoryOwnerSourceGroupId", route.member.getSourceGroupId());
            return TeamCommandResult.success(requestId, "OK",
                    "Team member memory dream triggered",
                    route.runtime.getDefinition().getVersion(), data);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        } catch (RuntimeException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.INTERNAL_ERROR, safeMessage(e));
        } finally {
            route.runtime.getOperationLock().unlock();
        }
    }

    public TeamCommandResult listSessions(String requestId, TeamMemberCommand command) {
        try {
            MemberRoute route = requireMemberRoute(command, false);
            ensureGrantActive(route.team);
            AcpClient client = requireExistingClient(route);
            int limit = command.getLimit() == null ? 7 : command.getLimit();
            if (limit < 1 || limit > 50) {
                throw new IllegalArgumentException("limit must be between 1 and 50");
            }
            List<Map<String, Object>> sessions = new ArrayList<>();
            for (com.mola.cmd.proxy.app.acp.acpclient.context.ConversationHistoryManager.SessionSummary
                    summary : client.getHistoryManager().listRecentSessions(limit)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sessionId", summary.getSessionId());
                item.put("preview", summary.getPreview());
                item.put("lastModified", summary.getLastModified());
                item.put("current", summary.getSessionId().equals(client.getSessionId()));
                sessions.add(item);
            }
            Map<String, Object> data = memberData(route.runtime, route.member, client);
            data.put("sessions", sessions);
            return TeamCommandResult.success(requestId, "OK",
                    "Team sessions listed", route.runtime.getDefinition().getVersion(), data);
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
    }

    /** Returns the selected Team member session as UI-safe structured messages. */
    public TeamCommandResult getSessionHistory(String requestId, TeamMemberCommand command) {
        try {
            MemberRoute route = requireMemberRoute(command, false);
            // Pair the session identity and history across wake/new-session transitions.
            route.runtime.getOperationLock().lock();
            try {
                return readSessionHistory(requestId, command);
            } finally {
                route.runtime.getOperationLock().unlock();
            }
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
    }

    private TeamCommandResult readSessionHistory(String requestId, TeamMemberCommand command) {
        try {
            MemberRoute route = requireMemberRoute(command, false);
            ensureGrantActive(route.team);
            AcpClient client = requireExistingClient(route);
            String sessionId = command.getSessionId();
            if (sessionId == null || sessionId.trim().isEmpty()) {
                sessionId = client.getSessionId();
            }
            if (command.getLimit() != null) {
                com.google.gson.JsonObject legacy = gson.toJsonTree(command).getAsJsonObject();
                legacy.remove("limit"); legacy.remove("before"); legacy.addProperty("sessionId", sessionId);
                TeamMemberCommand fullCommand = gson.fromJson(legacy, TeamMemberCommand.class);
                com.alibaba.fastjson.JSONObject page = client.getHistoryManager().uiHistoryPage(sessionId, command.getBefore(), command.getLimit(), () -> {
                    TeamCommandResult full = readSessionHistory(requestId, fullCommand);
                    if (!full.isAccepted()) throw new IllegalStateException(full.getMessage());
                    com.alibaba.fastjson.JSONObject snapshot = com.alibaba.fastjson.JSON.parseObject(full.toResultMap().get("data"));
                    com.alibaba.fastjson.JSONArray visible = new com.alibaba.fastjson.JSONArray();
                    Map<String, Integer> visibleRows = new HashMap<>();
                    Set<String> visibleEventIds = new HashSet<>();
                    com.alibaba.fastjson.JSONArray messages = snapshot.getJSONArray("messages");
                    for (int i = 0; i < messages.size(); i++) {
                        com.alibaba.fastjson.JSONObject row = messages.getJSONObject(i);
                        String kind = row.getString("kind"), type = row.getString("eventType"), role = row.getString("role");
                        com.alibaba.fastjson.JSONObject payload = row.getJSONObject("payload");
                        if (payload != null && payload.getString("messageId") != null) row.put("messageId", payload.getString("messageId"));
                        if (payload != null && payload.containsKey("revision")) row.put("revision", payload.getLongValue("revision"));
                        boolean taskCard = payload != null && ("STARWEAVE_TASK".equals(payload.getString("cardType")) || String.valueOf(payload.getString("eventType")).startsWith("TASK_"));
                        if ("TEAM_EVENT".equals(kind) && ("TALK_TO_ROUTE_REQUEST".equals(type) || !taskCard && (type == null || !(type.startsWith("TASK_") || type.startsWith("TALK_TO_") || java.util.Arrays.asList("TOOL_CALL", "SUB_AGENT_EVENT", "SCHEDULE_EVENT", "COMPACTION_EVENT", "LIFECYCLE_EVENT", "MESSAGE_ERROR").contains(type))))) continue;
                        if ("ASSISTANT".equals(role) && (row.getString("content") == null || row.getString("content").trim().isEmpty())) continue;
                        String eventId = payload == null ? null : payload.getString("eventId");
                        if (eventId != null && !visibleEventIds.add(eventId)) continue;
                        String tool = payload == null ? row.getString("toolCallId") : payload.getString("toolCallId");
                        if (tool != null && !tool.isEmpty()) row.put("messageId", "tool:" + tool);
                        if (row.getString("messageId") == null) row.put("messageId", "history:" + i);
                        String id = row.getString("messageId"); Integer previous = visibleRows.get(id);
                        if (previous == null) { visibleRows.put(id, visible.size()); visible.add(row); }
                        else if (row.getLongValue("revision") >= visible.getJSONObject(previous).getLongValue("revision")) visible.set(previous, row);
                    }
                    snapshot.remove("messages"); snapshot.put("items", visible); return snapshot;
                });
                page.put("messages", page.remove("items"));
                return TeamCommandResult.success(requestId, "OK", "Team session history page loaded", route.team.getVersion(), page);
            }
            List<com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage> history =
                    client.getHistoryManager().getUiHistory(sessionId);
            Set<String> eventTypes = new HashSet<>();
            Set<String> receivedTalkTo = new HashSet<>();
            Set<String> channelMessages = new HashSet<>();
            Set<String> schedulePrompts = new HashSet<>();
            Map<String, Integer> lastToolEvents = new HashMap<>();
            Map<String, Integer> firstToolEvents = new HashMap<>();
            for (int historyIndex = 0; historyIndex < history.size(); historyIndex++) {
                com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage message =
                        history.get(historyIndex);
                if (message.getRole()
                        != com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.Role.EVENT) {
                    continue;
                }
                String type = message.getEventType();
                com.google.gson.JsonObject payload = message.getEventData();
                eventTypes.add(type);
                if (TeamEventType.TOOL_CALL.name().equals(type)) {
                    String toolCallId = jsonText(payload, "toolCallId");
                    if (!toolCallId.isEmpty()) {
                        lastToolEvents.put(toolCallId, historyIndex);
                        firstToolEvents.putIfAbsent(toolCallId, historyIndex);
                    }
                } else if (TeamEventType.TALK_TO_RECEIVE.name().equals(type)) {
                    String content = jsonText(payload, "content");
                    String sender = jsonText(payload, "senderTeamMemberId");
                    if (sender.isEmpty()) sender = jsonText(payload, "robotName");
                    if (jsonText(payload, "cardType").equals("CHANNEL_TALK_TO")
                            || sender.startsWith("channel:")) {
                        channelMessages.add(content);
                    } else {
                        receivedTalkTo.add(sender + "\n" + content);
                    }
                } else if (TeamEventType.SCHEDULE_EVENT.name().equals(type)
                        && "SCHEDULE_EXECUTE".equals(jsonText(payload, "eventType"))) {
                    schedulePrompts.add(jsonText(payload, "detail"));
                }
            }

            List<Map<String, Object>> messages = new ArrayList<>();
            for (int historyIndex = 0; historyIndex < history.size(); historyIndex++) {
                com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage message =
                        history.get(historyIndex);
                if (TeamEventType.TOOL_CALL.name().equals(message.getEventType())) {
                    String toolId = jsonText(message.getEventData(), "toolCallId");
                    if (!toolId.isEmpty()) {
                        if (!Integer.valueOf(historyIndex).equals(firstToolEvents.get(toolId))) continue;
                        // 更新保留在首次调用的位置，分页游标不随完成回调移动。
                        message = history.get(lastToolEvents.get(toolId));
                    }
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("role", message.getRole().name());
                value.put("messageId", message.getMessageId());
                value.put("revision", message.getRevision());
                if (message.getRole()
                        == com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.Role.EVENT) {
                    value.put("kind", "TEAM_EVENT");
                    value.put("eventType", message.getEventType());
                    String eventMessageId = jsonText(message.getEventData(), "messageId");
                    if (!eventMessageId.isEmpty()) value.put("messageId", eventMessageId);
                    if (message.getEventData().has("revision")) value.put("revision", message.getEventData().get("revision").getAsLong());
                    value.put("payload", gson.fromJson(
                            message.getEventData(), Map.class));
                } else if (message.getRole()
                        == com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage.Role.TOOL) {
                    if (lastToolEvents.containsKey(message.getToolCallId())) continue;
                    TeamHistoryActionTool action = TeamHistoryActionTool.parse(message);
                    if (action != null) {
                        if (eventTypes.contains(action.persistedEventType)) continue;
                        value.put("kind", "TEAM_EVENT");
                        value.put("eventType", action.persistedEventType);
                        value.put("payload", action.payload);
                    } else {
                        value.put("toolCallId", message.getToolCallId());
                        value.put("toolName", message.getToolName());
                        value.put("status", message.getStatus());
                        value.put("rawInput", message.getRawInput());
                        value.put("rawOutput", message.getRawOutput());
                    }
                } else {
                    String content = message.getContent();
                    if (!message.isVisibleUserMessage()
                            || TeamHistoryTaskPrompt.isInternal(content)) {
                        continue;
                    }
                    TeamHistoryTalkTo talkTo = TeamHistoryTalkTo.parse(content);
                    if (talkTo != null) {
                        if (receivedTalkTo.contains(
                                talkTo.senderTeamMemberId + "\n" + talkTo.content)) continue;
                        value.put("kind", "TEAM_TALK_TO");
                        value.put("senderTeamMemberId", talkTo.senderTeamMemberId);
                        value.put("content", talkTo.content);
                    } else {
                        TeamHistoryChannelMessage channel =
                                TeamHistoryChannelMessage.parse(content);
                        if (channel != null) {
                            if (channelMessages.contains(channel.content)) continue;
                            value.put("kind", "TEAM_EVENT");
                            value.put("eventType", TeamEventType.TALK_TO_RECEIVE.name());
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("eventType", TeamEventType.TALK_TO_RECEIVE.name());
                            payload.put("direction", "RECEIVE");
                            payload.put("robotName", channel.label);
                            payload.put("content", channel.content);
                            payload.put("cardType", "CHANNEL_TALK_TO");
                            value.put("payload", payload);
                        } else if (schedulePrompts.contains(content)) {
                            continue;
                        } else {
                            value.put("content", content);
                            if (!message.getAttachments().isEmpty()) {
                                List<Map<String, Object>> attachments = new ArrayList<>();
                                for (String fileName : message.getAttachments()) {
                                    Map<String, Object> attachment = new LinkedHashMap<>();
                                    attachment.put("fileName", fileName);
                                    attachments.add(attachment);
                                }
                                value.put("attachments", attachments);
                            }
                        }
                    }
                }
                messages.add(value);
            }
            Map<String, Object> data = memberData(route.runtime, route.member, client);
            data.put("sessionId", sessionId);
            data.put("messages", messages);
            return TeamCommandResult.success(requestId, "OK",
                    "Team session history loaded", route.team.getVersion(), data);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
    }

    private static String jsonText(com.google.gson.JsonObject value, String key) {
        if (value == null || !value.has(key) || value.get(key).isJsonNull()) return "";
        try {
            return value.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return value.get(key).toString();
        }
    }

    /** Recognizes task execution prompts persisted before USER origins were recorded. */
    static final class TeamHistoryTaskPrompt {
        private static final String MARKER = "[Starweave Task]\n";

        static boolean isInternal(String value) {
            if (value == null) return false;
            String normalized = value.trim();
            return normalized.startsWith(MARKER)
                    && normalized.contains("\neventId: ")
                    && normalized.contains("\neventType: ")
                    && normalized.contains("\ntaskId: ");
        }
    }

    /** UI projection for persisted internal Team prompts; never expose harness text as chat. */
    static final class TeamHistoryTalkTo {
        private static final String MARKER =
                "📨 [Fast Team 路由消息] 发送者 memberId: ";
        private static final String CONTENT_START =
                "以下消息由当前 Fast Team 的严格队内路由投递，发送者身份已经过验证：\n\n";
        private static final String CONTENT_END = "\n\n─── 回复方式 ───";
        final String senderTeamMemberId;
        final String content;

        private TeamHistoryTalkTo(String senderTeamMemberId, String content) {
            this.senderTeamMemberId = senderTeamMemberId;
            this.content = content;
        }

        static TeamHistoryTalkTo parse(String value) {
            if (value == null) return null;
            int marker = value.indexOf(MARKER);
            int body = value.indexOf(CONTENT_START);
            if (marker < 0 || body < 0) return null;
            int senderStart = marker + MARKER.length();
            int senderEnd = value.indexOf('\n', senderStart);
            int contentStart = body + CONTENT_START.length();
            int contentEnd = value.indexOf(CONTENT_END, contentStart);
            if (senderEnd <= senderStart || contentEnd < contentStart) return null;
            String sender = value.substring(senderStart, senderEnd).trim();
            if (sender.isEmpty()) return null;
            return new TeamHistoryTalkTo(sender,
                    value.substring(contentStart, contentEnd));
        }
    }

    /** Backward-compatible projection for Team sessions saved before EVENT history. */
    static final class TeamHistoryActionTool {
        final String persistedEventType;
        final Map<String, Object> payload;

        private TeamHistoryActionTool(String persistedEventType,
                                      Map<String, Object> payload) {
            this.persistedEventType = persistedEventType;
            this.payload = payload;
        }

        static TeamHistoryActionTool parse(
                com.mola.cmd.proxy.app.acp.acpclient.context.ContextMessage message) {
            String name = message.getToolName();
            if (name == null) return null;
            String normalized = name.toLowerCase(Locale.ROOT).replace('_', '-');
            if (!normalized.contains("acp-harness")
                    && !normalized.contains("cmd-proxy")) return null;
            Map<String, Object> payload = new LinkedHashMap<>();
            String output = firstString(message.getRawOutput(), "text");
            if (normalized.contains("talk-to")) {
                String target = firstString(message.getRawInput(), "target");
                String content = firstString(message.getRawInput(), "content");
                payload.put("eventType", TeamEventType.TALK_TO_SEND.name());
                payload.put("direction", "SEND");
                payload.put("targetTeamMemberId", target);
                payload.put("content", content);
                payload.put("delivery", legacyTalkToDelivery(output));
                return new TeamHistoryActionTool(
                        TeamEventType.TALK_TO_SEND.name(), payload);
            }
            if (normalized.contains("dispatch-subagent")) {
                payload.put("eventType", "DISPATCH_COMPLETE");
                payload.put("detail", output.isEmpty()
                        ? "子 Agent 调用已完成" : output);
                return new TeamHistoryActionTool(
                        TeamEventType.SUB_AGENT_EVENT.name(), payload);
            }
            if (normalized.contains("schedule-task")
                    || normalized.contains("manage-schedule")) {
                payload.put("eventType", normalized.contains("schedule-task")
                        ? "SCHEDULE_CREATE" : "SCHEDULE_MANAGE");
                payload.put("detail", output.isEmpty()
                        ? "定时任务操作已完成" : output);
                payload.put("expanded", normalized.contains("schedule-task"));
                return new TeamHistoryActionTool(
                        TeamEventType.SCHEDULE_EVENT.name(), payload);
            }
            return null;
        }

        private static String legacyTalkToDelivery(String output) {
            if (output.contains("路由")) return "ROUTE_REQUESTED";
            if (output.contains("inbox") || output.contains("正忙")) return "QUEUED";
            if (output.contains("失败")) return "REJECTED";
            return "DELIVERED";
        }

        private static String firstString(com.google.gson.JsonElement value,
                                          String key) {
            if (value == null || value.isJsonNull()) return "";
            if (value.isJsonObject()) {
                com.google.gson.JsonObject object = value.getAsJsonObject();
                if (object.has(key) && !object.get(key).isJsonNull()
                        && object.get(key).isJsonPrimitive()) {
                    return object.get(key).getAsString();
                }
                for (Map.Entry<String, com.google.gson.JsonElement> entry
                        : object.entrySet()) {
                    String nested = firstString(entry.getValue(), key);
                    if (!nested.isEmpty()) return nested;
                }
            } else if (value.isJsonArray()) {
                for (com.google.gson.JsonElement child : value.getAsJsonArray()) {
                    String nested = firstString(child, key);
                    if (!nested.isEmpty()) return nested;
                }
            }
            return "";
        }
    }

    /** Hides channel routing instructions while preserving the external message. */
    static final class TeamHistoryChannelMessage {
        private static final String MARKER = "📨 [外部信道消息] ";
        private static final String CONTENT_START = "当前消息:\n";
        private static final String[] CONTENT_END_MARKERS = {
                "\n\n引用消息（", "\n\n本次消息关联附件",
                "\n\n本次消息已绑定其原始信道会话。"
        };
        final String label;
        final String content;

        private TeamHistoryChannelMessage(String label, String content) {
            this.label = label;
            this.content = content;
        }

        static TeamHistoryChannelMessage parse(String value) {
            if (value == null) return null;
            int marker = value.indexOf(MARKER);
            int labelEnd = marker < 0 ? -1 : value.indexOf('\n', marker);
            int contentMarker = value.indexOf(CONTENT_START);
            if (marker < 0 || labelEnd < 0 || contentMarker < 0) return null;
            int start = contentMarker + CONTENT_START.length();
            int end = value.length();
            for (String endMarker : CONTENT_END_MARKERS) {
                int candidate = value.indexOf(endMarker, start);
                if (candidate >= start && candidate < end) end = candidate;
            }
            if (end <= start) return null;
            return new TeamHistoryChannelMessage(
                    "channel:" + value.substring(marker + MARKER.length(), labelEnd).trim(),
                    value.substring(start, end));
        }
    }

    public TeamCommandResult getStatus(String requestId, TeamMemberCommand command) {
        try {
            MemberRoute route = requireMemberRoute(command, false);
            ensureGrantActive(route.team);
            AcpClient client = clientRegistry.get(route.team.getTeamId(),
                    route.member.getTeamMemberId()).orElse(null);
            return TeamCommandResult.success(requestId, "OK",
                    "Team member status loaded", route.team.getVersion(),
                    memberData(route.runtime, route.member, client));
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
    }

    public TeamCommandResult getContextUsage(String requestId,
                                             TeamMemberCommand command) {
        try {
            MemberRoute route = requireMemberRoute(command, false);
            ensureGrantActive(route.team);
            AcpClient client = requireExistingClient(route);
            Map<String, Object> data = memberData(route.runtime, route.member, client);
            data.put("contextUsagePercentage", client.getContextUsagePercentage());
            return TeamCommandResult.success(requestId, "OK",
                    "Team context usage loaded", route.team.getVersion(), data);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
    }

    public TeamCommandResult newSession(String requestId, TeamMemberCommand command) {
        return replaceSession(requestId, command, TeamMemberStartOptions.newSession());
    }

    public com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewResult readTextFile(
            String requestId, TeamMemberCommand command) {
        try {
            MemberRoute route = requireMemberRoute(command, false);
            ensureGrantActive(route.team);
            AcpClient client = requireExistingClient(route);
            return com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewReader.read(
                    requestId, client.getWorkspacePath(), command.getPath(),
                    command.getMaxBytes(), command.getCharset());
        } catch (IllegalArgumentException e) {
            return com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewResult.error(
                    requestId, "INVALID_ARGUMENT", e.getMessage(), false);
        } catch (MemberRouteException e) {
            return com.mola.cmd.proxy.app.acp.filepreview.TextFilePreviewResult.error(
                    requestId, e.code.name(), e.getMessage(), false);
        }
    }

    /** Lists only files already owned by the selected member's current session. */
    public TeamCommandResult listSessionResources(String requestId,
                                                  TeamMemberCommand command) {
        try {
            MemberRoute route = requireMemberRoute(command, false);
            ensureGrantActive(route.team);
            AcpClient client = requireCurrentResourceClient(route, command);
            List<Map<String, Object>> resources = new ArrayList<>();
            for (java.nio.file.Path path : safeResourcePaths(client, client.getSessionId())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("resourceId", resourceId(route, client, path));
                item.put("fileName", path.getFileName().toString());
                try { item.put("size", java.nio.file.Files.size(path)); }
                catch (java.io.IOException ignored) { item.put("size", 0L); }
                item.put("contentType", resourceContentType(path));
                resources.add(item);
            }
            Map<String, Object> data = memberData(route.runtime, route.member, client);
            data.put("resources", resources);
            return TeamCommandResult.success(requestId, "OK",
                    "Team session resources listed", route.team.getVersion(), data);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
    }

    public TeamCommandResult previewSessionResource(String requestId,
                                                     TeamMemberCommand command,
                                                     String requestedResourceId) {
        try {
            MemberRoute route = requireMemberRoute(command, false);
            ensureGrantActive(route.team);
            AcpClient client = requireCurrentResourceClient(route, command);
            java.nio.file.Path path = resolveResource(
                    route, client, requestedResourceId);
            byte[] bytes = readResource(path, 2 * 1024 * 1024);
            String contentType = resourceContentType(path);
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("resourceId", requestedResourceId);
            resource.put("fileName", path.getFileName().toString());
            resource.put("contentType", contentType);
            if (isSafeRasterImage(contentType)) {
                resource.put("kind", "image");
                resource.put("contentBase64", java.util.Base64.getEncoder()
                        .encodeToString(bytes));
            } else if (isTextResource(bytes, contentType)) {
                resource.put("kind", "text");
                resource.put("content", new String(bytes,
                        java.nio.charset.StandardCharsets.UTF_8));
            } else {
                resource.put("kind", "binary");
            }
            try { resource.put("truncated", java.nio.file.Files.size(path) > bytes.length); }
            catch (java.io.IOException ignored) { resource.put("truncated", false); }
            Map<String, Object> data = memberData(route.runtime, route.member, client);
            data.put("resource", resource);
            return TeamCommandResult.success(requestId, "OK",
                    "Team session resource previewed", route.team.getVersion(), data);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
    }

    public TeamResourcePayload downloadSessionResource(TeamMemberCommand command,
                                                       String requestedResourceId) {
        try {
            MemberRoute route = requireMemberRoute(command, false);
            ensureGrantActive(route.team);
            AcpClient client = requireCurrentResourceClient(route, command);
            java.nio.file.Path path = resolveResource(route, client, requestedResourceId);
            return new TeamResourcePayload(path.getFileName().toString(),
                    resourceContentType(path), readResource(path, 20 * 1024 * 1024));
        } catch (MemberRouteException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private AcpClient requireCurrentResourceClient(MemberRoute route,
                                                   TeamMemberCommand command)
            throws MemberRouteException {
        AcpClient client = requireExistingClient(route);
        String sessionId = command.getSessionId();
        if (sessionId == null || !sessionId.equals(client.getSessionId())) {
            throw new IllegalArgumentException("Team member session has changed");
        }
        return client;
    }

    private List<java.nio.file.Path> safeResourcePaths(AcpClient client,
                                                       String sessionId) {
        java.nio.file.Path root = client.getHistoryManager()
                .getSessionFilesDirectory(sessionId);
        List<java.nio.file.Path> result = new ArrayList<>();
        if (java.nio.file.Files.isDirectory(root,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
                java.nio.file.Path realRoot = root.toRealPath(
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                try (java.util.stream.Stream<java.nio.file.Path> children =
                             java.nio.file.Files.list(realRoot)) {
                    children.limit(201).forEach(path -> {
                        try {
                            if (java.nio.file.Files.isSymbolicLink(path)
                                    || !java.nio.file.Files.isRegularFile(path,
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
                            java.nio.file.Path real = path.toRealPath(
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
                            if (real.getParent().equals(realRoot)) result.add(real);
                        } catch (java.io.IOException ignored) { }
                    });
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException(
                        "failed to enumerate Team session resources", e);
            }
        }
        try {
            java.nio.file.Path workspace = java.nio.file.Paths.get(client.getWorkspacePath())
                    .toAbsolutePath().normalize().toRealPath(
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
            for (String value : client.getHistoryManager().getFileAbsolutePaths()) {
                java.nio.file.Path candidate = java.nio.file.Paths.get(value)
                        .toAbsolutePath().normalize();
                if (java.nio.file.Files.isSymbolicLink(candidate)
                        || !java.nio.file.Files.isRegularFile(candidate,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                java.nio.file.Path real = candidate.toRealPath(
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (real.startsWith(workspace) && !result.contains(real)) result.add(real);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to validate Team workspace resources", e);
        }
        if (result.size() > 200) throw new IllegalStateException(
                "too many Team session resources");
        result.sort(java.util.Comparator.comparing(path -> path.getFileName().toString()));
        return result;
    }

    private java.nio.file.Path resolveResource(MemberRoute route, AcpClient client,
                                                String requestedResourceId) {
        if (requestedResourceId == null
                || !requestedResourceId.matches("tr-[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid Team resourceId");
        }
        for (java.nio.file.Path path : safeResourcePaths(client, client.getSessionId())) {
            if (requestedResourceId.equals(resourceId(route, client, path))) return path;
        }
        throw new IllegalArgumentException(
                "resource not found in current Team member session");
    }

    private static String resourceId(MemberRoute route, AcpClient client,
                                     java.nio.file.Path path) {
        String value = route.team.getTeamId() + "\n" + route.member.getTeamMemberId()
                + "\n" + client.getSessionId() + "\n" + path;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) hex.append(String.format("%02x", item & 0xff));
            return "tr-" + hex.substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] readResource(java.nio.file.Path path, int maxBytes) {
        try {
            long size = java.nio.file.Files.size(path);
            if (size > maxBytes && maxBytes == 20 * 1024 * 1024) {
                throw new IllegalArgumentException("resource exceeds download limit");
            }
            int length = (int) Math.min(size, maxBytes);
            byte[] bytes = new byte[length];
            try (java.io.InputStream input = java.nio.file.Files.newInputStream(path)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count < 0) break;
                    offset += count;
                }
                return offset == bytes.length ? bytes
                        : java.util.Arrays.copyOf(bytes, offset);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to read Team session resource", e);
        }
    }

    private static String resourceContentType(java.nio.file.Path path) {
        try {
            String value = java.nio.file.Files.probeContentType(path);
            return value == null ? "application/octet-stream" : value;
        } catch (java.io.IOException ignored) {
            return "application/octet-stream";
        }
    }

    private static boolean isSafeRasterImage(String contentType) {
        return "image/png".equals(contentType) || "image/jpeg".equals(contentType)
                || "image/gif".equals(contentType) || "image/webp".equals(contentType);
    }

    private static boolean isTextResource(byte[] bytes, String contentType) {
        if (contentType.startsWith("text/") || contentType.contains("json")
                || contentType.contains("xml") || contentType.contains("javascript")) {
            return true;
        }
        int sample = Math.min(bytes.length, 4096);
        for (int i = 0; i < sample; i++) if (bytes[i] == 0) return false;
        return true;
    }

    public TeamCommandResult deliverTalkTo(TeamTalkToDeliverCommand command) {
        return handleTalkToDelivery(command, false);
    }

    public TeamCommandResult openTalkToCircuit(TeamTalkToDeliverCommand command) {
        return handleTalkToDelivery(command, true);
    }

    private TeamCommandResult handleTalkToDelivery(TeamTalkToDeliverCommand command,
                                                   boolean circuitControl) {
        String requestId = command == null ? "" : command.getRequestId();
        try {
            ensureOpen();
            if (command == null) throw new IllegalArgumentException("delivery payload is required");
            requireSchema(command.getSchemaVersion());
            requireSafeId(command.getRequestId(), "requestId");
            requireSafeId(command.getMessageId(), "messageId");
            requireSafeId(command.getTeamId(), "teamId");
            requireSafeId(command.getSenderTeamMemberId(), "senderTeamMemberId");
            requireSafeId(command.getTargetTeamMemberId(), "targetTeamMemberId");
            requireText(command.getOwnerChatterId(), "ownerChatterId");
            requireText(command.getContent(), "content");
            long now = System.currentTimeMillis();
            if (command.getDepth() < 1 || command.getDepth() > TeamTalkToDispatcher.MAX_DEPTH) {
                throw new IllegalArgumentException("depth must be between 1 and "
                        + TeamTalkToDispatcher.MAX_DEPTH);
            }
            if (command.getCreatedAt() <= 0L || command.getExpiresAt() <= command.getCreatedAt()
                    || command.getCreatedAt() > now + 60_000L
                    || command.getExpiresAt() - command.getCreatedAt()
                    > TeamTalkToDispatcher.INBOX_TTL_MS
                    || now > command.getExpiresAt()) {
                throw new IllegalArgumentException("talkTo delivery is expired or has invalid TTL");
            }
            TeamRuntime runtime = teams.get(command.getTeamId());
            if (runtime == null) return TeamCommandResult.error(requestId,
                    TeamErrorCode.NOT_FOUND, "Team not found");
            TeamDefinition team = runtime.getDefinition();
            if (!team.isMixedPlacement()
                    || !team.getOwnerChatterId().equals(command.getOwnerChatterId())) {
                return TeamCommandResult.error(requestId,
                        TeamErrorCode.UNAUTHORIZED, "mixed Team owner mismatch");
            }
            ensureGrantActive(team);
            if (findContact(team, command.getSenderTeamMemberId()) == null
                    || findContact(team, command.getTargetTeamMemberId()) == null
                    || findMember(team, command.getTargetTeamMemberId()) == null) {
                return TeamCommandResult.error(requestId, TeamErrorCode.UNAUTHORIZED,
                        "sender/target is not authorized by roster or target is not local");
            }
            if (!circuitControl && !team.canCommunicate(
                    command.getSenderTeamMemberId(),
                    command.getTargetTeamMemberId())) {
                return TeamCommandResult.error(requestId,
                        TeamErrorCode.TEAM_COMMUNICATION_FORBIDDEN,
                        "captain Team members may communicate only with the captain");
            }
            if (circuitControl) {
                requireSafeId(command.getCascadeId(), "cascadeId");
                if (command.getCascadeStartedAt() <= 0L
                        || command.getCascadeStartedAt() > now + 60_000L) {
                    throw new IllegalArgumentException("invalid cascadeStartedAt");
                }
                getOrCreateTalkToDispatcher(team.getTeamId()).openRemoteCircuit(
                        command.getTargetTeamMemberId(), command.getSenderTeamMemberId(),
                        new com.mola.cmd.proxy.app.acp.talkto.model.TalkToTrace(
                                command.getCascadeId(), command.getMessageId(),
                                command.getParentMessageId(), command.getDepth(),
                                command.getCascadeStartedAt()).withCascadeIds(command.getCascadeIds()), command.getContent());
                return TeamCommandResult.success(requestId, "OK", "cascade closed without prompt",
                        team.getVersion(), Collections.emptyMap());
            }
            String dedupKey = team.getTeamId() + "/" + command.getMessageId();
            Long old = receivedTalkToMessages.putIfAbsent(dedupKey, command.getExpiresAt());
            receivedTalkToMessages.entrySet().removeIf(e -> e.getValue() < now);
            if (old != null) {
                Map<String, Object> duplicate = new LinkedHashMap<>();
                duplicate.put("delivery", "DUPLICATE");
                duplicate.put("messageId", command.getMessageId());
                return TeamCommandResult.success(requestId, "OK", "duplicate delivery ignored",
                        team.getVersion(), duplicate);
            }
            com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher.InboundDeliveryResult delivered =
                    getOrCreateTalkToDispatcher(team.getTeamId()).deliverRemoteInbound(
                            command.getMessageId(), command.getSenderTeamMemberId(),
                            command.getTargetTeamMemberId(), command.getContent(),
                            command.getDepth(), command.getCascadeId(),
                            command.getParentMessageId(), command.getCascadeStartedAt(),
                            command.getCascadeIds(),
                            command.getAuthPrincipalId() == null
                                    || command.getAuthPrincipalId().trim().isEmpty() ? null
                                    : new AuthPrincipalContext(
                                            command.getAuthPrincipalId(),
                                            command.getAuthPrincipalName(),
                                            command.getAuthSourceType(),
                                            command.getAuthSourceId()));
            if (delivered.getStatus()
                    == com.mola.cmd.proxy.app.acp.talkto.TalkToDispatcher.InboundDeliveryResult.Status.REJECTED) {
                receivedTalkToMessages.remove(dedupKey);
                return TeamCommandResult.error(requestId, TeamErrorCode.TEAM_NOT_READY,
                        delivered.getReason());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("delivery", delivered.getStatus().name());
            data.put("queuePosition", delivered.getQueuePosition());
            data.put("messageId", command.getMessageId());
            return TeamCommandResult.success(requestId, "OK", "talkTo delivery accepted",
                    team.getVersion(), data);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error(requestId, TeamErrorCode.VALIDATION_ERROR,
                    e.getMessage());
        }
    }

    /** Sleeps idle Team member clients under the same lock as message admission. */
    public int sleepIdleClients(long nowMillis) {
        if (closed.get()) return 0;
        int slept = 0;
        for (TeamDefinition snapshot : snapshotDefinitions()) {
            TeamRuntime runtime = teams.get(snapshot.getTeamId());
            if (runtime == null) continue;
            runtime.getOperationLock().lock();
            try {
                TeamDefinition team = runtime.getDefinition();
                if (!runtime.isAcceptingRequests() || team.getState() != TeamState.READY
                        || !grantsActive(team)) continue;
                for (TeamMemberDefinition member : team.getMembers()) {
                    if (member.getState() != TeamMemberState.READY) continue;
                    AcpClient client = clientRegistry.get(
                            team.getTeamId(), member.getTeamMemberId()).orElse(null);
                    com.mola.cmd.proxy.app.acp.AutoSleepConfig config = client == null
                            || client.getRobotParam() == null
                            ? null : client.getRobotParam().getAutoSleep();
                    if (config == null || !config.isEnabled()) continue;
                    long idleMillis = TimeUnit.MINUTES.toMillis(config.getIdleMinutes());
                    if (client.sleepIfIdle(nowMillis, idleMillis)) {
                        publishMemberState(runtime, member.getTeamMemberId(),
                                TeamMemberState.SLEEP, null);
                        slept++;
                        logger.info("Team member 已进入睡眠, teamId={}, memberId={}, sessionId={}",
                                team.getTeamId(), member.getTeamMemberId(), client.getSessionId());
                    }
                }
            } catch (Exception e) {
                logger.warn("Team member 自动睡眠失败, teamId={}", snapshot.getTeamId(), e);
            } finally {
                runtime.getOperationLock().unlock();
            }
        }
        return slept;
    }

    /** Rotates idle Team member sessions under the same operation lock as manual/scheduled work. */
    public int rotateIdleSessions(long nowMillis) {
        if (closed.get() || startupCoordinator == null) return 0;
        int rotated = 0;
        for (TeamDefinition snapshot : snapshotDefinitions()) {
            TeamRuntime runtime = teams.get(snapshot.getTeamId());
            if (runtime == null) continue;
            runtime.getOperationLock().lock();
            try {
                TeamDefinition team = runtime.getDefinition();
                if (!runtime.isAcceptingRequests() || team.getState() != TeamState.READY
                        || !grantsActive(team)) continue;
                for (TeamMemberDefinition member : team.getMembers()) {
                    if (member.getState() != TeamMemberState.READY
                            && member.getState() != TeamMemberState.SLEEP) continue;
                    AcpClient old = clientRegistry.get(
                            team.getTeamId(), member.getTeamMemberId()).orElse(null);
                    String checkKey = team.getTeamId() + ":" + member.getTeamMemberId();
                    com.mola.cmd.proxy.app.acp.AutoNewSessionConfig config = old == null
                            || old.getRobotParam() == null
                            ? null : old.getRobotParam().getAutoNewSession();
                    if (config == null || !config.isEnabled()) {
                        autoNewSessionLastChecks.remove(checkKey);
                        continue;
                    }
                    Long lastCheck = autoNewSessionLastChecks.putIfAbsent(checkKey, nowMillis);
                    long checkIntervalMillis = TimeUnit.MINUTES.toMillis(
                            config.getCheckIntervalMinutes());
                    if (lastCheck == null || nowMillis - lastCheck < checkIntervalMillis
                            || !autoNewSessionLastChecks.replace(
                            checkKey, lastCheck, nowMillis)) continue;
                    long idleMillis = TimeUnit.MINUTES.toMillis(config.getIdleMinutes());
                    if (old.getState() == AbstractAcpClient.State.SLEEP) {
                        // 睡眠会话在唤醒时按最新空闲时长判断是否新建。
                        continue;
                    }
                    if (!old.tryReserveIdleForAutoNewSession(nowMillis, idleMillis)) continue;

                    publishMemberState(runtime, member.getTeamMemberId(),
                            TeamMemberState.STARTING, null);
                    if (!clientRegistry.remove(team.getTeamId(), member.getTeamMemberId())
                            .filter(client -> client == old).isPresent()) {
                        continue;
                    }
                    try { old.close(); } catch (IOException ignored) { }
                    TeamMemberDefinition starting = findMember(
                            runtime.getDefinition(), member.getTeamMemberId());
                    AcpClient replacement = startupCoordinator.replaceMember(
                            runtime, starting, TeamMemberStartOptions.newSession());
                    if (!clientRegistry.register(team.getTeamId(),
                            member.getTeamMemberId(), replacement)) {
                        replacement.close();
                        throw new IOException("duplicate Team automatic replacement client");
                    }
                    publishMemberState(runtime, member.getTeamMemberId(),
                            TeamMemberState.READY, null);
                    publishMemberSessionChanged(runtime, member.getTeamMemberId(),
                            old.getSessionId(), replacement.getSessionId(), "AUTO_IDLE");
                    rotated++;
                    logger.info("Team 空闲会话已自动轮转, teamId={}, memberId={}, sessionId={}",
                            team.getTeamId(), member.getTeamMemberId(), replacement.getSessionId());
                }
            } catch (Exception e) {
                logger.warn("Team 空闲会话自动轮转失败, teamId={}", snapshot.getTeamId(), e);
            } finally {
                runtime.getOperationLock().unlock();
            }
        }
        return rotated;
    }

    /**
     * Publishes a sleeping member's deferred new-session rotation so the UI can
     * resync the member session identity after wake. Unlike the idle rotation
     * path this runs while the member turn is still in flight, so it only emits
     * the projection event and never mutates member/client state.
     */
    public void onMemberSessionRotated(String teamId, String memberId,
                                       String oldSessionId, String newSessionId) {
        TeamRuntime runtime = teams.get(teamId);
        if (runtime == null || closed.get()) return;
        publishMemberSessionChanged(runtime, memberId, oldSessionId, newSessionId, "AUTO_IDLE");
    }

    /** Synchronizes the member before an Agent-requested continuation starts. */
    public void onMemberNewSession(String teamId, String memberId, AcpClient expected,
                                   String oldSessionId, String newSessionId,
                                   String prompt, String messageId) throws IOException {
        TeamRuntime runtime = teams.get(teamId);
        if (runtime == null || closed.get()) throw new IOException("Team is unavailable");
        runtime.getOperationLock().lock();
        try {
            if (clientRegistry.get(teamId, memberId).orElse(null) != expected
                    || !newSessionId.equals(expected.getSessionId())) {
                throw new IOException("Team member session is no longer current");
            }
            ensureGrantActive(runtime.getDefinition());
            publishMemberState(runtime, memberId, TeamMemberState.BUSY, null);
            publishMemberSessionChanged(runtime, memberId, oldSessionId, newSessionId, "AGENT_TOOL");
            TeamMemberDefinition member = findMember(runtime.getDefinition(), memberId);
            Map<String, Object> accepted = new LinkedHashMap<>();
            accepted.put("sessionId", newSessionId);
            accepted.put("messageId", messageId);
            accepted.put("revision", 1L);
            accepted.put("content", prompt);
            accepted.put("source", "AGENT_TOOL");
            accepted.put("attachments", java.util.Collections.emptyList());
            eventSink.publish(TeamEventEnvelope.next(runtime, memberId, member.getAcpClientId(),
                    TeamEventType.USER_MESSAGE_ACCEPTED, accepted));
        } catch (MemberRouteException failure) {
            throw new IOException(failure);
        } finally {
            runtime.getOperationLock().unlock();
        }
    }

    public TeamCommandResult restoreSession(String requestId, TeamMemberCommand command) {
        try {
            return replaceSession(requestId, command,
                    TeamMemberStartOptions.restore(
                            command == null ? null : command.getSessionId()));
        } catch (IllegalArgumentException e) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        }
    }

    public void onMemberState(String teamId, String memberId,
                              com.mola.cmd.proxy.app.acp.team.model.TeamMemberState state,
                              TeamError error) {
        TeamRuntime runtime = teams.get(teamId);
        if (runtime == null || closed.get()) {
            return;
        }
        boolean retryRevokedCleanup = false;
        runtime.getOperationLock().lock();
        try {
            if (runtime.getDefinition().getState()
                    != com.mola.cmd.proxy.app.acp.team.model.TeamState.READY) {
                return;
            }
            publishMemberState(runtime, memberId, state, error);
            scheduleStateMismatchSince.remove(scheduleMismatchKey(teamId, memberId));
            retryRevokedCleanup = revokedGrantCleanupPending.contains(teamId);
        } catch (Exception updateError) {
            logger.warn("Team member 状态回写失败, teamId={}, memberId={}, state={}",
                    teamId, memberId, state, updateError);
        } finally {
            runtime.getOperationLock().unlock();
        }
        if (retryRevokedCleanup) {
            scheduleRevokedGrantCleanup(runtime);
        }
    }

    private void logScheduleRejection(ScheduleOwnerKey owner, String taskId,
                                      String reason, TeamDefinition team,
                                      TeamMemberDefinition member, AcpClient client) {
        TeamRuntime runtime = team == null ? null : teams.get(team.getTeamId());
        logger.info("schedule_gate result=deferred reason={} taskId={} teamId={}"
                        + " memberId={} requestOwnerId={} teamOwnerId={}"
                        + " teamState={} memberState={} clientState={}"
                        + " acceptingRequests={} sessionId={}",
                reason, taskId,
                owner == null ? null : owner.getTeamId(),
                owner == null ? null : owner.getTeamMemberId(),
                owner == null ? null : owner.getOwnerId(),
                team == null ? null : team.getOwnerChatterId(),
                team == null ? null : team.getState(),
                member == null ? null : member.getState(),
                client == null ? null : client.getState(),
                runtime == null ? null : runtime.isAcceptingRequests(),
                client == null ? null : client.getSessionId());
    }

    private static String scheduleMismatchKey(String teamId, String memberId) {
        return teamId + ":" + memberId;
    }

    /**
     * Config reload/recovery hook. Revoked mixed fragments stop accepting new work,
     * publish a cleanup projection, and enter the existing idempotent delete state
     * machine after every already-running member turn has finished.
     */
    public void reconcileRevokedGrants() {
        if (closed.get()) return;
        for (String teamId : new HashSet<>(revokedGrantCleanupPending)) {
            TeamRuntime existing = teams.get(teamId);
            if (existing == null || existing.getDefinition().getState().isTerminal()) {
                revokedGrantCleanupPending.remove(teamId);
                revokedGrantCleanupRunning.remove(teamId);
            }
        }
        for (TeamDefinition definition : snapshotDefinitions()) {
            if (!definition.isMixedPlacement() || definition.getState().isTerminal()
                    || grantsActive(definition)) continue;
            TeamRuntime runtime = teams.get(definition.getTeamId());
            if (runtime == null) continue;
            if (revokedGrantCleanupPending.add(definition.getTeamId())) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("team", definition);
                data.put("cleanupStatus", "REVOKED_CLEANUP");
                data.put("reason", "TEAM_GRANT_REVOKED");
                data.put("waitForBusyTurn", hasBusyMember(definition));
                publishEvent(runtime, TeamEventType.TEAM_STATE_CHANGED, data);
            }
            scheduleRevokedGrantCleanup(runtime);
        }
    }

    public boolean isRevokedGrantCleanupPending(String teamId) {
        return teamId != null && revokedGrantCleanupPending.contains(teamId);
    }

    private void scheduleRevokedGrantCleanup(TeamRuntime runtime) {
        TeamDefinition definition = runtime.getDefinition();
        String teamId = definition.getTeamId();
        if (!revokedGrantCleanupPending.contains(teamId)
                || hasBusyMember(definition)
                || !revokedGrantCleanupRunning.add(teamId)) return;
        cleanupExecutor.submit(() -> {
            try {
                TeamDefinition current = runtime.getDefinition();
                if (current.getState().isTerminal()) {
                    revokedGrantCleanupPending.remove(teamId);
                    return;
                }
                TeamCommandResult result = delete(new TeamDeleteCommand(
                        TeamDefinition.SCHEMA_VERSION,
                        "grant-revoke-" + teamId + "-" + UUID.randomUUID(),
                        current.getOwnerChatterId(), teamId, null));
                if (result.isAccepted()) {
                    revokedGrantCleanupPending.remove(teamId);
                } else {
                    logger.warn("team_grant_cleanup action=delete_deferred teamId={} code={} error={}",
                            teamId, result.getCode(), result.getMessage());
                }
            } finally {
                revokedGrantCleanupRunning.remove(teamId);
            }
        });
    }

    private boolean hasBusyMember(TeamDefinition definition) {
        for (TeamMemberDefinition member : definition.getMembers()) {
            if (member.getState() == TeamMemberState.BUSY) return true;
            AcpClient client = clientRegistry.get(
                    definition.getTeamId(), member.getTeamMemberId()).orElse(null);
            if (client != null && client.getState() == AbstractAcpClient.State.BUSY) return true;
        }
        return false;
    }

    /**
     * Rebuilds every local Team member backed by one refreshed source robot.
     * Existing provider sessions are loaded again instead of creating a new
     * conversation, so a config save does not leave the member CLOSED.
     */
    public int refreshSourceRobot(String sourceRobotId) throws IOException {
        String sourceId = sourceRobotId == null ? "" : sourceRobotId.trim();
        if (sourceId.isEmpty() || closed.get()) return 0;
        if (startupCoordinator == null) {
            throw new IOException("Team startup coordinator is unavailable");
        }

        int refreshed = 0;
        List<String> failures = new ArrayList<>();
        for (TeamDefinition snapshot : snapshotDefinitions()) {
            TeamRuntime runtime = teams.get(snapshot.getTeamId());
            if (runtime == null) continue;
            for (TeamMemberDefinition candidate : snapshot.getMembers()) {
                if (!sourceId.equals(candidate.getSourceRobotId())) continue;
                boolean oldRemoved = false;
                runtime.getOperationLock().lock();
                try {
                    TeamDefinition current = runtime.getDefinition();
                    if (current.getState() != TeamState.READY) continue;
                    TeamMemberDefinition member = findMember(
                            current, candidate.getTeamMemberId());
                    if (member == null || !sourceId.equals(member.getSourceRobotId())) {
                        continue;
                    }

                    publishMemberState(runtime, member.getTeamMemberId(),
                            TeamMemberState.STARTING, null);
                    AcpClient old = clientRegistry.remove(current.getTeamId(),
                            member.getTeamMemberId()).orElse(null);
                    oldRemoved = true;
                    if (old != null) {
                        try {
                            old.close();
                        } catch (IOException closeFailure) {
                            logger.warn("刷新来源 Robot 时关闭旧 Team client 失败, teamId={}, memberId={}",
                                    current.getTeamId(), member.getTeamMemberId(), closeFailure);
                        }
                    }

                    TeamMemberDefinition starting = findMember(
                            runtime.getDefinition(), member.getTeamMemberId());
                    AcpClient replacement = startupCoordinator.replaceMember(
                            runtime, starting, TeamMemberStartOptions.initial());
                    if (!clientRegistry.register(current.getTeamId(),
                            member.getTeamMemberId(), replacement)) {
                        replacement.close();
                        throw new IOException("duplicate Team member client");
                    }
                    String fingerprint = TeamSourceRobotSnapshots.fingerprint(
                            replacement.getRobotParam());
                    publishMemberState(runtime, member.getTeamMemberId(),
                            TeamMemberState.READY, null, fingerprint);
                    refreshed++;
                    logger.info("Team member 已随来源 Robot 自动刷新并开启, teamId={}, memberId={}, sourceRobotId={}, sessionId={}",
                            current.getTeamId(), member.getTeamMemberId(), sourceId,
                            replacement.getSessionId());
                } catch (Exception error) {
                    String key = snapshot.getTeamId() + "/" + candidate.getTeamMemberId();
                    failures.add(key + ": " + safeMessage(error));
                    if (oldRemoved) {
                        clientRegistry.remove(snapshot.getTeamId(),
                                candidate.getTeamMemberId()).ifPresent(client -> {
                            try {
                                client.close();
                            } catch (IOException closeFailure) {
                                logger.warn("关闭刷新失败的 Team client 失败, teamId={}, memberId={}",
                                        snapshot.getTeamId(), candidate.getTeamMemberId(),
                                        closeFailure);
                            }
                        });
                    }
                    TeamError teamError = TeamError.of(TeamErrorCode.CLIENT_START_FAILED,
                            safeMessage(error), true);
                    try {
                        publishMemberState(runtime, candidate.getTeamMemberId(),
                                TeamMemberState.ERROR, teamError);
                    } catch (Exception stateError) {
                        logger.warn("Team member 自动刷新失败状态回写失败, teamId={}, memberId={}",
                                snapshot.getTeamId(), candidate.getTeamMemberId(), stateError);
                    }
                } finally {
                    runtime.getOperationLock().unlock();
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new IOException("Team member refresh failed: "
                    + String.join("; ", failures));
        }
        return refreshed;
    }

    /**
     * Robot 总开关关闭或切为 onlySubAgent 时，停止所有引用该来源的 Team client。
     * Team 定义保留为 ERROR，重新启用来源后可通过既有恢复/换会话流程重建。
     */
    public void disableSourceRobot(String sourceRobotId) {
        if (sourceRobotId == null || sourceRobotId.trim().isEmpty() || closed.get()) {
            return;
        }
        TeamError error = TeamError.of(TeamErrorCode.SOURCE_ROBOT_NOT_FOUND,
                "source robot is disabled for Fast Team", true);
        for (TeamDefinition definition : snapshotDefinitions()) {
            for (TeamMemberDefinition member : definition.getMembers()) {
                if (!sourceRobotId.equals(member.getSourceRobotId())) {
                    continue;
                }
                clientRegistry.remove(definition.getTeamId(), member.getTeamMemberId())
                        .ifPresent(client -> {
                            try {
                                client.close();
                            } catch (IOException closeFailure) {
                                logger.warn("关闭已禁用来源的 Team client 失败, teamId={}, memberId={}",
                                        definition.getTeamId(), member.getTeamMemberId(),
                                        closeFailure);
                            }
                        });
                onMemberState(definition.getTeamId(), member.getTeamMemberId(),
                        TeamMemberState.ERROR, error);
            }
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    public TeamResourceSnapshot resourceSnapshot() {
        return new TeamResourceSnapshot(
                teams.size(), clientRegistry.size(),
                talkToDispatchers.size(), pendingCleanup.size(),
                scheduleOwnerCount.getAsInt());
    }

    public TeamMetricsSnapshot metricsSnapshot() {
        return metrics.snapshot(activeTeamCount(), activeMemberCount(),
                resourceSnapshot());
    }

    public TeamLimits getLimits() {
        return limits;
    }

    public TeamResourceSnapshot resourceSnapshot(String teamId) {
        return new TeamResourceSnapshot(
                teams.containsKey(teamId) ? 1 : 0,
                clientRegistry.size(teamId),
                talkToDispatchers.containsKey(teamId) ? 1 : 0,
                pendingCleanup.containsKey(teamId) ? 1 : 0,
                scheduleTeamOwnerCount.applyAsInt(teamId));
    }

    void reapResources(long now) {
        metrics.reaperRun();
        for (Map.Entry<String, Future<List<IOException>>> entry
                : pendingCleanup.entrySet()) {
            if (entry.getValue().isDone()) {
                pendingCleanup.remove(entry.getKey(), entry.getValue());
            }
        }
        Set<String> activeIds = new HashSet<>(teams.keySet());
        for (String orphanTeamId : clientRegistry.teamIds()) {
            if (!activeIds.contains(orphanTeamId)) {
                clientRegistry.closeTeam(orphanTeamId);
            }
        }
        for (TeamRuntime runtime : new ArrayList<>(teams.values())) {
            TeamState state = runtime.getDefinition().getState();
            if (state == TeamState.DELETING) {
                resumeDeleting(runtime);
            } else if (state.isTerminal()) {
                cleanupEphemeralResourcesForDelete(
                        runtime.getDefinition().getTeamId());
                clientRegistry.closeTeam(runtime.getDefinition().getTeamId());
                teams.remove(runtime.getDefinition().getTeamId(), runtime);
                try {
                    store.deleteTeam(runtime.getDefinition().getTeamId());
                } catch (IOException ignored) {
                }
            }
        }
        scheduleOrphanCleanup.accept(
                Collections.unmodifiableSet(new HashSet<>(teams.keySet())));
        try {
            for (TeamOperationRecord operation : store.loadOperations()) {
                if (operation.getExpiresAt() <= now) {
                    store.deleteOperation(operation.getRequestId());
                }
            }
            for (TeamTombstone tombstone : store.loadTombstones()) {
                historyArchiver.deleteExpired(tombstone.getTeamId(), now);
                if (tombstone.getExpireAt() <= now) {
                    scheduleCleanup.accept(tombstone.getTeamId());
                    store.deleteTombstone(tombstone.getTeamId());
                }
            }
        } catch (Exception ignored) {
            // reaper 是最终一致性兜底；单次失败留待下一轮。
        }
        reconcileRevokedGrants();
        TeamMetricsSnapshot snapshot = metricsSnapshot();
        logger.debug("team_metrics activeTeams={} activeMembers={} clients={}"
                        + " talkToDispatchers={} pendingCleanup={}"
                        + " scheduleOwners={} reaperRuns={}",
                snapshot.getActiveTeams(), snapshot.getActiveMembers(),
                snapshot.getResources().getClients(),
                snapshot.getResources().getTalkToDispatchers(),
                snapshot.getResources().getPendingCleanup(),
                snapshot.getResources().getScheduleOwners(),
                snapshot.getReaperRuns());
    }

    @Override
    public void close() {
        close(false);
    }

    /** 全局 stop 专用：Team 会话的记忆提取延迟到下一次启动恢复。 */
    public void closeForShutdown() {
        close(true);
    }

    private void close(boolean deferMemoryExtraction) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (TeamRuntime runtime : teams.values()) {
            runtime.stopAcceptingRequests();
        }
        resourceReaper.close();
        revokedGrantCleanupPending.clear();
        revokedGrantCleanupRunning.clear();
        for (TeamTalkToDispatcher dispatcher : talkToDispatchers.values()) {
            dispatcher.close();
        }
        talkToDispatchers.clear();
        if (deferMemoryExtraction) {
            clientRegistry.closeAllForShutdown();
        } else {
            clientRegistry.closeAll();
        }
        if (startupCoordinator != null) {
            startupCoordinator.close();
        }
        try {
            eventSink.close();
        } catch (RuntimeException e) {
            logger.warn("Fast Team event sink close failed", e);
        }
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(
                    cleanupTimeoutMillis, TimeUnit.MILLISECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        pendingCleanup.clear();
        autoNewSessionLastChecks.clear();
        teams.clear();
    }

    private TeamCommandResult finalizeDelete(
            TeamRuntime runtime, String requestId) throws IOException {
        TeamDefinition deleting = runtime.getDefinition();
        if (deleting.getState() != TeamState.DELETING) {
            throw new IOException("Team is not DELETING");
        }
        List<TeamError> warnings = new ArrayList<>();
        cleanupEphemeralResourcesForDelete(deleting.getTeamId());

        Future<List<IOException>> closeFuture = cleanupExecutor.submit(
                () -> clientRegistry.closeTeam(deleting.getTeamId()));
        try {
            List<IOException> closeErrors =
                    closeFuture.get(cleanupTimeoutMillis, TimeUnit.MILLISECONDS);
            for (IOException closeError : closeErrors) {
                warnings.add(cleanupWarning(
                        "client-close", safeMessage(closeError)));
            }
        } catch (TimeoutException e) {
            pendingCleanup.put(deleting.getTeamId(), closeFuture);
            warnings.add(cleanupWarning("client-close",
                    "Team client cleanup timed out after "
                            + cleanupTimeoutMillis + "ms; reaper will continue"));
        } catch (Exception e) {
            warnings.add(cleanupWarning("client-close", safeMessage(e)));
        }

        long deletedAt = System.currentTimeMillis();
        long archiveExpireAt = deletedAt + TOMBSTONE_TTL_MILLIS;
        try {
            historyArchiver.archive(deleting.getTeamId(), archiveExpireAt);
        } catch (Exception e) {
            warnings.add(cleanupWarning("history-archive", safeMessage(e)));
        }

        TeamState finalState = warnings.isEmpty()
                ? TeamState.DELETED : TeamState.DELETED_WITH_WARNINGS;
        TeamError lastError = warnings.isEmpty() ? null : warnings.get(0);
        TeamDefinition terminal = deleting.transitionTo(
                finalState, lastError, deletedAt);
        store.saveTeam(terminal);
        if (!runtime.publishNextDefinition(terminal)) {
            throw new IOException("Team version changed before terminal publish");
        }
        store.saveTombstone(new TeamTombstone(
                terminal.getTeamId(), requestId, finalState,
                deletedAt, archiveExpireAt, warnings));

        teams.remove(terminal.getTeamId(), runtime);
        Map<String, Object> data = deleteData(
                terminal, warnings, archiveExpireAt);
        publishEvent(runtime, TeamEventType.TEAM_DELETED, data);
        try {
            store.deleteTeam(terminal.getTeamId());
        } catch (IOException e) {
            // Tombstone 已经落盘，定义文件残留由 reaper/下次恢复忽略并再次清理。
        }
        metrics.deleteCompleted(!warnings.isEmpty());
        logger.info("team_lifecycle action=delete_completed teamId={}"
                        + " requestId={} state={} version={} warnings={}"
                        + " resourcesZero={}",
                terminal.getTeamId(), requestId, finalState,
                terminal.getVersion(), warnings.size(),
                resourceSnapshot(terminal.getTeamId()).isZero());
        return TeamCommandResult.success(
                requestId, finalState.name(),
                warnings.isEmpty() ? "Team deleted"
                        : "Team deleted with cleanup warnings",
                terminal.getVersion(), data);
    }

    private void resumeDeleting(TeamRuntime runtime) {
        if (closed.get()) return;
        runtime.getOperationLock().lock();
        try {
            TeamDefinition definition = runtime.getDefinition();
            if (definition.getState() != TeamState.DELETING) return;
            String requestId = definition.getDeleteRequestId();
            if (requestId == null || requestId.trim().isEmpty()) {
                requestId = "recovery-" + definition.getTeamId();
            }
            TeamCommandResult result = finalizeDelete(runtime, requestId);
            Optional<TeamOperationRecord> operation =
                    store.loadOperation(requestId);
            if (operation.isPresent()) {
                TeamOperationRecord existing = operation.get();
                store.saveOperation(new TeamOperationRecord(
                        requestId, TeamOperationRecord.Operation.DELETE,
                        existing.getPayloadHash(), definition.getTeamId(),
                        TeamOperationRecord.Status.SUCCEEDED,
                        gson.toJson(result), existing.getCreatedAt(),
                        existing.getExpiresAt()));
            }
        } catch (Exception e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("team", runtime.getDefinition());
            data.put("error", TeamError.of(
                    TeamErrorCode.INTERNAL_ERROR, safeMessage(e), true));
            publishEvent(runtime, TeamEventType.TEAM_DELETE_FAILED, data);
        } finally {
            runtime.getOperationLock().unlock();
        }
    }

    private TeamCommandResult deletedResult(
            String requestId, TeamDefinition terminal,
            List<TeamError> warnings, long archiveExpireAt) {
        return TeamCommandResult.success(
                requestId, terminal.getState().name(),
                "Team already deleted", terminal.getVersion(),
                deleteData(terminal, warnings, archiveExpireAt));
    }

    private Map<String, Object> deleteData(
            TeamDefinition team, List<TeamError> warnings,
            long archiveExpireAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("team", team);
        data.put("warnings", warnings);
        data.put("archiveExpireAt", archiveExpireAt);
        data.put("resources", resourceSnapshot(team.getTeamId()));
        return data;
    }

    private static TeamError cleanupWarning(String phase, String message) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("phase", phase);
        return new TeamError(TeamErrorCode.INTERNAL_ERROR,
                message, true, details, System.currentTimeMillis());
    }

    private void publishEvent(TeamRuntime runtime, TeamEventType type,
                              Object data) {
        try {
            eventSink.publish(TeamEventEnvelope.next(
                    runtime, null, null, type, data));
        } catch (RuntimeException ignored) {
            // 事件是可重建投影，不回滚权威持久状态。
        }
    }

    private void publishRecoveryEvent(
            TeamRuntime runtime, TeamEventType type, TeamError error) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("team", runtime.getDefinition());
        data.put("members", runtime.getDefinition().getMembers());
        if (error != null) data.put("error", error);
        publishEvent(runtime, type, data);
    }

    private void startMembers(TeamRuntime runtime) {
        if (startupCoordinator == null || closed.get()) {
            return;
        }
        startupCoordinator.startAsync(runtime)
                .whenComplete((result, error) -> completeStartup(runtime, result, error));
    }

    private void completeStartup(TeamRuntime runtime, TeamStartupCoordinator.Result result,
                                 Throwable failure) {
        runtime.getOperationLock().lock();
        try {
            TeamState startingState = runtime.getDefinition().getState();
            if (closed.get()
                    || (startingState != TeamState.CREATING
                    && startingState != TeamState.RECOVERING)) {
                clientRegistry.closeTeam(runtime.getDefinition().getTeamId());
                return;
            }
            TeamError teamError;
            if (failure == null && result != null) {
                teamError = result.getError();
            } else {
                teamError = TeamError.of(TeamErrorCode.CLIENT_START_FAILED,
                        failure == null ? "Team startup returned no result"
                                : safeMessage(failure), true);
            }
            boolean successful =
                    failure == null && result != null && result.isSuccessful();
            List<TeamMemberDefinition> members = result == null
                    ? markAllFailed(runtime.getDefinition().getMembers(), teamError)
                    : result.getMembers();
            TeamDefinition next = runtime.getDefinition().transitionWithMembers(
                    successful
                            ? com.mola.cmd.proxy.app.acp.team.model.TeamState.READY
                            : com.mola.cmd.proxy.app.acp.team.model.TeamState.FAILED,
                    members, teamError, System.currentTimeMillis());
            store.saveTeam(next);
            if (!runtime.publishNextDefinition(next)) {
                clientRegistry.closeTeam(next.getTeamId());
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("team", next);
            data.put("members", next.getMembers());
            if (teamError != null) {
                data.put("error", teamError);
            }
            try {
                eventSink.publish(TeamEventEnvelope.next(runtime, null, null,
                        startingState == TeamState.RECOVERING
                                ? (successful ? TeamEventType.TEAM_RECOVERED
                                : TeamEventType.TEAM_RECOVERY_FAILED)
                                : (successful ? TeamEventType.TEAM_READY
                                : TeamEventType.TEAM_CREATE_FAILED), data));
            } catch (RuntimeException ignored) {
                // callback 只是可重建投影，失败不得改变已持久化的权威状态或回滚 client。
            }
            if (!successful) {
                cleanupTalkTo(next.getTeamId());
                runtime.stopAcceptingRequests();
            }
        } catch (Exception e) {
            cleanupTalkTo(runtime.getDefinition().getTeamId());
            clientRegistry.closeTeam(runtime.getDefinition().getTeamId());
            runtime.stopAcceptingRequests();
        } finally {
            runtime.getOperationLock().unlock();
        }
    }

    private static List<TeamMemberDefinition> markAllFailed(
            List<TeamMemberDefinition> members, TeamError error) {
        TeamError resolved = error == null
                ? TeamError.of(TeamErrorCode.CLIENT_START_FAILED,
                "Team startup failed", true) : error;
        List<TeamMemberDefinition> failed = new ArrayList<>();
        for (TeamMemberDefinition member : members) {
            failed.add(member.withState(
                    com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.ERROR,
                    null, resolved));
        }
        return failed;
    }

    private TeamCommandResult replaceSession(String requestId, TeamMemberCommand command,
                                             TeamMemberStartOptions options) {
        MemberRoute route;
        try {
            route = requireMemberRoute(command, true);
            ensureGrantActive(route.team);
        } catch (MemberRouteException e) {
            return TeamCommandResult.error(requestId, e.code, e.getMessage());
        }
        if (startupCoordinator == null) {
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.TEAM_NOT_READY, "Team startup coordinator is unavailable");
        }
        route.runtime.getOperationLock().lock();
        try {
            AcpClient old;
            if (options.isForceNewSession()
                    && route.member.getState() == TeamMemberState.SLEEP) {
                // The old runtime is being discarded; waking it would emit an
                // orphan card and may rotate the session a second time.
                old = requireExistingClient(route);
                if (old.getState() != AbstractAcpClient.State.SLEEP
                        && old.getState() != AbstractAcpClient.State.READY) {
                    throw new MemberRouteException(TeamErrorCode.MEMBER_BUSY,
                            "Team member is not READY or SLEEP");
                }
            } else {
                old = options.isForceNewSession()
                    && route.member.getState() == TeamMemberState.ERROR
                    ? clientRegistry.get(route.team.getTeamId(),
                            route.member.getTeamMemberId()).orElse(null)
                    : requireReadyClient(route);
            }
            publishMemberState(route.runtime, route.member.getTeamMemberId(),
                    com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.STARTING, null);
            clientRegistry.remove(route.team.getTeamId(), route.member.getTeamMemberId());
            if (old != null) {
                try { old.close(); }
                catch (IOException ignored) { }
            }
            TeamMemberDefinition starting = findMember(
                    route.runtime.getDefinition(), route.member.getTeamMemberId());
            AcpClient replacement = startupCoordinator.replaceMember(
                    route.runtime, starting, options);
            if (!clientRegistry.register(route.team.getTeamId(),
                    route.member.getTeamMemberId(), replacement)) {
                replacement.close();
                throw new IllegalStateException("duplicate Team member client");
            }
            publishMemberState(route.runtime, route.member.getTeamMemberId(),
                    com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.READY, null);
            TeamMemberDefinition ready = findMember(
                    route.runtime.getDefinition(), route.member.getTeamMemberId());
            Map<String, Object> data = memberData(route.runtime, ready, replacement);
            data.put("restored", options.getTargetRestoreSessionId() != null);
            return TeamCommandResult.success(requestId, "OK",
                    options.getTargetRestoreSessionId() == null
                            ? "Team member session created"
                            : "Team member session restored",
                    route.runtime.getDefinition().getVersion(), data);
        } catch (Exception e) {
            TeamError error = TeamError.of(TeamErrorCode.CLIENT_START_FAILED,
                    safeMessage(e), true);
            try {
                publishMemberState(route.runtime, route.member.getTeamMemberId(),
                        com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.ERROR, error);
            } catch (Exception ignored) {
            }
            return TeamCommandResult.error(requestId,
                    TeamErrorCode.CLIENT_START_FAILED, safeMessage(e));
        } finally {
            route.runtime.getOperationLock().unlock();
        }
    }

    private MemberRoute requireMemberRoute(TeamMemberCommand command, boolean requireReady)
            throws MemberRouteException {
        if (command == null) {
            throw new MemberRouteException(
                    TeamErrorCode.VALIDATION_ERROR, "payload is required");
        }
        try {
            requireSchema(command.getSchemaVersion());
            requireText(command.getOwnerChatterId(), "ownerChatterId");
            requireSafeId(command.getTeamId(), "teamId");
            requireSafeId(command.getTeamMemberId(), "teamMemberId");
        } catch (IllegalArgumentException e) {
            throw new MemberRouteException(TeamErrorCode.VALIDATION_ERROR, e.getMessage());
        }
        TeamRuntime runtime = teams.get(command.getTeamId());
        if (runtime == null) {
            throw new MemberRouteException(TeamErrorCode.NOT_FOUND, "Team not found");
        }
        TeamDefinition team = runtime.getDefinition();
        if (!command.getOwnerChatterId().equals(team.getOwnerChatterId())) {
            throw new MemberRouteException(
                    TeamErrorCode.UNAUTHORIZED, "Team does not belong to ownerChatterId");
        }
        if (team.getState() == TeamState.DELETING) {
            throw new MemberRouteException(
                    TeamErrorCode.TEAM_DELETING, "Team is DELETING");
        }
        TeamMemberDefinition member = findMember(team, command.getTeamMemberId());
        if (member == null) {
            throw new MemberRouteException(
                    TeamErrorCode.NOT_FOUND, "Team member not found");
        }
        if (command.getAcpClientId() != null
                && !command.getAcpClientId().trim().isEmpty()
                && !member.getAcpClientId().equals(command.getAcpClientId())) {
            throw new MemberRouteException(
                    TeamErrorCode.VALIDATION_ERROR, "acpClientId does not match teamMemberId");
        }
        if (requireReady && team.getState()
                != com.mola.cmd.proxy.app.acp.team.model.TeamState.READY) {
            throw new MemberRouteException(
                    TeamErrorCode.TEAM_NOT_READY, "Team is not READY");
        }
        return new MemberRoute(runtime, team, member);
    }

    private AcpClient requireExistingClient(MemberRoute route)
            throws MemberRouteException {
        return clientRegistry.get(route.team.getTeamId(), route.member.getTeamMemberId())
                .orElseThrow(() -> new MemberRouteException(
                        TeamErrorCode.CLIENT_CLOSED,
                        "Team member client is not available"));
    }

    private AcpClient requireReadyClient(MemberRoute route)
            throws MemberRouteException {
        AcpClient client = requireExistingClient(route);
        if (route.member.getState() == TeamMemberState.SLEEP
                && client.getState() == AbstractAcpClient.State.SLEEP) {
            try {
                publishMemberState(route.runtime, route.member.getTeamMemberId(),
                        TeamMemberState.STARTING, null);
                client.wakeIfSleeping();
                publishMemberState(route.runtime, route.member.getTeamMemberId(),
                        TeamMemberState.READY, null);
            } catch (Exception e) {
                throw new MemberRouteException(
                        TeamErrorCode.CLIENT_START_FAILED, "唤醒 Team member 失败: " + safeMessage(e));
            }
        }
        if (client.getState() != AbstractAcpClient.State.READY) {
            throw new MemberRouteException(
                    TeamErrorCode.MEMBER_BUSY, "Team member is not READY");
        }
        return client;
    }

    /** cancel 允许 READY/BUSY，其他成员或 client 生命周期状态一律拒绝。 */
    private AcpClient requireCancellableClient(MemberRoute route)
            throws MemberRouteException {
        if (route.team.getState() != TeamState.READY) {
            throw new MemberRouteException(
                    TeamErrorCode.TEAM_NOT_READY, "Team is not READY");
        }
        com.mola.cmd.proxy.app.acp.team.model.TeamMemberState memberState =
                route.member.getState();
        if (memberState != com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.READY
                && memberState != com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.SLEEP
                && memberState != com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.BUSY) {
            TeamErrorCode code = memberState
                    == com.mola.cmd.proxy.app.acp.team.model.TeamMemberState.STARTING
                    ? TeamErrorCode.TEAM_NOT_READY : TeamErrorCode.CLIENT_CLOSED;
            throw new MemberRouteException(code,
                    "Team member cannot be cancelled in state " + memberState.name());
        }
        AcpClient client = requireExistingClient(route);
        AbstractAcpClient.State clientState = client.getState();
        if (clientState != AbstractAcpClient.State.READY
                && clientState != AbstractAcpClient.State.SLEEP
                && clientState != AbstractAcpClient.State.BUSY) {
            TeamErrorCode code = clientState == AbstractAcpClient.State.CREATED
                    || clientState == AbstractAcpClient.State.STARTING
                    ? TeamErrorCode.TEAM_NOT_READY : TeamErrorCode.CLIENT_CLOSED;
            throw new MemberRouteException(code,
                    "Team member client cannot be cancelled in state "
                            + clientState.name());
        }
        return client;
    }

    private void publishMemberState(
            TeamRuntime runtime, String memberId,
            com.mola.cmd.proxy.app.acp.team.model.TeamMemberState state,
            TeamError error) throws IOException {
        publishMemberState(runtime, memberId, state, error, null);
    }

    private void publishMemberState(
            TeamRuntime runtime, String memberId,
            com.mola.cmd.proxy.app.acp.team.model.TeamMemberState state,
            TeamError error, String configFingerprint) throws IOException {
        TeamDefinition current = runtime.getDefinition();
        List<TeamMemberDefinition> members = new ArrayList<>();
        TeamMemberDefinition updated = null;
        for (TeamMemberDefinition member : current.getMembers()) {
            if (member.getTeamMemberId().equals(memberId)) {
                String sessionId = member.getSessionId();
                AcpClient client = clientRegistry.get(
                        current.getTeamId(), memberId).orElse(null);
                if (client != null && client.getSessionId() != null) {
                    sessionId = client.getSessionId();
                }
                TeamMemberDefinition configured = configFingerprint == null
                        ? member : member.withConfigFingerprint(configFingerprint);
                updated = configured.withState(state, sessionId, error);
                members.add(updated);
            } else {
                members.add(member);
            }
        }
        if (updated == null) {
            throw new IllegalArgumentException("Team member not found");
        }
        TeamDefinition next = current.withMembers(members, System.currentTimeMillis());
        store.saveTeam(next);
        if (!runtime.publishNextDefinition(next)) {
            throw new IOException("Team runtime version changed while publishing member state");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("team", next);
        data.put("member", updated);
        data.put("state", state.name());
        if (error != null) {
            data.put("error", error);
        }
        try {
            eventSink.publish(TeamEventEnvelope.next(runtime,
                    updated.getTeamMemberId(), updated.getAcpClientId(),
                    TeamEventType.MEMBER_STATE_CHANGED, data));
        } catch (RuntimeException ignored) {
        }
    }

    private void publishMemberSessionChanged(
            TeamRuntime runtime, String memberId, String oldSessionId,
            String newSessionId, String reason) {
        TeamMemberDefinition member = findMember(runtime.getDefinition(), memberId);
        if (member == null || newSessionId == null || newSessionId.trim().isEmpty()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("oldSessionId", oldSessionId);
        data.put("newSessionId", newSessionId);
        data.put("reason", reason);
        try {
            eventSink.publish(TeamEventEnvelope.next(runtime,
                    member.getTeamMemberId(), member.getAcpClientId(),
                    TeamEventType.MEMBER_SESSION_CHANGED, data));
        } catch (RuntimeException ignored) {
        }
    }

    private static TeamMemberDefinition findMember(TeamDefinition definition,
                                                   String memberId) {
        for (TeamMemberDefinition member : definition.getMembers()) {
            if (member.getTeamMemberId().equals(memberId)) {
                return member;
            }
        }
        return null;
    }

    private static TeamContactRef findContact(TeamDefinition definition, String memberId) {
        if (memberId == null) return null;
        for (TeamContactRef contact : definition.getRoster()) {
            if (memberId.equals(contact.getTargetTeamMemberId())) return contact;
        }
        return null;
    }

    private void ensureGrantActive(TeamDefinition team) throws MemberRouteException {
        if (!grantsActive(team)) throw new MemberRouteException(
                TeamErrorCode.TEAM_GRANT_REVOKED,
                "remote Team sharing grant has been revoked");
    }

    private boolean grantsActive(TeamDefinition team) {
        if (!team.isMixedPlacement()) return true;
        for (TeamMemberDefinition member : team.getMembers()) {
            if (!sourceResolver.isGrantActive(member.getSourceGroupId(),
                    team.getOwnerChatterId())) return false;
        }
        return true;
    }

    private static Map<String, Object> memberData(
            TeamRuntime runtime, TeamMemberDefinition member, AcpClient client) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teamId", runtime.getDefinition().getTeamId());
        data.put("teamState", runtime.getDefinition().getState().name());
        data.put("teamMemberId", member.getTeamMemberId());
        data.put("acpClientId", member.getAcpClientId());
        data.put("memberState", member.getState().name());
        data.put("clientState", client == null ? "CLOSED" : client.getState().name());
        data.put("sessionId", client == null ? member.getSessionId() : client.getSessionId());
        return data;
    }

    private static void validateFiles(List<Map<String, String>> files) {
        if (files == null) {
            return;
        }
        if (files.size() > 10) {
            throw new IllegalArgumentException("files size must not exceed 10");
        }
        for (Map<String, String> file : files) {
            if (file == null || file.size() != 1) {
                throw new IllegalArgumentException(
                        "each files item must contain exactly one filename-to-content entry");
            }
            Map.Entry<String, String> entry = file.entrySet().iterator().next();
            String name = requireText(entry.getKey(), "file name");
            if (name.contains("/") || name.contains("\\") || ".".equals(name)
                    || "..".equals(name)) {
                throw new IllegalArgumentException("file name must be a basename");
            }
            String content = requireText(entry.getValue(), "file content");
            if (!content.startsWith("http://") && !content.startsWith("https://")) {
                try {
                    Base64.getDecoder().decode(content);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "file content must be Base64 or an HTTP(S) URL");
                }
            }
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static final class MemberRoute {
        private final TeamRuntime runtime;
        private final TeamDefinition team;
        private final TeamMemberDefinition member;

        private MemberRoute(TeamRuntime runtime, TeamDefinition team,
                            TeamMemberDefinition member) {
            this.runtime = runtime;
            this.team = team;
            this.member = member;
        }
    }

    private static final class MemberRouteException extends Exception {
        private final TeamErrorCode code;

        private MemberRouteException(TeamErrorCode code, String message) {
            super(message);
            this.code = code;
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("TeamManager is closed");
        }
    }

    private TeamCommandResult finishOperation(TeamCreateCommand command, String payloadHash,
                                              TeamCommandResult result,
                                              TeamOperationRecord.Status status)
            throws IOException {
        store.saveOperation(new TeamOperationRecord(
                command.getRequestId(), TeamOperationRecord.Operation.CREATE,
                payloadHash, command.getTeamId(), status, gson.toJson(result),
                System.currentTimeMillis(),
                System.currentTimeMillis() + 24L * 60L * 60L * 1000L));
        return result;
    }

    private TeamCommandResult finishDeleteOperation(
            TeamDeleteCommand command, String payloadHash,
            TeamCommandResult result, TeamOperationRecord.Status status)
            throws IOException {
        store.saveOperation(new TeamOperationRecord(
                command.getRequestId(), TeamOperationRecord.Operation.DELETE,
                payloadHash, command.getTeamId(), status, gson.toJson(result),
                System.currentTimeMillis(),
                System.currentTimeMillis() + OPERATION_TTL_MILLIS));
        return result;
    }

    private TeamCommandResult finishRemarksUpdateOperation(
            TeamRemarksUpdateCommand command, String payloadHash,
            TeamCommandResult result, TeamOperationRecord.Status status)
            throws IOException {
        store.saveOperation(new TeamOperationRecord(
                command.getRequestId(), TeamOperationRecord.Operation.UPDATE_REMARKS,
                payloadHash, command.getTeamId(), status, gson.toJson(result),
                System.currentTimeMillis(),
                System.currentTimeMillis() + OPERATION_TTL_MILLIS));
        return result;
    }

    private TeamCommandResult finishOperationQuietly(TeamCreateCommand command,
                                                     String payloadHash,
                                                     TeamCommandResult result) {
        try {
            return finishOperation(command, payloadHash, result,
                    TeamOperationRecord.Status.FAILED);
        } catch (IOException ignored) {
            return result;
        }
    }

    private List<TeamMemberDefinition> resolveMembers(List<TeamMemberCreateSpec> specs,
                                                      String ownerChatterId,
                                                      boolean mixedPlacement)
            throws TeamSourceResolutionException {
        List<TeamMemberDefinition> result = new ArrayList<>();
        for (TeamMemberCreateSpec spec : specs) {
            TeamSourceRobotSnapshot snapshot = sourceResolver.snapshot(
                    spec, ownerChatterId, mixedPlacement);
            AcpRobotParam robot = snapshot.copyRobotParam();
            String remark = ContactRemarkResolver.resolve(spec.getRemark(), robot);
            result.add(new TeamMemberDefinition(
                    spec.getTeamMemberId(), spec.getSourceRobotId(),
                    spec.getSourceGroupId(), robot.getName(), robot.getName(),
                    robot.getAvatar(), spec.getOrder(), remark,
                    snapshot.getConfigFingerprint()));
        }
        result.sort(Comparator.comparingInt(TeamMemberDefinition::getOrder));
        return result;
    }

    private static List<TeamContactRef> roster(TeamCreateCommand command,
                                               List<TeamMemberDefinition> localMembers) {
        if (!command.isMixedPlacement() || command.getRoster() == null) {
            List<TeamContactRef> local = new ArrayList<>();
            for (TeamMemberDefinition member : localMembers) {
                local.add(TeamContactRef.from(member));
            }
            return local;
        }
        List<TeamContactRef> result = new ArrayList<>();
        for (TeamRosterMemberSpec member : command.getRoster()) {
            result.add(new TeamContactRef(member.getTeamMemberId(), member.getAcpClientId(),
                    member.getDisplayName(), member.getRemark(), member.getOrder()));
        }
        return result;
    }

    private TeamCommandResult quotaError(TeamCreateCommand command) {
        int requested = command.getMembers().size();
        if (requested > limits.getMaxMembersPerTeam()) {
            return TeamCommandResult.error(command.getRequestId(),
                    TeamErrorCode.QUOTA_EXCEEDED,
                    "Team members exceed maxMembersPerTeam="
                            + limits.getMaxMembersPerTeam());
        }
        if (activeTeamCount() >= limits.getMaxActiveTeams()) {
            return TeamCommandResult.error(command.getRequestId(),
                    TeamErrorCode.QUOTA_EXCEEDED,
                    "Active Teams exceed maxActiveTeams="
                            + limits.getMaxActiveTeams());
        }
        if (activeMemberCount() + requested > limits.getMaxTotalMembers()) {
            return TeamCommandResult.error(command.getRequestId(),
                    TeamErrorCode.QUOTA_EXCEEDED,
                    "Team members exceed maxTotalMembers="
                            + limits.getMaxTotalMembers());
        }
        return null;
    }

    private int activeTeamCount() {
        int count = 0;
        for (TeamRuntime runtime : teams.values()) {
            if (!runtime.getDefinition().getState().isTerminal()) count++;
        }
        return count;
    }

    private int activeMemberCount() {
        int count = 0;
        for (TeamRuntime runtime : teams.values()) {
            if (!runtime.getDefinition().getState().isTerminal()) {
                count += runtime.getDefinition().getMembers().size();
            }
        }
        return count;
    }

    private static void validateCreate(TeamCreateCommand command, String transportGroup) {
        if (command == null) {
            throw new IllegalArgumentException("create payload is required");
        }
        requireSchema(command.getSchemaVersion());
        requireSafeId(command.getRequestId(), "requestId");
        requireSafeId(command.getTeamId(), "teamId");
        requireText(command.getOwnerChatterId(), "ownerChatterId");
        String name = requireText(command.getName(), "name");
        if (name.length() > 40) {
            throw new IllegalArgumentException("name length must not exceed 40");
        }
        requireText(transportGroup, "transportGroup");
        List<TeamMemberCreateSpec> members = command.getMembers();
        if (members == null || members.isEmpty() || members.size() > 10) {
            throw new IllegalArgumentException("members size must be between 1 and 10");
        }
        Set<String> memberIds = new HashSet<>();
        Set<String> sourceGroups = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (TeamMemberCreateSpec member : members) {
            if (member == null) {
                throw new IllegalArgumentException("member must not be null");
            }
            requireSafeId(member.getTeamMemberId(), "teamMemberId");
            requireText(member.getSourceRobotId(), "sourceRobotId");
            requireText(member.getSourceGroupId(), "sourceGroupId");
            if (member.getOrder() < 0
                    || !memberIds.add(member.getTeamMemberId())
                    || !sourceGroups.add(member.getSourceGroupId())
                    || !orders.add(member.getOrder())) {
                throw new IllegalArgumentException(
                        "member ids, source groups and orders must be unique");
            }
        }
        List<TeamRosterMemberSpec> roster = command.getRoster();
        if (command.isMixedPlacement()) {
            if (roster == null || roster.size() < 2 || roster.size() > 10) {
                throw new IllegalArgumentException(
                        "mixed Team roster size must be between 2 and 10");
            }
            Set<String> rosterIds = new HashSet<>();
            Set<Integer> rosterOrders = new HashSet<>();
            for (TeamRosterMemberSpec item : roster) {
                if (item == null) throw new IllegalArgumentException("roster member must not be null");
                requireSafeId(item.getTeamMemberId(), "roster.teamMemberId");
                String id = item.getTeamMemberId().trim();
                String clientId = requireText(item.getAcpClientId(), "roster.acpClientId");
                if (!clientId.equals("team-acp-" + id)) {
                    throw new IllegalArgumentException("roster acpClientId must match teamMemberId");
                }
                requireText(item.getDisplayName(), "roster.displayName");
                if (item.getOrder() < 0 || !rosterIds.add(id)
                        || !rosterOrders.add(item.getOrder())) {
                    throw new IllegalArgumentException("roster ids and orders must be unique");
                }
            }
            if (!rosterIds.containsAll(memberIds)) {
                throw new IllegalArgumentException("all local members must belong to roster");
            }
        } else if (roster != null && !roster.isEmpty()) {
            throw new IllegalArgumentException("roster requires mixedPlacement=true");
        }
        TeamMode mode = TeamMode.fromWire(command.getMode());
        String captainId = normalizeOptional(command.getCaptainTeamMemberId());
        if (mode == TeamMode.NORMAL) {
            if (captainId != null) {
                throw new IllegalArgumentException(
                        "NORMAL Team must not define captainTeamMemberId");
            }
            return;
        }
        if (captainId == null) {
            throw new TeamValidationException(TeamErrorCode.CAPTAIN_REQUIRED,
                    "captainTeamMemberId is required for CAPTAIN Team");
        }
        Set<String> authoritativeIds = command.isMixedPlacement()
                ? new HashSet<>() : memberIds;
        if (command.isMixedPlacement()) {
            for (TeamRosterMemberSpec item : roster) {
                authoritativeIds.add(item.getTeamMemberId().trim());
            }
        }
        if (authoritativeIds.size() < 2) {
            throw new TeamValidationException(TeamErrorCode.CAPTAIN_REQUIRED,
                    "CAPTAIN Team must contain a captain and at least one member");
        }
        if (!authoritativeIds.contains(captainId)) {
            throw new TeamValidationException(TeamErrorCode.CAPTAIN_REQUIRED,
                    "captainTeamMemberId must belong to Team roster");
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static final class TeamValidationException extends IllegalArgumentException {
        private final TeamErrorCode code;

        private TeamValidationException(TeamErrorCode code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static void validateDelete(TeamDeleteCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("delete payload is required");
        }
        requireSchema(command.getSchemaVersion());
        requireSafeId(command.getRequestId(), "requestId");
        requireSafeId(command.getTeamId(), "teamId");
        requireText(command.getOwnerChatterId(), "ownerChatterId");
        if (command.getExpectedVersion() != null
                && command.getExpectedVersion().longValue() < 1L) {
            throw new IllegalArgumentException(
                    "expectedVersion must be positive");
        }
    }

    private static void validateMembersUpdate(TeamMembersUpdateCommand command) {
        if (command == null) throw new IllegalArgumentException("update payload is required");
        requireSchema(command.getSchemaVersion());
        requireSafeId(command.getRequestId(), "requestId");
        requireSafeId(command.getTeamId(), "teamId");
        requireText(command.getOwnerChatterId(), "ownerChatterId");
        List<TeamMemberCreateSpec> members = command.getMembers();
        if (members == null || members.isEmpty() || members.size() > 10) {
            throw new IllegalArgumentException("members size must be between 1 and 10");
        }
        Set<String> ids = new HashSet<>();
        Set<String> groups = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (TeamMemberCreateSpec member : members) {
            if (member == null) throw new IllegalArgumentException("member is required");
            requireSafeId(member.getTeamMemberId(), "teamMemberId");
            requireText(member.getSourceRobotId(), "sourceRobotId");
            requireText(member.getSourceGroupId(), "sourceGroupId");
            if (member.getOrder() < 0 || !ids.add(member.getTeamMemberId())
                    || !groups.add(member.getSourceGroupId())
                    || !orders.add(member.getOrder())) {
                throw new IllegalArgumentException(
                        "member ids, source groups and orders must be unique");
            }
            if (member.getRemark() != null && member.getRemark().length() > 200) {
                throw new IllegalArgumentException("member remark is too long");
            }
        }
    }

    private static void validateRemarksUpdate(TeamRemarksUpdateCommand command) {
        if (command == null) throw new IllegalArgumentException("command is required");
        requireSchema(command.getSchemaVersion());
        requireSafeId(command.getRequestId(), "requestId");
        requireText(command.getOwnerChatterId(), "ownerChatterId");
        requireSafeId(command.getTeamId(), "teamId");
        if (command.getExpectedVersion() != null
                && command.getExpectedVersion().longValue() < 1L) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
        if (command.getMemberRemarks() == null) {
            throw new IllegalArgumentException("memberRemarks is required");
        }
    }

    private static void validateMemberRemarks(
            TeamDefinition team, Map<String, String> memberRemarks) {
        Set<String> expected = team.getMembers().stream()
                .map(TeamMemberDefinition::getTeamMemberId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!expected.equals(memberRemarks.keySet())) {
            throw new IllegalArgumentException(
                    "memberRemarks must contain every Team member exactly once");
        }
        for (Map.Entry<String, String> entry : memberRemarks.entrySet()) {
            String remark = entry.getValue();
            if (remark != null && remark.length() > 200) {
                throw new IllegalArgumentException(
                        "member remark must not exceed 200 characters");
            }
        }
    }

    private static void validateQuery(TeamQuery query, boolean teamRequired) {
        if (query == null) {
            throw new IllegalArgumentException("query payload is required");
        }
        requireSchema(query.getSchemaVersion());
        requireText(query.getOwnerChatterId(), "ownerChatterId");
        if (teamRequired) {
            requireSafeId(query.getTeamId(), "teamId");
        }
    }

    private static void requireSchema(String schemaVersion) {
        if (!TeamDefinition.SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported schemaVersion");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static void requireSafeId(String value, String field) {
        String text = requireText(value, field);
        if (!text.matches("[a-zA-Z0-9._-]+") || ".".equals(text) || "..".equals(text)) {
            throw new IllegalArgumentException(field + " is not safe");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                result.append(String.format("%02x", b & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
