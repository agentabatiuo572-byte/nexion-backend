package ffdd.opsconsole.platform.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppUpdateService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    @Value("${nexion.app-updates.android-download-origin:}")
    private final String androidDownloadOrigin;

    public record Text(String zh, String en, String vi) {}
    public record Artifact(String artifactId, String platform, String versionName, int latestBuild,
                           String updateType, String downloadUrl, String sha256) {}
    public record Policy(String releaseId, String artifactId, String versionName, int latestBuild, int minBuild,
                         String updateType, String downloadUrl, String sha256, Text notes, Text reasons) {}
    public record Draft(String artifactId, Integer minBuild, Text notes, Text reasons) {}
    public record Command(Long revision, UUID requestId, String reason, Draft policy) {}
    public record PublicView(String platform, String decision, Policy policy) {}
    public record Snapshot(String status, Policy live, Policy candidate, boolean restoring) {}
    public record Event(String action, String actor, String reason, String createdAt, Snapshot before, Snapshot after) {}
    public record AdminView(String platform, long revision, String status, Policy live, Policy candidate, Policy previous,
                            boolean canRestore, boolean restoring, String requestReason, List<Artifact> artifacts,
                            List<String> allowedActions, List<Event> history) {}

    // All mutable candidate fields belong to one platform row, locked throughout a command.
    public static class State {
        public String status = "idle";
        public Policy live, candidate, previous;
        public boolean canRestore, restoring;
        public Set<String> editors = new LinkedHashSet<>();
        public String submitter, approver, requestReason;
        public Policy savedDraft;
        public Set<String> savedEditors;
    }
    private record Row(long revision, State state) {}
    private record Receipt(String action, String actorId, String hash) {}

    public PublicView current(String platform, int build, Integer resourceBuild) {
        checkPlatform(platform);
        if (build < 1 || (resourceBuild != null && resourceBuild < 0)) throw new BizException("构建号无效");
        Policy live = load(platform, false).state.live;
        if (live == null || !currentArtifact(platform, live)) return new PublicView(platform, "none", null);
        String decision = build < live.minBuild ? "required" : build < live.latestBuild ? "optional" : "current";
        return new PublicView(platform, decision, live);
    }

    @Transactional(readOnly = true)
    public AdminView overview(String platform, Authentication admin) {
        checkPlatform(platform);
        return view(platform, load(platform, false), admin);
    }

    @Transactional
    public AdminView change(String platform, String action, Command command, Authentication admin) {
        checkPlatform(platform);
        if (command == null || command.revision == null || command.revision < 0 || command.requestId == null)
            throw new BizException("请提供当前修订号与操作编号");
        String reason = command.reason == null ? "" : command.reason.trim();
        if ((!action.equals("save") && reason.length() < 8) || reason.length() > 200)
            throw new BizException("操作理由须为 8 至 200 字");
        if (!action.equals("save") && command.policy != null) throw new BizException("该操作不能修改策略内容");

        Row row = load(platform, true);
        String actor = admin.getName();
        String hash = digest(encode(command));
        List<Receipt> receipts = jdbc.query(
                "SELECT action,actor_id,request_hash FROM nx_app_update_history WHERE platform=? AND request_id=?",
                (r,n) -> new Receipt(r.getString(1), r.getString(2), r.getString(3)), platform, command.requestId.toString());
        if (!receipts.isEmpty()) {
            if (!receipts.get(0).equals(new Receipt(action, actor, hash))) throw new BizException(409, "操作编号已被使用");
            return view(platform, row, admin);
        }
        if (row.revision != command.revision) throw new BizException(409, "策略已变更，请刷新");
        State state = row.state;
        if (!actions(state, admin).contains(action))
            throw new BizException(403, "当前账号或审批状态不允许这项操作");
        Snapshot before = snapshot(state);
        switch (action) {
            case "save" -> {
                Draft draft = command.policy;
                if (draft == null || draft.minBuild == null) throw new BizException("请填写版本策略");
                Artifact a = artifact(platform, draft.artifactId);
                if (state.live != null && a.latestBuild <= state.live.latestBuild)
                    throw new BizException("安装包构建号必须大于线上构建号");
                if (draft.minBuild < 1 || draft.minBuild > a.latestBuild) throw new BizException("最低支持构建号超出范围");
                Text notes = texts(draft.notes, true);
                Text reasons = draft.reasons;
                if (state.live != null && draft.minBuild <= state.live.minBuild
                        && (reasons == null || reasons.equals(new Text("", "", "")))) reasons = state.live.reasons;
                reasons = texts(reasons, draft.minBuild > 1);
                state.candidate = new Policy(UUID.randomUUID().toString(), a.artifactId, a.versionName, a.latestBuild,
                        draft.minBuild, a.updateType, a.downloadUrl, a.sha256, notes, reasons);
                state.editors.add(actor);
                state.status = "draft";
            }
            case "restore" -> {
                state.savedDraft = state.candidate;
                state.savedEditors = new LinkedHashSet<>(state.editors);
                state.candidate = state.previous;
                state.editors.clear();
                state.restoring = true;
                checkArtifact(platform, state.candidate);
                submit(state, actor, reason);
            }
            case "submit" -> {
                if (state.live != null && state.candidate.latestBuild <= state.live.latestBuild)
                    throw new BizException(409, "请重新选择高于线上版本的安装包");
                checkArtifact(platform, state.candidate);
                state.editors.add(actor);
                submit(state, actor, reason);
            }
            case "withdraw", "reject" -> finishCandidate(state, false);
            case "approve" -> {
                state.approver = actor;
                state.status = "approved";
            }
            case "publish" -> {
                checkArtifact(platform, state.candidate);
                state.previous = state.live;
                state.canRestore = true;
                state.live = state.candidate;
                finishCandidate(state, true);
            }
            default -> throw new BizException("未知操作");
        }
        long revision = row.revision + 1;
        jdbc.update("UPDATE nx_app_update_platform SET revision=?,state_json=? WHERE platform=?",
                revision, encode(state), platform);
        jdbc.update("""
                INSERT INTO nx_app_update_history(platform,revision,request_id,request_hash,actor_id,actor,action,reason,before_json,after_json)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, platform, revision, command.requestId.toString(), hash, actor, AdminActorResolver.resolve(actor), action,
                reason, encode(before), encode(snapshot(state)));
        return view(platform, new Row(revision, state), admin);
    }

    private void checkArtifact(String platform, Policy policy) {
        if (policy == null) return; // Approved recovery to the original, empty policy.
        if (!currentArtifact(platform, policy))
            throw new BizException(409, "当前产物与送审版本不一致");
    }

    private boolean currentArtifact(String platform, Policy policy) {
        return artifacts(platform).stream().anyMatch(a -> a.artifactId.equals(policy.artifactId)
                && a.latestBuild == policy.latestBuild && a.versionName.equals(policy.versionName)
                && a.downloadUrl.equals(policy.downloadUrl) && a.sha256.equals(policy.sha256));
    }

    private Artifact artifact(String platform, String id) {
        return artifacts(platform).stream().filter(a -> a.artifactId.equals(id)).findFirst()
                .orElseThrow(() -> new BizException(409, "该平台没有此可用的可信安装产物"));
    }

    private List<Artifact> artifacts(String platform) {
        return jdbc.query("""
                SELECT artifact_id,platform,version_name,latest_build,download_url,sha256 FROM nx_app_update_artifact
                WHERE platform=? AND available=TRUE ORDER BY latest_build DESC
                """, (r,n) -> new Artifact(r.getString(1),r.getString(2),r.getString(3),r.getInt(4),
                        "package",r.getString(5),r.getString(6)), platform).stream().filter(this::trusted).toList();
    }

    private boolean trusted(Artifact a) {
        if (!a.sha256.matches("[a-f0-9]{64}") || a.latestBuild < 1 || a.versionName.isBlank()) return false;
        if ("android".equals(a.platform))
            return androidDownloadOrigin.matches("https://[a-zA-Z0-9.-]+")
                    && a.downloadUrl.equals(androidDownloadOrigin + "/app-updates/android/" + a.sha256 + ".apk");
        return a.downloadUrl.matches("https://apps[.]apple[.]com/(?:[a-z]{2}/)?app/(?:[A-Za-z0-9_%.-]+/)?id[0-9]+");
    }

    private void submit(State s, String actor, String reason) {
        s.status = "pending";
        s.submitter = actor;
        s.requestReason = reason;
    }

    private void finishCandidate(State s, boolean published) {
        if (s.restoring) {
            s.candidate = s.savedDraft;
            s.editors = s.savedEditors;
            s.savedDraft = null;
            s.savedEditors = null;
        } else if (published) {
            s.candidate = null;
            s.editors.clear();
        }
        s.restoring = false;
        s.submitter = s.approver = s.requestReason = null;
        s.status = s.candidate == null ? "idle" : "draft";
    }

    private List<String> actions(State s, Authentication admin) {
        String actor = admin.getName();
        boolean write = admin.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("platform_a3_write"));
        boolean approve = admin.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("platform_a2_operation_approve"));
        List<String> actions = new ArrayList<>();
        if (write && (s.status.equals("idle") || s.status.equals("draft"))) {
            actions.add("save");
            if (s.status.equals("draft")) actions.add("submit");
            if (s.canRestore) actions.add("restore");
        }
        if (s.status.equals("pending")) {
            if (write && actor.equals(s.submitter)) actions.add("withdraw");
            if (approve && !actor.equals(s.submitter) && !s.editors.contains(actor)) actions.addAll(List.of("approve","reject"));
        }
        if (write && s.status.equals("approved") && !actor.equals(s.approver)) actions.add("publish");
        return actions;
    }

    private static Text texts(Text t, boolean required) {
        if (t == null) t = new Text("", "", "");
        return new Text(text(t.zh, required), text(t.en, required), text(t.vi, required));
    }

    private static String text(String value, boolean required) {
        String clean = value == null ? "" : value.strip();
        if ((required && clean.isEmpty()) || clean.length() > 2000) throw new BizException("请检查三种语言的说明，单项上限 2000 字");
        return clean;
    }

    private static void checkPlatform(String p) {
        if (!"android".equals(p) && !"ios".equals(p)) throw new BizException("请选择 Android 或 iOS");
    }

    private Row load(String platform, boolean lock) {
        String sql = "SELECT revision,state_json FROM nx_app_update_platform WHERE platform=?" + (lock ? " FOR UPDATE" : "");
        return jdbc.queryForObject(sql, (r,n) -> new Row(r.getLong(1), decode(r.getString(2), State.class)), platform);
    }

    private Snapshot snapshot(State s) { return new Snapshot(s.status, s.live, s.candidate, s.restoring); }

    private AdminView view(String platform, Row row, Authentication admin) {
        State s = row.state;
        List<Event> history = jdbc.query("""
                SELECT action,actor,reason,created_at,before_json,after_json FROM nx_app_update_history
                WHERE platform=? ORDER BY revision DESC LIMIT 100
                """, (r,n) -> new Event(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant().toString(),
                        decode(r.getString(5),Snapshot.class),decode(r.getString(6),Snapshot.class)),platform);
        return new AdminView(platform,row.revision,s.status,s.live,s.candidate,s.previous,s.canRestore,s.restoring,
                s.requestReason,artifacts(platform),actions(s,admin),history);
    }

    private String encode(Object object) {
        try { return json.writeValueAsString(object); }
        catch (JsonProcessingException e) { throw new IllegalStateException("版本策略无法序列化", e); }
    }

    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value,type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("版本策略记录损坏", e); }
    }

    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
