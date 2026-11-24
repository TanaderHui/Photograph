package com.shiguang.photograph.catalog;

import com.shiguang.photograph.api.ApiException;
import com.shiguang.photograph.auth.AccountPrincipal;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class WorkspaceController {
    private final JdbcClient jdbc;

    WorkspaceController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/me/profile")
    public Profile profile(@AuthenticationPrincipal AccountPrincipal principal) {
        return jdbc.sql("""
                SELECT id, nickname, is_operator, accepts_paid_bookings, accepts_tfp_bookings
                FROM photograph_app.accounts WHERE id = :id
                """).param("id", principal.accountId())
                .query((rs, row) -> new Profile(rs.getObject("id", UUID.class), rs.getString("nickname"),
                        rs.getBoolean("is_operator"), rs.getBoolean("accepts_paid_bookings"),
                        rs.getBoolean("accepts_tfp_bookings"))).single();
    }

    @PatchMapping("/me/profile")
    public Profile profile(@AuthenticationPrincipal AccountPrincipal principal, @RequestBody ProfileEdit edit) {
        jdbc.sql("""
                UPDATE photograph_app.accounts
                SET nickname = coalesce(CAST(:nickname AS text), nickname),
                    accepts_paid_bookings = coalesce(CAST(:paid AS boolean), accepts_paid_bookings),
                    accepts_tfp_bookings = coalesce(CAST(:tfp AS boolean), accepts_tfp_bookings)
                WHERE id = :id AND closed_at IS NULL
                """).param("id", principal.accountId())
                .param("nickname", edit.nickname() == null ? null : normalize(edit.nickname(), 80, "昵称", true))
                .param("paid", edit.acceptsPaidBookings()).param("tfp", edit.acceptsTfpBookings()).update();
        return profile(principal);
    }

    @GetMapping("/me/photographer")
    public Photographer photographer(@AuthenticationPrincipal AccountPrincipal principal) {
        return findPhotographer(principal.accountId(), false);
    }

    @PostMapping("/me/photographer/applications")
    @Transactional
    public ResponseEntity<Photographer> apply(@AuthenticationPrincipal AccountPrincipal principal, @RequestBody BioEdit edit) {
        String city = normalize(edit.city(), 100, "服务城市", true);
        String bio = normalize(edit.bio(), 2000, "个人介绍", false);
        int count = jdbc.sql("""
                INSERT INTO photograph_app.photographer_profiles AS profile (account_id, city, bio)
                SELECT id, :city, :bio FROM photograph_app.accounts
                WHERE id = :account AND closed_at IS NULL FOR NO KEY UPDATE
                ON CONFLICT (account_id) DO UPDATE
                SET city = EXCLUDED.city, bio = EXCLUDED.bio, approval_status = 'PENDING', version = profile.version + 1
                WHERE profile.approval_status = 'REJECTED'
                """).param("account", principal.accountId()).param("city", city).param("bio", bio).update();
        if (count != 1) {
            throw ApiException.conflict("当前入驻状态不能重复申请。");
        }
        return ResponseEntity.created(URI.create("/api/v1/me/photographer")).body(photographer(principal));
    }

    @PutMapping("/me/photographer")
    @Transactional
    public Photographer updatePhotographer(@AuthenticationPrincipal AccountPrincipal principal, @RequestBody BioEdit edit) {
        Photographer profile = approved(principal);
        jdbc.sql("""
                UPDATE photograph_app.photographer_profiles SET city = :city, bio = :bio, version = version + 1
                WHERE id = :id
                """).param("city", normalize(edit.city(), 100, "服务城市", true))
                .param("bio", normalize(edit.bio(), 2000, "个人介绍", false)).param("id", profile.id()).update();
        return photographer(principal);
    }

    @PutMapping("/me/photographer/styles")
    @Transactional
    public Photographer styles(@AuthenticationPrincipal AccountPrincipal principal, @RequestBody StylesEdit edit) {
        Photographer profile = approved(principal);
        List<String> styles = edit.styleIds();
        if (styles == null || styles.size() > 30 || styles.stream().anyMatch(id -> id == null || id.length() > 100)
                || styles.stream().distinct().count() != styles.size()) {
            throw ApiException.invalid("风格标签无效或重复。");
        }
        if (!styles.isEmpty()) {
            int found = jdbc.sql("SELECT count(*) FROM photograph_app.styles WHERE id IN (:styles)")
                    .param("styles", styles).query(Integer.class).single();
            if (found != styles.size()) {
                throw ApiException.invalid("部分风格标签不存在。");
            }
        }
        jdbc.sql("DELETE FROM photograph_app.photographer_styles WHERE photographer_id = :id")
                .param("id", profile.id()).update();
        for (String style : styles) {
            jdbc.sql("INSERT INTO photograph_app.photographer_styles VALUES (:id, :style)")
                    .param("id", profile.id()).param("style", style).update();
        }
        return photographer(principal);
    }

    @GetMapping("/me/photographer/packages")
    @Transactional
    public ManagedPackagePage packages(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam(required = false) UUID cursor, @RequestParam(defaultValue = "20") int limit) {
        Photographer profile = approved(principal);
        if (limit < 1 || limit > 50) {
            throw ApiException.invalid("每页记录数须介于 1 至 50。");
        }
        var items = jdbc.sql("""
                SELECT id, name, version, duration_minutes, retouched_count, delivery_days, price_minor, currency, is_listed
                FROM photograph_app.photography_packages
                WHERE photographer_id = :id AND (CAST(:cursor AS uuid) IS NULL OR id > CAST(:cursor AS uuid))
                ORDER BY id LIMIT :limit
                """).param("id", profile.id()).param("cursor", cursor).param("limit", limit + 1)
                .query(WorkspaceController::readPackage).list();
        return items.size() > limit
                ? new ManagedPackagePage(items.subList(0, limit), items.get(limit - 1).id())
                : new ManagedPackagePage(items, null);
    }

    @PostMapping("/me/photographer/packages")
    @Transactional
    public ResponseEntity<ManagedPackage> createPackage(
            @AuthenticationPrincipal AccountPrincipal principal, @RequestBody PackageEdit edit) {
        var profile = approved(principal);
        var result = packageFields("""
                INSERT INTO photograph_app.photography_packages
                    (photographer_id, name, duration_minutes, retouched_count, delivery_days, price_minor, is_listed)
                VALUES (:owner, :name, :duration, :retouches, :days, :price, :listed)
                RETURNING *
                """, profile.id(), edit).query(WorkspaceController::readPackage).single();
        return ResponseEntity.created(URI.create("/api/v1/me/photographer/packages/" + result.id())).body(result);
    }

    @PutMapping("/me/photographer/packages/{id}")
    @Transactional
    public ManagedPackage updatePackage(@AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable UUID id, @RequestBody PackageEdit edit) {
        var profile = approved(principal);
        var current = jdbc.sql("""
                SELECT version FROM photograph_app.photography_packages WHERE id = :id AND photographer_id = :owner
                """).param("id", id).param("owner", profile.id()).query(Integer.class).optional()
                .orElseThrow(ApiException::notFound);
        if (!current.equals(edit.expectedVersion())) {
            throw ApiException.conflict("套餐已更新，请刷新后重试。");
        }
        return packageFields("""
                UPDATE photograph_app.photography_packages
                SET name = :name, duration_minutes = :duration, retouched_count = :retouches,
                    delivery_days = :days, price_minor = :price, is_listed = :listed, version = version + 1
                WHERE id = :id AND photographer_id = :owner
                RETURNING *
                """, profile.id(), edit).param("id", id).query(WorkspaceController::readPackage).single();
    }

    @GetMapping("/admin/photographers")
    public ReviewPage reviewQueue(@RequestParam(required = false) UUID cursor) {
        var items = jdbc.sql("""
                SELECT profile.id, account.nickname, profile.city, profile.bio, profile.version
                FROM photograph_app.photographer_profiles profile
                JOIN photograph_app.accounts account ON account.id = profile.account_id
                WHERE profile.approval_status = 'PENDING'
                  AND (CAST(:cursor AS uuid) IS NULL OR profile.id > CAST(:cursor AS uuid))
                ORDER BY profile.id LIMIT 21
                """).param("cursor", cursor).query(ReviewItem.class).list();
        return items.size() > 20 ? new ReviewPage(items.subList(0, 20), items.get(19).id()) : new ReviewPage(items, null);
    }

    @PostMapping("/admin/photographers/{id}/decision")
    @Transactional
    public ResponseEntity<Void> decide(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id,
                                       @RequestBody Decision edit) {
        String reason = normalize(edit.reason(), 500, "审核理由", true);
        if (edit.decision() == null || !Set.of("APPROVED", "REJECTED", "SUSPENDED").contains(edit.decision())
                || edit.expectedVersion() == null || edit.expectedVersion() < 1) {
            throw ApiException.invalid("审核结果或版本无效。");
        }
        var current = jdbc.sql("SELECT approval_status FROM photograph_app.photographer_profiles WHERE id = :id")
                .param("id", id).query(String.class).optional().orElseThrow(ApiException::notFound);
        if (!allowed(current, edit.decision())) {
            throw ApiException.conflict("当前入驻状态无法执行该裁定。");
        }
        var version = jdbc.sql("""
                UPDATE photograph_app.photographer_profiles SET approval_status = :decision, version = version + 1
                WHERE id = :id AND version = :expected AND approval_status = :current
                  AND EXISTS (SELECT 1 FROM photograph_app.accounts WHERE id = account_id AND closed_at IS NULL)
                RETURNING version
                """).param("id", id).param("decision", edit.decision()).param("expected", edit.expectedVersion())
                .param("current", current).query(Integer.class).optional()
                .orElseThrow(() -> ApiException.conflict("入驻资料已更新，请刷新后重试。"));
        jdbc.sql("""
                INSERT INTO photograph_app.photographer_review_log
                    (photographer_id, operator_id, decision, reason, profile_version)
                VALUES (:id, :operator, :decision, :reason, :version)
                """).param("id", id).param("operator", principal.accountId()).param("decision", edit.decision())
                .param("reason", reason).param("version", version).update();
        return ResponseEntity.noContent().build();
    }

    private static boolean allowed(String current, String decision) {
        return switch (current) {
            case "PENDING" -> !decision.equals("SUSPENDED");
            case "APPROVED" -> decision.equals("SUSPENDED");
            case "SUSPENDED" -> decision.equals("APPROVED");
            default -> false;
        };
    }

    private Photographer approved(AccountPrincipal principal) {
        var profile = findPhotographer(principal.accountId(), true);
        if (!profile.approvalStatus().equals("APPROVED")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PHOTOGRAPHER_NOT_APPROVED", "摄影师账号通过审核后才能使用此功能。");
        }
        return profile;
    }

    private Photographer findPhotographer(UUID account, boolean lock) {
        return jdbc.sql("""
                SELECT profile.id, profile.city, profile.bio, profile.approval_status, profile.version,
                    ARRAY(SELECT style_id FROM photograph_app.photographer_styles
                          WHERE photographer_id = profile.id ORDER BY style_id) AS style_ids
                FROM photograph_app.photographer_profiles profile WHERE account_id = :account
                """ + (lock ? " FOR UPDATE" : "")).param("account", account)
                .query((rs, row) -> new Photographer(rs.getObject("id", UUID.class), rs.getString("city"),
                        rs.getString("bio"), rs.getString("approval_status"), rs.getInt("version"),
                        PhotographerController.styleIds(rs.getArray("style_ids"))))
                .optional().orElseThrow(ApiException::notFound);
    }

    private JdbcClient.StatementSpec packageFields(String sql, UUID owner, PackageEdit edit) {
        if (edit.durationMinutes() == null || edit.durationMinutes() < 30 || edit.durationMinutes() > 720
                || edit.retouchedCount() == null || edit.retouchedCount() < 0
                || edit.deliveryDays() == null || edit.deliveryDays() < 1
                || edit.priceMinor() == null || edit.priceMinor() < 0 || edit.listed() == null) {
            throw ApiException.invalid("请检查套餐时长（30 至 720 分钟）、精修数量、交付天数和金额。");
        }
        return jdbc.sql(sql).param("owner", owner).param("name", normalize(edit.name(), 120, "套餐名", true))
                .param("duration", edit.durationMinutes()).param("retouches", edit.retouchedCount())
                .param("days", edit.deliveryDays()).param("price", edit.priceMinor()).param("listed", edit.listed());
    }

    private static String normalize(String value, int max, String label, boolean required) {
        String normalized = value == null ? "" : value.strip();
        if ((required && normalized.isEmpty()) || normalized.length() > max) {
            throw ApiException.invalid(label + "的长度不符合要求。");
        }
        return normalized;
    }

    private static ManagedPackage readPackage(ResultSet row, int index) throws SQLException {
        return new ManagedPackage(row.getObject("id", UUID.class), row.getString("name"), row.getInt("version"),
                row.getInt("duration_minutes"), row.getInt("retouched_count"), row.getInt("delivery_days"),
                row.getInt("price_minor"), row.getString("currency"), row.getBoolean("is_listed"));
    }

    record Profile(UUID id, String nickname, boolean operator, boolean acceptsPaidBookings, boolean acceptsTfpBookings) {}
    record ProfileEdit(String nickname, Boolean acceptsPaidBookings, Boolean acceptsTfpBookings) {}
    record Photographer(UUID id, String city, String bio, String approvalStatus, int version, List<String> styleIds) {}
    record BioEdit(String city, String bio) {}
    record StylesEdit(List<String> styleIds) {}
    record PackageEdit(String name, Integer durationMinutes, Integer retouchedCount, Integer deliveryDays,
                       Integer priceMinor, Boolean listed, Integer expectedVersion) {}
    record ManagedPackage(UUID id, String name, int version, int durationMinutes, int retouchedCount,
                          int deliveryDays, int priceMinor, String currency, boolean listed) {}
    record ManagedPackagePage(List<ManagedPackage> items, UUID nextCursor) {}
    record ReviewItem(UUID id, String nickname, String city, String bio, int version) {}
    record ReviewPage(List<ReviewItem> items, UUID nextCursor) {}
    record Decision(String decision, String reason, Integer expectedVersion) {}
}
