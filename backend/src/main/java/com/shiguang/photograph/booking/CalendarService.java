package com.shiguang.photograph.booking;

import com.shiguang.photograph.api.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class CalendarService {
    private final JdbcClient jdbc;

    CalendarService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Calendar ownCalendar(UUID account) {
        var owner = ownPhotographer(account);
        return calendar(owner);
    }

    @Transactional
    public Calendar replace(UUID account, CalendarEdit edit) {
        var owner = ownPhotographer(account);
        if (edit.zoneId() == null || !ZoneId.getAvailableZoneIds().contains(edit.zoneId()) || edit.bufferMinutes() == null
                || edit.bufferMinutes() < 0 || edit.bufferMinutes() > 120 || edit.windows() == null || edit.windows().size() > 500
                || edit.windows().stream().anyMatch(window -> window == null || window.startsAt() == null
                   || window.endsAt() == null || !window.endsAt().isAfter(window.startsAt()))) {
            throw ApiException.invalid("请输入有效时区、0 至 120 分钟缓冲和有效工作区间。");
        }
        List<Window> windows = new ArrayList<>();
        for (Window next : edit.windows().stream().sorted(Comparator.comparing(window -> window.startsAt().toInstant())).toList()) {
            Window previous = windows.isEmpty() ? null : windows.getLast();
            if (previous != null && next.startsAt().isBefore(previous.endsAt())) {
                throw ApiException.invalid("工作区间不能重叠。");
            }
            if (previous != null && next.startsAt().isEqual(previous.endsAt())) {
                windows.set(windows.size() - 1, new Window(previous.startsAt(), next.endsAt()));
            } else {
                windows.add(next);
            }
        }
        jdbc.sql("""
                UPDATE photograph_app.photographer_profiles
                SET calendar_zone = :zone, booking_buffer_minutes = :buffer WHERE id = :id
                """).param("id", owner.id()).param("zone", edit.zoneId()).param("buffer", edit.bufferMinutes()).update();
        jdbc.sql("DELETE FROM photograph_app.availability_windows WHERE photographer_id = :id")
                .param("id", owner.id()).update();
        for (Window window : windows) {
            jdbc.sql("""
                    INSERT INTO photograph_app.availability_windows (photographer_id, starts_at, ends_at)
                    VALUES (:id, :start, :end)
                    """).param("id", owner.id()).param("start", window.startsAt()).param("end", window.endsAt()).update();
        }
        return calendar(photographer(owner.id()));
    }

    @Transactional
    public Calendar visibility(UUID account, String visibility) {
        if (!"PUBLIC".equals(visibility) && !"PRIVATE".equals(visibility)) {
            throw ApiException.invalid("请选择公开或私密。");
        }
        var owner = ownPhotographer(account);
        jdbc.sql("UPDATE photograph_app.photographer_profiles SET calendar_visibility = :value WHERE id = :id")
                .param("value", visibility).param("id", owner.id()).update();
        return calendar(photographer(owner.id()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Availability publicAvailability(UUID photographerId, UUID packageId, LocalDate date) {
        var photographer = readPhotographer("profile.id", photographerId, false);
        requireApproved(photographer);
        if (!photographer.visibility().equals("PUBLIC")) {
            return new Availability("PRIVATE", null, List.of());
        }
        if (date.getYear() < 1900 || date.getYear() > 9999) throw ApiException.invalid("查询日期超出范围。");
        int duration = jdbc.sql("""
                SELECT duration_minutes FROM photograph_app.photography_packages
                WHERE id = :id AND photographer_id = :photographer AND is_listed
                """).param("id", packageId).param("photographer", photographerId).query(Integer.class).optional()
                .orElseThrow(ApiException::notFound);
        ZoneId zone = ZoneId.of(photographer.zoneId());
        OffsetDateTime dayStart = date.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        var windows = jdbc.sql("""
                SELECT starts_at, ends_at FROM photograph_app.availability_windows
                WHERE photographer_id = :id AND starts_at < :dayEnd AND ends_at > :dayStart
                ORDER BY starts_at
                """).param("id", photographerId).param("dayStart", dayStart).param("dayEnd", dayEnd)
                .query(CalendarService::readWindow).list();
        var busy = jdbc.sql("""
                SELECT starts_at, busy_until AS ends_at FROM photograph_app.bookings
                WHERE photographer_id = :id AND starts_at < :end AND busy_until > :start
                  AND status IN ('CONFIRMED', 'AWAITING_DELIVERY', 'DELIVERED', 'COMPLETED')
                """).param("id", photographerId).param("start", dayStart)
                .param("end", dayEnd.plusMinutes(duration + photographer.buffer()))
                .query(CalendarService::readWindow).list();
        List<Window> slots = new ArrayList<>();
        OffsetDateTime now = now();
        for (var local = dayStart.atZoneSameInstant(zone); local.toInstant().isBefore(dayEnd.toInstant()); local = local.plusMinutes(30)) {
            OffsetDateTime start = local.toOffsetDateTime();
            if (!start.isAfter(now) || !aligned(start, photographer)) continue;
            OffsetDateTime busyUntil = local.plusMinutes(duration + photographer.buffer()).toOffsetDateTime();
            if (windows.stream().anyMatch(window -> contains(window, start, busyUntil))
                    && busy.stream().noneMatch(window -> overlaps(window, start, busyUntil))) {
                slots.add(new Window(start, local.plusMinutes(duration).toOffsetDateTime()));
            }
        }
        return new Availability("PUBLIC", photographer.zoneId(), slots);
    }

    Photographer photographer(UUID id) {
        return readPhotographer("profile.id", id, true);
    }

    void requireApproved(Photographer photographer) {
        if (!photographer.approved()) throw ApiException.notFound();
    }

    void validateSlot(Photographer photographer, UUID ignoredBooking, OffsetDateTime start, OffsetDateTime busyUntil) {
        validateStart(photographer, start);
        boolean free = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM photograph_app.availability_windows
                    WHERE photographer_id = :owner AND starts_at <= :start AND ends_at >= :end
                ) AND NOT EXISTS (
                    SELECT 1 FROM photograph_app.bookings
                    WHERE photographer_id = :owner AND starts_at < :end AND busy_until > :start
                      AND status IN ('CONFIRMED', 'AWAITING_DELIVERY', 'DELIVERED', 'COMPLETED')
                      AND (CAST(:ignored AS uuid) IS NULL OR id <> CAST(:ignored AS uuid))
                )
                """).param("owner", photographer.id()).param("start", start).param("end", busyUntil)
                .param("ignored", ignoredBooking).query(Boolean.class).single();
        if (!free) {
            throw new ApiException(HttpStatus.CONFLICT, "SLOT_CONFLICT", "所选时间不可预约，请重新选择。");
        }
    }

    void validateStart(Photographer photographer, OffsetDateTime start) {
        if (start == null || !start.isAfter(now()) || !aligned(start, photographer)) {
            throw ApiException.invalid("拍摄时间须为未来的整点或半点。");
        }
    }

    private Photographer ownPhotographer(UUID account) {
        var owner = readPhotographer("profile.account_id", account, true);
        if (!owner.approved()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PHOTOGRAPHER_NOT_APPROVED", "摄影师账号通过审核后才能维护日历。");
        }
        return owner;
    }

    private Photographer readPhotographer(String column, UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT profile.id, account.id AS account_id, profile.approval_status, profile.calendar_visibility,
                       profile.calendar_zone, profile.booking_buffer_minutes, account.accepts_paid_bookings,
                       account.accepts_tfp_bookings
                FROM photograph_app.photographer_profiles profile
                JOIN photograph_app.accounts account ON account.id = profile.account_id
                WHERE %s = :id
                """.formatted(column) + (lock ? " FOR UPDATE OF profile" : "")).param("id", id)
                .query((rs, row) -> new Photographer(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                        rs.getString("approval_status").equals("APPROVED"), rs.getString("calendar_visibility"),
                        rs.getString("calendar_zone"), rs.getInt("booking_buffer_minutes"),
                        rs.getBoolean("accepts_paid_bookings"), rs.getBoolean("accepts_tfp_bookings")))
                .optional().orElseThrow(ApiException::notFound);
    }

    private Calendar calendar(Photographer owner) {
        var windows = jdbc.sql("""
                SELECT starts_at, ends_at FROM photograph_app.availability_windows
                WHERE photographer_id = :id ORDER BY starts_at
                """).param("id", owner.id()).query(CalendarService::readWindow).list();
        var busy = jdbc.sql("""
                SELECT starts_at, busy_until AS ends_at FROM photograph_app.bookings
                WHERE photographer_id = :id AND busy_until > now()
                  AND status IN ('CONFIRMED', 'AWAITING_DELIVERY', 'DELIVERED', 'COMPLETED')
                ORDER BY starts_at
                """).param("id", owner.id()).query(CalendarService::readWindow).list();
        return new Calendar(owner.zoneId(), owner.visibility(), owner.buffer(), windows, busy);
    }

    static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }

    private static boolean aligned(OffsetDateTime time, Photographer photographer) {
        var local = time.atZoneSameInstant(ZoneId.of(photographer.zoneId()));
        return local.getMinute() % 30 == 0 && local.getSecond() == 0 && local.getNano() == 0;
    }

    private static boolean contains(Window window, OffsetDateTime start, OffsetDateTime end) {
        return !window.startsAt().isAfter(start) && !window.endsAt().isBefore(end);
    }

    private static boolean overlaps(Window window, OffsetDateTime start, OffsetDateTime end) {
        return window.startsAt().isBefore(end) && window.endsAt().isAfter(start);
    }

    private static Window readWindow(ResultSet rs, int index) throws SQLException {
        return new Window(rs.getObject("starts_at", OffsetDateTime.class), rs.getObject("ends_at", OffsetDateTime.class));
    }

    record Photographer(UUID id, UUID accountId, boolean approved, String visibility, String zoneId, int buffer,
                        boolean acceptsPaid, boolean acceptsTfp) {}
    record Window(OffsetDateTime startsAt, OffsetDateTime endsAt) {}
    record Availability(String visibility, String zoneId, List<Window> slots) {}
    record Calendar(String zoneId, String visibility, int bufferMinutes, List<Window> windows, List<Window> busy) {}
    record CalendarEdit(String zoneId, Integer bufferMinutes, List<Window> windows) {}
}
