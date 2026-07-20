package dev.nathan.sbaagentic.runner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.nathan.sbaagentic.runner.gate.StoryFrontmatter;
import dev.nathan.sbaagentic.runner.gate.StoryFrontmatterParser;
import dev.nathan.sbaagentic.runner.process.ProcessRunner;
import dev.nathan.sbaagentic.runner.process.ProcessRunner.ProcessResult;
import dev.nathan.sbaagentic.runner.ship.ShipExecutor;
import dev.nathan.sbaagentic.runner.ship.ShipExecutor.ShipResult;
import dev.nathan.sbaagentic.workflow.Task;
import dev.nathan.sbaagentic.workflow.TaskEvent;
import dev.nathan.sbaagentic.workflow.TaskEventType;
import dev.nathan.sbaagentic.workflow.TaskSnapshot;
import dev.nathan.sbaagentic.workflow.TaskSpec;
import dev.nathan.sbaagentic.workflow.TaskStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SdlcApprovalReconciler {

    private static final Logger log = LoggerFactory.getLogger(SdlcApprovalReconciler.class);
    private static final String PLAN_LANE = "sdlc:plan";
    private static final String REVIEW_LANE = "sdlc:review";
    private static final String BUILD_LANE = "auto";
    private static final String SDLC_SHIPPED = "shipped";
    private static final String SDLC_REJECTION_RECORDED = "rejection_recorded";
    private static final String WORKER_ACTOR = "blackbox-runner-worker";
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    private final BlackBoxApiClient apiClient;
    private final StoryFrontmatterParser frontmatterParser;
    private final ShipExecutor shipExecutor;
    private final ProcessRunner processRunner;
    private final SdlcTaskChainer taskChainer;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    public SdlcApprovalReconciler(
            BlackBoxApiClient apiClient,
            StoryFrontmatterParser frontmatterParser,
            ShipExecutor shipExecutor,
            ProcessRunner processRunner,
            SdlcTaskChainer taskChainer) {
        this.apiClient = apiClient;
        this.frontmatterParser = frontmatterParser;
        this.shipExecutor = shipExecutor;
        this.processRunner = processRunner;
        this.taskChainer = taskChainer;
    }

    public SdlcApprovalReconciler(
            BlackBoxApiClient apiClient,
            StoryFrontmatterParser frontmatterParser,
            ShipExecutor shipExecutor,
            ProcessRunner processRunner) {
        this(
                apiClient,
                frontmatterParser,
                shipExecutor,
                processRunner,
                new SdlcTaskChainer(apiClient));
    }

    public void reconcile(RunnerConfig config, String actorId) {
        reconcileBuildSuccessors(actorId);
        reconcileLane(PLAN_LANE, config, actorId);
        reconcileLane(REVIEW_LANE, config, actorId);
    }

    public void reconcileTask(String taskId, RunnerConfig config, String actorId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        for (String lane : List.of(PLAN_LANE, REVIEW_LANE)) {
            Optional<TaskSnapshot> matching = safeList(apiClient.listTasks("done", lane)).stream()
                    .filter(Objects::nonNull)
                    .filter(snapshot -> snapshot.task() != null)
                    .filter(snapshot -> taskId.equals(snapshot.task().id()))
                    .findFirst();
            if (matching.isPresent()) {
                reconcileSnapshot(matching.orElseThrow(), config, actorId);
                return;
            }
        }
    }

    private void reconcileLane(String lane, RunnerConfig config, String actorId) {
        for (TaskSnapshot snapshot : safeList(apiClient.listTasks("done", lane))) {
            if (snapshot == null || snapshot.task() == null) {
                continue;
            }
            try {
                reconcileSnapshot(snapshot, config, actorId);
            }
            catch (RuntimeException ex) {
                log.warn("Unable to reconcile SDLC approval for task {}", snapshot.task().id(), ex);
            }
        }
    }

    private void reconcileSnapshot(TaskSnapshot snapshot, RunnerConfig config, String actorId) {
        Task task = snapshot.task();
        if (task.status() != TaskStatus.DONE
                || (!PLAN_LANE.equals(task.lane()) && !REVIEW_LANE.equals(task.lane()))
                || !inFlight.add(task.id())) {
            return;
        }

        try {
            TaskSpec spec = snapshot.spec() == null
                    ? apiClient.getSpec(task.specId())
                    : snapshot.spec();
            Optional<StoryFrontmatterParser.ParsedStory> parsed = frontmatterParser.parse(spec.body());
            if (parsed.isEmpty()
                    || !"sdlc".equals(parsed.orElseThrow().frontmatter().mode())) {
                return;
            }

            String stage = PLAN_LANE.equals(task.lane()) ? "plan" : "review";
            List<TaskEvent> events = safeList(apiClient.taskEvents(task.id()));
            if (PLAN_LANE.equals(task.lane())) {
                reconcilePlan(task, spec, stage, events, actorId);
            }
            else {
                reconcileReview(
                        task,
                        spec,
                        parsed.orElseThrow().frontmatter(),
                        stage,
                        events,
                        config,
                        actorId);
            }
        }
        finally {
            inFlight.remove(task.id());
        }
    }

    private void reconcilePlan(
            Task task,
            TaskSpec spec,
            String stage,
            List<TaskEvent> events,
            String actorId) {
        if (existingBuildTask(spec.id()).isPresent()
                || hasRunnerMarker(events, actorId, SDLC_REJECTION_RECORDED)) {
            return;
        }

        Optional<Approval> rejection = latestRejection(events, stage);
        if (rejection.isPresent()) {
            recordRejection(task, rejection.orElseThrow(), actorId);
            return;
        }

        Optional<Approval> approval = latestApproval(events, stage);
        if (approval.isEmpty() || !"approve".equals(approval.orElseThrow().decision())) {
            return;
        }
        if (latestWorkerAnnotationText(events, "plan").isEmpty()) {
            log.warn(
                    "SDLC plan task {} is approved without a worker plan annotation; refusing to enqueue build",
                    task.id());
            return;
        }

        taskChainer.ensureTask(
                spec.id(), task.title(), BUILD_LANE, task.priority(), actorId);
    }

    private void reconcileReview(
            Task task,
            TaskSpec spec,
            StoryFrontmatter frontmatter,
            String stage,
            List<TaskEvent> events,
            RunnerConfig config,
            String actorId) {
        Optional<ShipMarker> shipMarker = latestShipMarker(events, actorId);
        if (shipMarker.isPresent()) {
            pruneMergedWorktreeIfNeeded(
                    task.id(), actorId, frontmatter, config, shipMarker.orElseThrow());
            return;
        }
        if (hasRunnerMarker(events, actorId, SDLC_REJECTION_RECORDED)) {
            return;
        }

        Optional<Approval> rejection = latestRejection(events, stage);
        if (rejection.isPresent()) {
            recordRejection(task, rejection.orElseThrow(), actorId);
            return;
        }

        Optional<Approval> approval = latestApproval(events, stage);
        if (approval.isEmpty() || !"approve".equals(approval.orElseThrow().decision())) {
            return;
        }
        Optional<String> reviewSummary = latestWorkerAnnotationText(events, "review");
        if (reviewSummary.isEmpty()) {
            log.warn(
                    "SDLC review task {} is approved without a worker review annotation; refusing to ship",
                    task.id());
            return;
        }

        Optional<RepoConfig> repoConfig = matchingRepo(config, frontmatter.repo());
        Optional<BuildState> buildState = findBuildState(spec.id(), actorId);
        if (repoConfig.isEmpty() || buildState.isEmpty()) {
            log.warn(
                    "SDLC review task {} is approved, but its configured repo or preserved build "
                            + "worktree is unavailable; refusing to ship",
                    task.id());
            return;
        }

        BuildState state = buildState.orElseThrow();
        RepoConfig repo = repoConfig.orElseThrow();
        Optional<Path> worktree = validatedWorktree(repo, state, true);
        if (worktree.isEmpty()) {
            log.warn(
                    "SDLC review task {} is approved, but preserved worktree {} failed validation; "
                            + "refusing to ship",
                    task.id(), state.worktree());
            return;
        }

        ShipResult result = shipExecutor.shipForSdlc(
                task.id(),
                actorId,
                repo,
                state.branch(),
                worktree.orElseThrow().toFile(),
                task.title(),
                reviewSummary.orElseThrow(),
                approval.orElseThrow().id());
        if ("merged".equals(result.status())) {
            pruneMergedWorktree(task.id(), actorId, repo, worktree.orElseThrow());
        }
    }

    private void reconcileBuildSuccessors(String actorId) {
        for (TaskSnapshot snapshot : safeList(apiClient.listTasks("done", BUILD_LANE))) {
            if (snapshot == null || snapshot.task() == null) {
                continue;
            }
            Task build = snapshot.task();
            try {
                TaskSpec spec = snapshot.spec() == null
                        ? apiClient.getSpec(build.specId())
                        : snapshot.spec();
                Optional<StoryFrontmatterParser.ParsedStory> parsed = frontmatterParser.parse(spec.body());
                if (parsed.isEmpty()
                        || !"sdlc".equals(parsed.orElseThrow().frontmatter().mode())) {
                    continue;
                }
                List<TaskEvent> events = safeList(apiClient.taskEvents(build.id()));
                if (latestBuildState(events, actorId, build).isEmpty() || !hasWorkerDone(events)) {
                    continue;
                }
                taskChainer.ensureTask(
                        spec.id(), build.title(), REVIEW_LANE, build.priority(), actorId);
            }
            catch (RuntimeException ex) {
                log.warn("Unable to reconcile SDLC review successor for build {}", build.id(), ex);
            }
        }
    }

    private Optional<Task> existingBuildTask(String specId) {
        return safeList(apiClient.listTasks(null, BUILD_LANE)).stream()
                .filter(Objects::nonNull)
                .map(TaskSnapshot::task)
                .filter(Objects::nonNull)
                .filter(task -> specId.equals(task.specId()))
                // BLOCKED counts as acted-on: the approval already produced a successor and
                // it needs human intervention. Excluding it turns every reconcile poll into
                // another enqueue — a runaway that flooded a live board with blocked tasks.
                .filter(task -> task.status() == TaskStatus.OPEN
                        || task.status() == TaskStatus.IN_PROGRESS
                        || task.status() == TaskStatus.DONE
                        || task.status() == TaskStatus.BLOCKED)
                .findFirst();
    }

    private Optional<BuildState> findBuildState(String specId, String actorId) {
        List<Task> builds = safeList(apiClient.listTasks(null, BUILD_LANE)).stream()
                .filter(Objects::nonNull)
                .map(TaskSnapshot::task)
                .filter(Objects::nonNull)
                .filter(task -> specId.equals(task.specId()))
                .filter(task -> task.status() == TaskStatus.DONE)
                .sorted(Comparator.comparing(
                                Task::createdAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .reversed())
                .toList();
        for (Task build : builds) {
            Optional<BuildState> state = latestBuildState(
                    safeList(apiClient.taskEvents(build.id())), actorId, build);
            if (state.isPresent()) {
                return state;
            }
        }
        return Optional.empty();
    }

    private Optional<BuildState> latestBuildState(
            List<TaskEvent> events, String actorId, Task build) {
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (!isRunnerProgress(event, actorId)) {
                continue;
            }
            Map<?, ?> data = dataJson(event);
            String branch = stringValue(data.get("branch"));
            String worktree = stringValue(data.get("worktree"));
            if (!isBlank(branch) && !isBlank(worktree)) {
                return Optional.of(new BuildState(
                        branch, worktree, build.id(), build.title()));
            }
        }
        return Optional.empty();
    }

    private Optional<Approval> latestApproval(List<TaskEvent> events, String expectedStage) {
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (!isAnnotation(event, "approval")) {
                continue;
            }
            return parseApproval(event)
                    .filter(approval -> expectedStage.equals(approval.stage()));
        }
        return Optional.empty();
    }

    private Optional<Approval> latestRejection(List<TaskEvent> events, String expectedStage) {
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            Optional<Approval> approval = parseApproval(event);
            if (approval.isPresent()
                    && expectedStage.equals(approval.orElseThrow().stage())
                    && "reject".equals(approval.orElseThrow().decision())) {
                return approval;
            }
        }
        return Optional.empty();
    }

    private Optional<Approval> parseApproval(TaskEvent event) {
        if (!isAnnotation(event, "approval")) {
            return Optional.empty();
        }
        Map<?, ?> data = dataJson(event);
        String decision = stringValue(data.get("decision"));
        String stage = stringValue(data.get("stage"));
        if ((!"approve".equals(decision) && !"reject".equals(decision))
                || (!"plan".equals(stage) && !"review".equals(stage))) {
            return Optional.empty();
        }
        return Optional.of(new Approval(
                event.id(), decision, stage, stringValue(data.get("feedback"))));
    }

    private void recordRejection(Task task, Approval approval, String actorId) {
        String feedback = isBlank(approval.feedback())
                ? "No feedback provided."
                : approval.feedback();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sdlc", SDLC_REJECTION_RECORDED);
        data.put("approvalId", approval.id());
        data.put("decision", "reject");
        data.put("stage", approval.stage());
        data.put("feedback", feedback);
        apiClient.annotate(
                task.id(),
                actorId,
                "progress",
                "SDLC " + approval.stage() + " rejected: " + feedback,
                data);
    }

    private Optional<ShipMarker> latestShipMarker(List<TaskEvent> events, String actorId) {
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (!isRunnerProgress(event, actorId)) {
                continue;
            }
            Map<?, ?> data = dataJson(event);
            if (!SDLC_SHIPPED.equals(stringValue(data.get("sdlc")))) {
                continue;
            }
            return Optional.of(new ShipMarker(
                    stringValue(data.get("status")),
                    stringValue(data.get("branch")),
                    stringValue(data.get("worktree"))));
        }
        return Optional.empty();
    }

    private boolean hasRunnerMarker(List<TaskEvent> events, String actorId, String marker) {
        return events.stream()
                .filter(event -> isRunnerProgress(event, actorId))
                .map(SdlcApprovalReconciler::dataJson)
                .anyMatch(data -> marker.equals(stringValue(data.get("sdlc"))));
    }

    private Optional<String> latestWorkerAnnotationText(List<TaskEvent> events, String kind) {
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEvent event = events.get(index);
            if (event != null
                    && WORKER_ACTOR.equals(event.actor())
                    && isAnnotation(event, kind)) {
                String text = stringValue(event.detail().get("text"));
                if (!isBlank(text)) {
                    return Optional.of(text);
                }
            }
        }
        return Optional.empty();
    }

    private boolean hasWorkerDone(List<TaskEvent> events) {
        return events.stream().anyMatch(event -> {
            if (event == null
                    || !WORKER_ACTOR.equals(event.actor())
                    || !isAnnotation(event, "progress")) {
                return false;
            }
            Map<?, ?> data = dataJson(event);
            return "worker_done".equals(stringValue(data.get("event")))
                    && "done".equals(stringValue(data.get("outcome")));
        });
    }

    private void pruneMergedWorktreeIfNeeded(
            String taskId,
            String actorId,
            StoryFrontmatter frontmatter,
            RunnerConfig config,
            ShipMarker marker) {
        if (!"merged".equals(marker.status()) || isBlank(marker.worktree())) {
            return;
        }
        Optional<RepoConfig> repoConfig = matchingRepo(config, frontmatter.repo());
        if (repoConfig.isEmpty()) {
            return;
        }
        Optional<Path> worktree = normalizedWorktree(repoConfig.orElseThrow(), marker.worktree());
        worktree.filter(Files::isDirectory)
                .ifPresent(path -> pruneMergedWorktree(
                        taskId, actorId, repoConfig.orElseThrow(), path));
    }

    private Optional<Path> validatedWorktree(
            RepoConfig repoConfig, BuildState state, boolean requireClean) {
        Optional<Path> normalized = normalizedWorktree(repoConfig, state.worktree());
        if (normalized.isEmpty() || !Files.isDirectory(normalized.orElseThrow())) {
            return Optional.empty();
        }
        Path worktree = normalized.orElseThrow();
        Path expected = Path.of(repoConfig.path())
                .toAbsolutePath()
                .normalize()
                .resolve(RunnerNaming.worktreeDirName(state.buildTaskId()))
                .normalize();
        if (!expected.equals(worktree)
                || !RunExecutor.branchName(state.buildTitle(), state.buildTaskId())
                        .equals(state.branch())) {
            return Optional.empty();
        }
        try {
            Path realRoot = Path.of(repoConfig.path())
                    .toAbsolutePath()
                    .normalize()
                    .resolve(".worktrees")
                    .toRealPath();
            if (!worktree.toRealPath().startsWith(realRoot)) {
                return Optional.empty();
            }
        }
        catch (IOException ex) {
            return Optional.empty();
        }

        ProcessResult branch = processRunner.run(
                List.of("git", "-C", worktree.toString(), "rev-parse", "--abbrev-ref", "HEAD"),
                worktree.toFile(),
                GIT_TIMEOUT);
        if (branch.timedOut()
                || branch.exitCode() != 0
                || !state.branch().equals(safeStrip(branch.stdout()))) {
            return Optional.empty();
        }
        if (requireClean && !isClean(worktree)) {
            return Optional.empty();
        }
        return Optional.of(worktree);
    }

    private Optional<Path> normalizedWorktree(RepoConfig repoConfig, String worktreeValue) {
        if (repoConfig == null || isBlank(repoConfig.path()) || isBlank(worktreeValue)) {
            return Optional.empty();
        }
        try {
            Path worktree = Path.of(worktreeValue);
            if (!worktree.isAbsolute()) {
                return Optional.empty();
            }
            Path repoRoot = Path.of(repoConfig.path()).toAbsolutePath().normalize();
            Path normalized = worktree.normalize();
            if (!normalized.startsWith(repoRoot.resolve(".worktrees").normalize())) {
                return Optional.empty();
            }
            return Optional.of(normalized);
        }
        catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private void pruneMergedWorktree(
            String taskId, String actorId, RepoConfig repoConfig, Path worktree) {
        if (!Files.isDirectory(worktree) || !isClean(worktree)) {
            return;
        }
        File repoDir = new File(repoConfig.path());
        ProcessResult remove = processRunner.run(
                List.of(
                        "git",
                        "-C",
                        repoDir.getAbsolutePath(),
                        "worktree",
                        "remove",
                        worktree.toString(),
                        "--force"),
                repoDir,
                GIT_TIMEOUT);
        if (remove.timedOut() || remove.exitCode() != 0) {
            log.warn("Unable to remove merged SDLC worktree {}: {}", worktree, processDetail(remove));
            return;
        }

        ProcessResult prune = processRunner.run(
                List.of("git", "-C", repoDir.getAbsolutePath(), "worktree", "prune"),
                repoDir,
                GIT_TIMEOUT);
        if (prune.timedOut() || prune.exitCode() != 0) {
            log.warn("Unable to prune merged SDLC worktree metadata in {}: {}", repoDir, processDetail(prune));
            return;
        }

        Map<String, Object> data = Map.of(
                "sdlc", "worktree_pruned",
                "worktree", worktree.toString());
        try {
            apiClient.annotate(
                    taskId,
                    actorId,
                    "progress",
                    "Merged SDLC worktree removed and pruned: " + worktree,
                    data);
        }
        catch (RuntimeException ex) {
            log.warn("Unable to annotate merged SDLC worktree cleanup for task {}", taskId, ex);
        }
    }

    private boolean isClean(Path worktree) {
        try {
            ProcessResult status = processRunner.run(
                    List.of("git", "-C", worktree.toString(), "status", "--porcelain"),
                    worktree.toFile(),
                    GIT_TIMEOUT);
            return !status.timedOut()
                    && status.exitCode() == 0
                    && safeStrip(status.stdout()).isBlank();
        }
        catch (RuntimeException ex) {
            log.warn("Unable to verify SDLC worktree cleanliness at {}", worktree, ex);
            return false;
        }
    }

    private Optional<RepoConfig> matchingRepo(RunnerConfig config, String repoPath) {
        if (config == null || isBlank(repoPath)) {
            return Optional.empty();
        }
        return safeList(config.repos()).stream()
                .filter(Objects::nonNull)
                .filter(repo -> Objects.equals(repo.path(), repoPath))
                .findFirst();
    }

    private static boolean isRunnerProgress(TaskEvent event, String actorId) {
        return event != null
                && Objects.equals(actorId, event.actor())
                && isAnnotation(event, "progress");
    }

    private static boolean isAnnotation(TaskEvent event, String kind) {
        return event != null
                && event.type() == TaskEventType.NOTE
                && event.detail() != null
                && kind.equals(stringValue(event.detail().get("kind")));
    }

    private static Map<?, ?> dataJson(TaskEvent event) {
        if (event == null || event.detail() == null) {
            return Map.of();
        }
        Object data = event.detail().get("dataJson");
        return data instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String processDetail(ProcessResult result) {
        String output = !isBlank(result.stderr()) ? result.stderr().strip() : safeStrip(result.stdout());
        return "exit " + result.exitCode()
                + (result.timedOut() ? ", timed out" : "")
                + (output.isBlank() ? "" : ": " + output);
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }

    private static String safeStrip(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record Approval(String id, String decision, String stage, String feedback) {
    }

    private record BuildState(
            String branch, String worktree, String buildTaskId, String buildTitle) {
    }

    private record ShipMarker(String status, String branch, String worktree) {
    }
}
