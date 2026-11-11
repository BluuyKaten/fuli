package com.fuli.trade.practice;

import com.alibaba.fastjson2.JSON;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * One database transaction owns every state transition. Market snapshots are insert-only,
 * so they are read through {@link PracticeSnapshotCache} instead of being re-read (and re-parsed)
 * from {@code snapshot_json} on every mutation.
 */
@Service
public class PracticeService {
    private final JdbcTemplate jdbc;
    private final PracticeSnapshotLoader loader;
    private final PracticeSnapshotCache snapshots;

    public PracticeService(JdbcTemplate jdbc, PracticeSnapshotLoader loader, PracticeSnapshotCache snapshots) {
        this.jdbc = jdbc;
        this.loader = loader;
        this.snapshots = snapshots;
    }

    @Transactional
    public PracticeDtos.PracticeView create(long userId, PracticeDtos.CreateRequest request) {
        PracticeSnapshotLoader.Inputs input = loader.inputs(request);
        PracticeState.Snapshot snapshot = loader.load(input);
        PracticeState state = PracticeEngine.create(input, snapshot);
        Timestamp now = Timestamp.from(Instant.parse(state.createdAt));
        jdbc.update("INSERT INTO practice_session (id,user_id,stock_code,start_date,end_date,current_day,status,equity,initial_cash,version,state_json,snapshot_json,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                state.id, userId, state.stockCode, state.startDate, state.endDate,
                currentDate(state), state.status, state.equity, state.initialCash,
                state.version, JSON.toJSONString(state), JSON.toJSONString(snapshot), now, now);
        // 刚建好的快照直接入缓存：前端创建后马上 GET 详情/列表，那一次不必再读大字段。
        snapshots.put(state.id, snapshot);
        return PracticeEngine.view(state, snapshot);
    }

    @Transactional(readOnly = true)
    public List<PracticeDtos.Summary> list(long userId) {
        // 摘要列命中时无需解析完整 state_json；历史行摘要列为 NULL 时回退解析兜底。
        return jdbc.query("SELECT id,stock_code,start_date,end_date,current_day,status,version,equity,initial_cash,created_at,state_json FROM practice_session WHERE user_id=? ORDER BY created_at DESC,id DESC",
                (rs, row) -> {
                    String stockCode = rs.getString("stock_code");
                    if (stockCode == null) {
                        PracticeState state = JSON.parseObject(rs.getString("state_json"), PracticeState.class);
                        state.version = rs.getLong("version");
                        return PracticeEngine.summary(state);
                    }
                    BigDecimal equity = rs.getBigDecimal("equity");
                    return new PracticeDtos.Summary(rs.getString("id"), stockCode,
                            rs.getString("start_date"), rs.getString("end_date"),
                            rs.getString("current_day"), rs.getString("status"),
                            rs.getLong("version"), equity,
                            returnRate(equity, rs.getBigDecimal("initial_cash")),
                            toIso(rs.getTimestamp("created_at")));
                }, userId);
    }

    @Transactional(readOnly = true)
    public PracticeDtos.PracticeView get(long userId, String id) {
        Stored stored = load(userId, id, false);
        return PracticeEngine.view(stored.state(), stored.snapshot());
    }

    @Transactional
    public PracticeDtos.PracticeView order(long userId, String id, PracticeDtos.OrderRequest request) {
        return mutate(userId, id, request == null ? null : request.expectedVersion(),
                (state, snapshot) -> PracticeEngine.order(state, snapshot, request));
    }

    @Transactional
    public PracticeDtos.PracticeView cancel(long userId, String id, PracticeDtos.VersionRequest request) {
        return mutate(userId, id, request == null ? null : request.expectedVersion(), (state, snapshot) -> PracticeEngine.cancel(state));
    }

    @Transactional
    public PracticeDtos.PracticeView advance(long userId, String id, PracticeDtos.VersionRequest request) {
        return mutate(userId, id, request == null ? null : request.expectedVersion(), PracticeEngine::advance);
    }

    @Transactional
    public PracticeDtos.PracticeView finish(long userId, String id, PracticeDtos.VersionRequest request) {
        return mutate(userId, id, request == null ? null : request.expectedVersion(), (state, snapshot) -> PracticeEngine.finish(state));
    }

    @Transactional
    public PracticeDtos.PracticeView annotations(long userId, String id, PracticeDtos.AnnotationsRequest request) {
        return mutate(userId, id, request == null ? null : request.expectedVersion(),
                (state, snapshot) -> PracticeEngine.annotations(state, snapshot, request.annotations()));
    }

    private PracticeDtos.PracticeView mutate(long userId, String id, Long expectedVersion,
                                             BiConsumer<PracticeState, PracticeState.Snapshot> operation) {
        Stored stored = load(userId, id, true);
        PracticeState state = stored.state();
        PracticeEngine.requireMutable(state, expectedVersion);
        operation.accept(state, stored.snapshot());
        long previous = state.version;
        state.version++;
        int updated = jdbc.update("UPDATE practice_session SET state_json=?,status=?,current_day=?,equity=?,version=?,updated_at=? WHERE id=? AND user_id=? AND version=?",
                JSON.toJSONString(state), state.status, currentDate(state), state.equity, state.version, Timestamp.from(Instant.now()), id, userId, previous);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "练习版本已变化，请重新读取后操作");
        return PracticeEngine.view(state, stored.snapshot());
    }

    private Stored load(long userId, String id, boolean lock) {
        if (id == null || id.length() > 36) throw missing();
        // 只取决策状态，snapshot_json（几十 KB 的冻结 bars）不再随行一起读。
        // FOR UPDATE 仍然锁住 practice_session 这一行，锁语义不变。
        List<PracticeState> rows = jdbc.query("SELECT state_json,version FROM practice_session WHERE id=? AND user_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> {
                    PracticeState state = JSON.parseObject(rs.getString("state_json"), PracticeState.class);
                    state.version = rs.getLong("version");
                    return state;
                }, id, userId);
        if (rows.isEmpty()) throw missing();
        return new Stored(rows.get(0), snapshot(id));
    }

    /**
     * 快照创建后不可变，命中缓存就不再碰数据库。
     *
     * <p>缓存未命中时单独读一列：此时上面那条 SELECT 已经确认会话存在且属于该用户，
     * 事务里又持有行锁，不会有并发事务在两次读之间改写它。
     */
    private PracticeState.Snapshot snapshot(String id) {
        PracticeState.Snapshot cached = snapshots.get(id);
        if (cached != null) return cached;
        List<String> rows = jdbc.query("SELECT snapshot_json FROM practice_session WHERE id=?",
                (rs, row) -> rs.getString("snapshot_json"), id);
        if (rows.isEmpty()) throw missing();
        PracticeState.Snapshot snapshot = JSON.parseObject(rows.get(0), PracticeState.Snapshot.class);
        snapshots.put(id, snapshot);
        return snapshot;
    }

    private static String currentDate(PracticeState state) {
        return state.equityCurve.get(state.equityCurve.size() - 1).date();
    }

    private static BigDecimal returnRate(BigDecimal equity, BigDecimal initialCash) {
        if (equity == null || initialCash == null || initialCash.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return equity.subtract(initialCash).divide(initialCash, 12, RoundingMode.HALF_UP);
    }

    private static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "练习不存在");
    }

    private record Stored(PracticeState state, PracticeState.Snapshot snapshot) {}
}
