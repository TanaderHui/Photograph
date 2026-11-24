package com.shiguang.photograph.catalog;

import com.shiguang.photograph.api.ApiException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HomeSearchController {
    private final JdbcClient jdbc;

    HomeSearchController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/v1/search/home")
    SearchPage search(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "photographer") String type,
                      @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
                      @RequestParam(defaultValue = "") String city, @RequestParam(defaultValue = "") String style,
                      @RequestParam(defaultValue = "false") boolean bookable) {
        String query = Normalizer.normalize(q.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String cityFilter = Normalizer.normalize(city.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (query.length() > 80 || cityFilter.length() > 100 || style.length() > 100
                || limit < 1 || limit > 50 || !type.equals("photographer")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH", "搜索条件无效。");
        }
        Cursor after = parseCursor(cursor);
        List<Hit> hits = jdbc.sql("""
                WITH matched AS (
                    SELECT profile.id, account.nickname, profile.city, profile.bio,
                        strpos(lower(normalize(account.nickname, NFKC)), :q) > 0 AS nickname_hit,
                        strpos(lower(normalize(profile.city, NFKC)), :q) > 0 AS city_hit,
                        EXISTS (SELECT 1 FROM photograph_app.photographer_styles link
                                JOIN photograph_app.styles style ON style.id = link.style_id
                                WHERE link.photographer_id = profile.id
                                  AND strpos(lower(normalize(style.name, NFKC)), :q) > 0) AS style_hit
                    FROM photograph_app.photographer_profiles profile
                    JOIN photograph_app.accounts account ON account.id = profile.account_id
                    WHERE profile.approval_status = 'APPROVED'
                      AND (:city = '' OR strpos(lower(normalize(profile.city, NFKC)), :city) > 0)
                      AND (:style = '' OR EXISTS (SELECT 1 FROM photograph_app.photographer_styles link
                                                  WHERE link.photographer_id = profile.id AND link.style_id = :style))
                      AND (NOT :bookable OR ((account.accepts_paid_bookings OR account.accepts_tfp_bookings)
                           AND EXISTS (SELECT 1 FROM photograph_app.photography_packages package
                                       WHERE package.photographer_id = profile.id AND package.is_listed)))
                ), ranked AS (
                    SELECT *, CASE WHEN :q = '' THEN 0
                              ELSE (nickname_hit::int * 4 + city_hit::int * 2 + style_hit::int) END AS score
                    FROM matched
                    WHERE :q = '' OR nickname_hit OR city_hit OR style_hit
                )
                SELECT * FROM ranked
                WHERE score < :score OR (score = :score AND id > CAST(:id AS uuid))
                ORDER BY score DESC, id LIMIT :size
                """).param("q", query).param("city", cityFilter).param("style", style).param("bookable", bookable)
                .param("score", after.score()).param("id", after.id()).param("size", limit + 1)
                .query((row, index) -> {
                    var fields = new java.util.ArrayList<String>(3);
                    if (!query.isEmpty()) {
                        if (row.getBoolean("nickname_hit")) fields.add("nickname");
                        if (row.getBoolean("city_hit")) fields.add("city");
                        if (row.getBoolean("style_hit")) fields.add("style");
                    }
                    return new Hit(row.getObject("id", UUID.class), row.getString("nickname"),
                            row.getString("city"), row.getString("bio"), fields, row.getInt("score"));
                }).list();
        if (hits.size() <= limit) {
            return new SearchPage(hits, null);
        }
        Hit last = hits.get(limit - 1);
        return new SearchPage(hits.subList(0, limit), last.score() + "." + last.id());
    }

    private static Cursor parseCursor(String value) {
        if (value == null) {
            return new Cursor(8, null);
        }
        try {
            if (value.length() == 38 && value.charAt(1) == '.' && value.charAt(0) >= '0' && value.charAt(0) <= '7') {
                UUID id = UUID.fromString(value.substring(2));
                return new Cursor(value.charAt(0) - '0', id);
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed cursors share the same response.
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "分页参数无效。");
    }

    private record Cursor(int score, UUID id) {}
    record Hit(UUID id, String nickname, String city, String bio, List<String> matchedFields, int score) {}
    record SearchPage(List<Hit> items, String nextCursor) {}
}
