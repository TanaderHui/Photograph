package com.shiguang.photograph.booking;

import static com.shiguang.photograph.booking.CalendarService.now;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiguang.photograph.api.ApiException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class BookingService {
    private final JdbcClient jdbc;
    private final CalendarService calendars;
    private final ObjectMapper json;

    BookingService(JdbcClient jdbc, CalendarService calendars, ObjectMapper json) {
        this.jdbc = jdbc;
        this.calendars = calendars;
        this.json = json;
    }

    @Transactional
    public JsonNode create(UUID account, String key, BookingRequest request) {
        jdbc.sql("""
                SELECT id FROM photograph_app.accounts WHERE id = :account AND closed_at IS NULL FOR NO KEY UPDATE
                """).param("account", account).query(UUID.class).optional().orElseThrow(ApiException::notFound);
        return once(account, "bookings", key, request, () -> {
            if (request.packageId() == null || request.packageVersion() == null || request.packageVersion() < 1
                    || request.startsAt() == null) {
                throw ApiException.invalid("请提供有效套餐版本和拍摄时间。");
            }
            if (request.briefId() != null || request.briefVersion() != null || request.sourceWorkId() != null) {
                throw ApiException.invalid("当前版本仅支持通过套餐直接预约。");
            }
            String area = requiredText(request.meetingArea(), 200, "集合区域");
            String note = request.note() == null ? "" : request.note().strip();
            if (note.length() > 1000) throw ApiException.invalid("备注最多 1000 字。");
            UUID ownerId = jdbc.sql("SELECT photographer_id FROM photograph_app.photography_packages WHERE id = :id")
                    .param("id", request.packageId()).query(UUID.class).optional().orElseThrow(ApiException::notFound);
            var owner = calendars.photographer(ownerId);
            calendars.requireApproved(owner);
            if (owner.accountId().equals(account)) throw ApiException.invalid("不能预约自己的套餐。");
            PackageSnapshot current = jdbc.sql("""
                    SELECT name, version, duration_minutes, retouched_count, delivery_days, price_minor, currency
                    FROM photograph_app.photography_packages WHERE id = :id AND is_listed
                    """).param("id", request.packageId()).query(PackageSnapshot.class).optional().orElseThrow(ApiException::notFound);
            if (current.version() != request.packageVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "PACKAGE_CHANGED", "套餐条件已更新，请刷新后重新确认。");
            }
            String engagement = request.engagementType() == null ? "PAID" : request.engagementType();
            PackageSnapshot snapshot = switch (engagement) {
                case "PAID" -> {
                    if (!owner.acceptsPaid()) throw ApiException.invalid("该摄影师当前不接受付费约拍。");
                    yield current;
                }
                case "TFP" -> {
                    if (!owner.acceptsTfp()) throw ApiException.invalid("该摄影师当前不接受互免约拍。");
                    yield new PackageSnapshot(current.name(), current.version(), current.durationMinutes(),
                            current.retouchedCount(), current.deliveryDays(), 0, current.currency());
                }
                default -> throw ApiException.invalid("无效的合作方式。");
            };
            OffsetDateTime start = request.startsAt();
            var end = start.plusMinutes(snapshot.durationMinutes());
            var busy = end.plusMinutes(owner.buffer());
            if (owner.visibility().equals("PUBLIC")) {
                calendars.validateSlot(owner, null, start, busy);
            } else {
                calendars.validateStart(owner, start);
            }
            UUID id = jdbc.sql("""
                    INSERT INTO photograph_app.bookings
                        (customer_id, photographer_id, photographer_account_id, package_id, package_snapshot,
                         engagement_type, settlement_mode, starts_at, ends_at, busy_until, buffer_minutes, zone_id,
                         expires_at, meeting_area, note)
                    VALUES (:customer, :owner, :ownerAccount, :package, CAST(:snapshot AS jsonb),
                            :engagement, :settlement, :start, :end, :busy, :buffer, :zone,
                            least(CAST(:start AS timestamptz), now() + interval '24 hours'), :area, :note)
                    RETURNING id
                    """).param("customer", account).param("owner", owner.id()).param("ownerAccount", owner.accountId())
                    .param("package", request.packageId()).param("snapshot", json.valueToTree(snapshot).toString())
                    .param("engagement", engagement).param("settlement", engagement.equals("TFP") ? "NO_PAYMENT" : "OFFLINE")
                    .param("start", start).param("end", end).param("busy", busy).param("buffer", owner.buffer())
                    .param("zone", owner.zoneId()).param("area", area).param("note", note).query(UUID.class).single();
            notifyAccount(owner.accountId(), id, "BOOKING_REQUESTED");
            return view(find(account, id, false), null);
        });
    }

    @Transactional
    public JsonNode action(UUID account, UUID id, String key, Action request) {
        return once(account, "bookings/" + id + "/actions", key, request, () -> {
            if (request.action() == null) throw ApiException.invalid("请选择订单操作。");
            var first = find(account, id, false);
            var owner = calendars.photographer(first.photographerId());
            var booking = find(account, id, true);
            checkVersion(booking, request.expectedVersion());
            boolean customer = booking.customerId().equals(account);
            String event = switch (request.action()) {
                case "accept" -> {
                    requireRole(!customer);
                    requirePending(booking);
                    calendars.requireApproved(owner);
                    calendars.validateSlot(owner, id, booking.startsAt(), booking.endsAt().plusMinutes(booking.buffer()));
                    changeStatus(id, "CONFIRMED", null);
                    closeConflicts(booking.photographerId(), id, booking.startsAt(), booking.endsAt().plusMinutes(booking.buffer()));
                    yield "BOOKING_CONFIRMED";
                }
                case "reject", "withdraw" -> {
                    boolean withdraw = request.action().equals("withdraw");
                    requireRole(customer == withdraw);
                    requirePending(booking);
                    changeStatus(id, withdraw ? "WITHDRAWN" : "REJECTED", null);
                    yield withdraw ? "BOOKING_WITHDRAWN" : "BOOKING_REJECTED";
                }
                case "cancel" -> {
                    requireFutureConfirmed(booking);
                    String reason = request.reasonCode() == null ? null : request.reasonCode().strip();
                    if ((booking.startsAt().isBefore(now().plusHours(24)) || reason != null)
                            && (reason == null || !reason.matches("[A-Z][A-Z0-9_]{0,79}"))) {
                        throw ApiException.invalid("距开拍不足 24 小时取消时须提供原因代码。");
                    }
                    changeStatus(id, "CANCELLED", reason);
                    jdbc.sql("""
                            UPDATE photograph_app.booking_reschedules SET status = 'CLOSED', decided_at = now()
                            WHERE booking_id = :id AND status = 'PENDING'
                            """).param("id", id).update();
                    yield "BOOKING_CANCELLED";
                }
                case "confirmShoot" -> {
                    if (!booking.status().equals("CONFIRMED") || booking.endsAt().isAfter(now())) {
                        throw stateConflict("预约结束后双方才能确认已拍摄。");
                    }
                    if (customer ? booking.customerConfirmed() : booking.photographerConfirmed()) {
                        throw stateConflict("当前账号已经确认拍摄。");
                    }
                    boolean both = customer ? booking.photographerConfirmed() : booking.customerConfirmed();
                    String column = customer ? "customer_shoot_at" : "photographer_shoot_at";
                    jdbc.sql("""
                            UPDATE photograph_app.bookings SET %s = now(), version = version + 1,
                                status = :status WHERE id = :id
                            """.formatted(column)).param("id", id).param("status", both ? "AWAITING_DELIVERY" : "CONFIRMED").update();
                    yield both ? "BOOKING_AWAITING_DELIVERY" : "BOOKING_SHOOT_CONFIRMED";
                }
                case "markDelivered" -> {
                    requireRole(!customer);
                    if (!booking.status().equals("AWAITING_DELIVERY")) {
                        throw stateConflict("双方确认拍摄后才能标记已线下交片。");
                    }
                    jdbc.sql("""
                            UPDATE photograph_app.bookings SET status = 'DELIVERED', delivered_at = now(), version = version + 1
                            WHERE id = :id
                            """).param("id", id).update();
                    yield "BOOKING_DELIVERED";
                }
                case "confirmReceipt" -> {
                    requireRole(customer);
                    if (!booking.status().equals("DELIVERED")) {
                        throw stateConflict("摄影师标记已交片后才能确认收片。");
                    }
                    jdbc.sql("""
                            UPDATE photograph_app.bookings SET status = 'COMPLETED', completed_at = now(), version = version + 1
                            WHERE id = :id
                            """).param("id", id).update();
                    yield "BOOKING_COMPLETED";
                }
                default -> throw ApiException.invalid("当前不支持该订单操作。");
            };
            notifyAccount(customer ? booking.photographerAccountId() : booking.customerId(), id, event);
            return view(find(account, id, false), pendingReschedule(id));
        });
    }

    @Transactional
    public JsonNode propose(UUID account, UUID id, String key, RescheduleRequest request) {
        return once(account, "bookings/" + id + "/reschedules", key, request, () -> {
            var first = find(account, id, false);
            var owner = calendars.photographer(first.photographerId());
            var booking = find(account, id, true);
            checkVersion(booking, request.expectedVersion());
            requireFutureConfirmed(booking);
            if (request.startsAt() == null || request.startsAt().isEqual(booking.startsAt())) {
                throw ApiException.invalid("请选择不同的改期时间。");
            }
            if (pendingReschedule(id) != null) throw stateConflict("该预约已有待确认的改期申请。");
            var end = request.startsAt().plusMinutes(booking.packageSnapshot().durationMinutes() + booking.buffer());
            if (booking.photographerAccountId().equals(account) || owner.visibility().equals("PUBLIC")) {
                calendars.validateSlot(owner, id, request.startsAt(), end);
            } else {
                calendars.validateStart(owner, request.startsAt());
            }
            UUID rid = jdbc.sql("""
                    INSERT INTO photograph_app.booking_reschedules (booking_id, proposed_by, starts_at)
                    VALUES (:id, :account, :start) RETURNING id
                    """).param("id", id).param("account", account).param("start", request.startsAt()).query(UUID.class).single();
            changeStatus(id, "CONFIRMED", null);
            notifyAccount(other(booking, account), id, "RESCHEDULE_PROPOSED");
            return reschedule(id, rid);
        });
    }

    @Transactional
    public JsonNode decide(UUID account, UUID id, UUID rid, String key, RescheduleDecision request) {
        return once(account, "bookings/" + id + "/reschedules/" + rid + "/decision", key, request, () -> {
            var first = find(account, id, false);
            var owner = calendars.photographer(first.photographerId());
            var booking = find(account, id, true);
            checkVersion(booking, request.expectedVersion());
            requireFutureConfirmed(booking);
            var current = reschedule(id, rid);
            requireRole(!current.proposedBy().equals(account));
            if (!current.status().equals("PENDING")) throw stateConflict("该改期申请已处理。");
            if (!"accept".equals(request.decision()) && !"reject".equals(request.decision())) {
                throw ApiException.invalid("请选择接受或拒绝改期。");
            }
            boolean accept = request.decision().equals("accept");
            if (accept) {
                var end = current.startsAt().plusMinutes(booking.packageSnapshot().durationMinutes());
                var busy = end.plusMinutes(booking.buffer());
                calendars.validateSlot(owner, id, current.startsAt(), busy);
                jdbc.sql("""
                        UPDATE photograph_app.bookings
                        SET starts_at = :start, ends_at = :end, busy_until = :busy, zone_id = :zone, version = version + 1
                        WHERE id = :id
                        """).param("start", current.startsAt()).param("end", end).param("busy", busy).param("id", id)
                        .param("zone", owner.zoneId()).update();
                closeConflicts(booking.photographerId(), id, current.startsAt(), busy);
            } else {
                changeStatus(id, "CONFIRMED", null);
            }
            jdbc.sql("""
                    UPDATE photograph_app.booking_reschedules SET status = :status, decided_at = now() WHERE id = :id
                    """).param("status", accept ? "ACCEPTED" : "REJECTED").param("id", rid).update();
            notifyAccount(current.proposedBy(), id, accept ? "RESCHEDULE_ACCEPTED" : "RESCHEDULE_REJECTED");
            return view(find(account, id, false), null);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(UUID account, UUID id) {
        return view(find(account, id, false), pendingReschedule(id));
    }

    Page<View> list(UUID account, UUID cursor, int limit, Boolean completed) {
        checkLimit(limit);
        List<View> items = jdbc.sql("""
                SELECT booking.* FROM photograph_app.bookings booking
                WHERE (customer_id = :account OR photographer_account_id = :account)
                  AND (CAST(:completed AS boolean) IS NULL OR (status = 'COMPLETED') = CAST(:completed AS boolean))
                  AND (CAST(:cursor AS uuid) IS NULL OR id > CAST(:cursor AS uuid))
                ORDER BY id LIMIT :limit
                """).param("account", account).param("cursor", cursor).param("limit", limit + 1).param("completed", completed)
                .query(this::readBooking).list().stream().map(booking -> view(booking, null)).toList();
        return items.size() > limit
                ? new Page<>(items.subList(0, limit), items.get(limit - 1).id().toString()) : new Page<>(items, null);
    }

    Page<Notice> notices(UUID account, Long cursor, int limit) {
        checkLimit(limit);
        List<Notice> items = jdbc.sql("""
                SELECT id::text, booking_id, event_type, created_at FROM photograph_app.booking_notifications
                WHERE account_id = :account AND (CAST(:cursor AS bigint) IS NULL OR id < CAST(:cursor AS bigint))
                ORDER BY booking_notifications.id DESC LIMIT :limit
                """).param("account", account).param("cursor", cursor).param("limit", limit + 1).query(Notice.class).list();
        return items.size() > limit
                ? new Page<>(items.subList(0, limit), items.get(limit - 1).id()) : new Page<>(items, null);
    }

    @Scheduled(fixedDelay = 30_000)
    public void expireApplications() {
        jdbc.sql("""
                WITH due AS (
                    SELECT id FROM photograph_app.bookings
                    WHERE status = 'PENDING' AND expires_at <= now()
                    ORDER BY expires_at LIMIT 200 FOR UPDATE SKIP LOCKED
                ), expired AS (
                    UPDATE photograph_app.bookings SET status = 'EXPIRED', version = version + 1
                    FROM due WHERE bookings.id = due.id
                    RETURNING bookings.id, customer_id, photographer_account_id
                )
                INSERT INTO photograph_app.booking_notifications (account_id, booking_id, event_type)
                SELECT customer_id, id, 'BOOKING_EXPIRED' FROM expired
                UNION ALL
                SELECT photographer_account_id, id, 'BOOKING_EXPIRED' FROM expired
                """).update();
    }

    private void closeConflicts(UUID photographerId, UUID id, OffsetDateTime start, OffsetDateTime busyUntil) {
        jdbc.sql("""
                WITH closed AS (
                    UPDATE photograph_app.bookings SET status = 'CONFLICT_CLOSED', version = version + 1
                    WHERE photographer_id = :owner AND id <> :id AND status = 'PENDING' AND expires_at > now()
                      AND starts_at < :end AND busy_until > :start
                    RETURNING id, customer_id
                )
                INSERT INTO photograph_app.booking_notifications (account_id, booking_id, event_type)
                SELECT customer_id, id, 'BOOKING_CONFLICT_CLOSED' FROM closed
                """).param("owner", photographerId).param("id", id).param("start", start).param("end", busyUntil).update();
    }

    private JsonNode once(UUID account, String scope, String key, Object request, Supplier<Object> operation) {
        if (key == null || !key.matches("[!-~]{1,120}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "请提供有效的 Idempotency-Key。");
        }
        String digest = hash(json.valueToTree(request).toString());
        int created = jdbc.sql("""
                INSERT INTO photograph_app.booking_requests (account_id, scope, request_key, request_digest)
                VALUES (:account, :scope, :key, :digest) ON CONFLICT DO NOTHING
                """).param("account", account).param("scope", scope).param("key", key).param("digest", digest).update();
        if (created == 0) {
            var existing = jdbc.sql("""
                    SELECT request_digest, result::text FROM photograph_app.booking_requests
                    WHERE account_id = :account AND scope = :scope AND request_key = :key
                    """).param("account", account).param("scope", scope).param("key", key)
                    .query((rs, row) -> new StoredRequest(rs.getString(1), rs.getString(2))).single();
            if (!existing.digest().equals(digest)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "该幂等键已用于另一项请求。");
            }
            return readJson(existing.result(), JsonNode.class);
        }
        JsonNode result = json.valueToTree(operation.get());
        jdbc.sql("""
                UPDATE photograph_app.booking_requests SET result = CAST(:result AS jsonb)
                WHERE account_id = :account AND scope = :scope AND request_key = :key
                """).param("result", result.toString()).param("account", account).param("scope", scope).param("key", key).update();
        return result;
    }

    private Booking find(UUID account, UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT * FROM photograph_app.bookings WHERE id = :id
                AND (customer_id = :account OR photographer_account_id = :account)
                """ + (lock ? " FOR UPDATE" : "")).param("id", id).param("account", account)
                .query(this::readBooking).optional().orElseThrow(ApiException::notFound);
    }

    private Reschedule pendingReschedule(UUID id) {
        return jdbc.sql("""
                SELECT id, proposed_by, starts_at, status FROM photograph_app.booking_reschedules
                WHERE booking_id = :id AND status = 'PENDING'
                """).param("id", id).query(Reschedule.class).optional().orElse(null);
    }

    private Reschedule reschedule(UUID id, UUID rid) {
        return jdbc.sql("""
                SELECT id, proposed_by, starts_at, status FROM photograph_app.booking_reschedules
                WHERE booking_id = :id AND id = :rid
                """).param("id", id).param("rid", rid).query(Reschedule.class).optional().orElseThrow(ApiException::notFound);
    }

    private void changeStatus(UUID id, String status, String reason) {
        jdbc.sql("""
                UPDATE photograph_app.bookings SET status = :status, cancellation_reason = :reason, version = version + 1
                WHERE id = :id
                """).param("status", status).param("reason", reason).param("id", id).update();
    }

    private void notifyAccount(UUID account, UUID booking, String event) {
        jdbc.sql("""
                INSERT INTO photograph_app.booking_notifications (account_id, booking_id, event_type) VALUES (:account, :booking, :event)
                """).param("account", account).param("booking", booking).param("event", event).update();
    }

    private Booking readBooking(ResultSet rs, int row) throws SQLException {
        return new Booking(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getObject("photographer_id", UUID.class), rs.getObject("photographer_account_id", UUID.class),
                readJson(rs.getString("package_snapshot"), PackageSnapshot.class),
                rs.getString("engagement_type"), rs.getString("settlement_mode"), rs.getString("zone_id"),
                rs.getObject("starts_at", OffsetDateTime.class), rs.getObject("ends_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getInt("buffer_minutes"), rs.getInt("version"),
                rs.getString("status"), rs.getString("meeting_area"), rs.getString("note"), rs.getString("cancellation_reason"),
                rs.getTimestamp("customer_shoot_at") != null, rs.getTimestamp("photographer_shoot_at") != null,
                rs.getObject("delivered_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class));
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Stored booking data is invalid", error);
        }
    }

    private static View view(Booking booking, Reschedule pending) {
        ZoneId zone = ZoneId.of(booking.zoneId());
        return new View(booking.id(), 1, booking.customerId(), booking.photographerId(), booking.photographerAccountId(),
                booking.status(), booking.version(), booking.startsAt().atZoneSameInstant(zone).toOffsetDateTime(),
                booking.endsAt().atZoneSameInstant(zone).toOffsetDateTime(), booking.expiresAt(), booking.zoneId(),
                booking.packageSnapshot(), booking.engagementType(), booking.settlementMode(), booking.meetingArea(),
                booking.note(), booking.cancellationReason(), booking.customerConfirmed(), booking.photographerConfirmed(),
                booking.deliveredAt(), booking.completedAt(), pending);
    }

    private static UUID other(Booking booking, UUID account) {
        return booking.customerId().equals(account) ? booking.photographerAccountId() : booking.customerId();
    }

    private static void checkVersion(Booking booking, Integer expected) {
        if (expected == null || expected < 1) throw ApiException.invalid("请提供有效的订单版本。");
        if (booking.version() != expected) throw ApiException.conflict("订单状态已更新，请刷新后重试。");
    }

    private static void requirePending(Booking booking) {
        if (!booking.status().equals("PENDING") || !booking.expiresAt().isAfter(now())) {
            throw stateConflict("此申请已结束或已过期。");
        }
    }

    private static void requireFutureConfirmed(Booking booking) {
        if (!booking.status().equals("CONFIRMED") || !booking.startsAt().isAfter(now())) {
            throw stateConflict("仅可更改尚未开拍的已确认预约。");
        }
    }

    private static void requireRole(boolean permitted) {
        if (!permitted) throw new ApiException(HttpStatus.FORBIDDEN, "BOOKING_ROLE_REQUIRED", "当前角色无法执行此操作。");
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > 50) throw ApiException.invalid("每页记录数须介于 1 至 50。");
    }

    private static ApiException stateConflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "BOOKING_STATE_CONFLICT", message);
    }

    private static String requiredText(String raw, int max, String name) {
        if (raw == null || raw.isBlank() || raw.strip().length() > max) throw ApiException.invalid("请填写有效的" + name + "。");
        return raw.strip();
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    record BookingRequest(UUID packageId, Integer packageVersion, OffsetDateTime startsAt, String meetingArea,
                          String note, String engagementType, UUID sourceWorkId, UUID briefId, Integer briefVersion) {}
    record Action(String action, Integer expectedVersion, String reasonCode) {}
    record RescheduleRequest(Integer expectedVersion, OffsetDateTime startsAt) {}
    record RescheduleDecision(Integer expectedVersion, String decision) {}
    record Page<T>(List<T> items, String nextCursor) {}
    record Notice(String id, UUID bookingId, String eventType, OffsetDateTime createdAt) {}
    record Reschedule(UUID id, UUID proposedBy, OffsetDateTime startsAt, String status) {}
    record PackageSnapshot(String name, int version, int durationMinutes, int retouchedCount,
                           int deliveryDays, int priceMinor, String currency) {}
    record View(UUID id, int schemaVersion, UUID customerId, UUID photographerId, UUID photographerAccountId,
                String status, int version, OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime expiresAt,
                String zoneId, PackageSnapshot packageSnapshot, String engagementType, String settlementMode, String meetingArea,
                String note, String cancellationReason, boolean customerConfirmed, boolean photographerConfirmed,
                OffsetDateTime deliveredAt, OffsetDateTime completedAt, Reschedule pendingReschedule) {}
    private record Booking(UUID id, UUID customerId, UUID photographerId, UUID photographerAccountId,
                           PackageSnapshot packageSnapshot, String engagementType, String settlementMode, String zoneId,
                           OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime expiresAt, int buffer,
                           int version, String status, String meetingArea, String note, String cancellationReason,
                           boolean customerConfirmed, boolean photographerConfirmed,
                           OffsetDateTime deliveredAt, OffsetDateTime completedAt) {}
    private record StoredRequest(String digest, String result) {}
}
