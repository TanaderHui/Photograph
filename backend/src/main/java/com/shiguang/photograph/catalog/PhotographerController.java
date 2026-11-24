package com.shiguang.photograph.catalog;

import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PhotographerController {
    private final JdbcClient jdbc;

    PhotographerController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/photographers/{id}")
    Photographer getPhotographer(@PathVariable("id") String id) {
        return jdbc.sql("""
                SELECT profile.id, account.nickname, profile.city, profile.bio,
                       ARRAY(
                           SELECT style.id
                           FROM photograph_app.photographer_styles AS link
                           JOIN photograph_app.styles AS style ON style.id = link.style_id
                           WHERE link.photographer_id = profile.id
                           ORDER BY style.display_order, style.id
                       ) AS style_ids
                FROM photograph_app.photographer_profiles AS profile
                JOIN photograph_app.accounts AS account ON account.id = profile.account_id
                WHERE profile.id = :photographerId AND profile.approval_status = 'APPROVED'
                """)
                .param("photographerId", photographerId(id))
                .query((row, index) -> new Photographer(
                        row.getString("id"),
                        row.getString("nickname"),
                        row.getString("city"),
                        row.getString("bio"),
                        styleIds(row.getArray("style_ids"))))
                .optional()
                .orElseThrow(PhotographerController::notFound);
    }

    @GetMapping("/api/v1/photographers/{id}/packages")
    PackagePage getPackages(
            @PathVariable("id") String id,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") String limit
    ) {
        UUID photographerId = photographerId(id);
        UUID after = cursor == null ? null : parseUuid(cursor, PhotographerController::invalidPage);
        int pageSize = pageSize(limit);
        List<PackageListing> rows = jdbc.sql("""
                SELECT package.id, package.name, package.version, package.duration_minutes,
                       package.retouched_count, package.delivery_days, package.price_minor, package.currency
                FROM photograph_app.photographer_profiles AS profile
                LEFT JOIN LATERAL (
                    SELECT listing.id, listing.name, listing.version, listing.duration_minutes,
                           listing.retouched_count, listing.delivery_days, listing.price_minor, listing.currency
                    FROM photograph_app.photography_packages AS listing
                    WHERE listing.photographer_id = profile.id AND listing.is_listed
                      AND (CAST(:after AS uuid) IS NULL OR listing.id > CAST(:after AS uuid))
                    ORDER BY listing.id
                    LIMIT :fetchLimit
                ) AS package ON true
                WHERE profile.id = :photographerId AND profile.approval_status = 'APPROVED'
                ORDER BY package.id
                """)
                .param("photographerId", photographerId)
                .param("after", after)
                .param("fetchLimit", pageSize + 1)
                .query((row, index) -> new PackageListing(
                        row.getString("id"),
                        row.getString("name"),
                        row.getInt("version"),
                        row.getInt("duration_minutes"),
                        row.getInt("retouched_count"),
                        row.getInt("delivery_days"),
                        row.getInt("price_minor"),
                        row.getString("currency")))
                .list();

        if (rows.isEmpty()) {
            throw notFound();
        }
        if (rows.getFirst().id() == null) {
            return new PackagePage(List.of(), null);
        }
        if (rows.size() > pageSize) {
            return new PackagePage(List.copyOf(rows.subList(0, pageSize)), rows.get(pageSize - 1).id());
        }
        return new PackagePage(rows, null);
    }

    static List<String> styleIds(Array sqlArray) throws SQLException {
        try {
            return List.of((String[]) sqlArray.getArray());
        } finally {
            sqlArray.free();
        }
    }

    private static UUID photographerId(String id) {
        return parseUuid(id, PhotographerController::notFound);
    }

    private static UUID parseUuid(String value, Supplier<CatalogException> error) {
        try {
            UUID result = UUID.fromString(value);
            if (result.toString().equalsIgnoreCase(value)) {
                return result;
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed identifiers use the same response as other invalid identifiers.
        }
        throw error.get();
    }

    private static int pageSize(String limit) {
        try {
            int parsed = Integer.parseInt(limit);
            if (parsed >= 1 && parsed <= 50) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // The response also covers limits outside the allowed range.
        }
        throw invalidPage();
    }

    private static CatalogException notFound() {
        return new CatalogException(HttpStatus.NOT_FOUND, "PHOTOGRAPHER_NOT_FOUND", "该摄影师当前不可查看。");
    }

    private static CatalogException invalidPage() {
        return new CatalogException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "分页参数无效。");
    }

    @ExceptionHandler(CatalogException.class)
    ResponseEntity<ApiError> handleError(CatalogException error) {
        String requestId = UUID.randomUUID().toString();
        return ResponseEntity.status(error.status)
                .header("X-Request-ID", requestId)
                .body(new ApiError(error.code, error.getMessage(), requestId));
    }

    record Photographer(String id, String nickname, String city, String bio, List<String> styleIds) {}

    record PackageListing(String id, String name, int version, int durationMinutes,
                          int retouchedCount, int deliveryDays, int priceMinor, String currency) {}

    record PackagePage(List<PackageListing> items, String nextCursor) {}

    record ApiError(String code, String message, String requestId) {}

    private static class CatalogException extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        CatalogException(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
