package com.shiguang.photograph.feed;

import com.shiguang.photograph.api.ApiException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class PlacesController {
    private static final String PLACE_SELECT = """
            SELECT id, name, city, category, description, public.ST_X(location) AS longitude,
                   public.ST_Y(location) AS latitude
            FROM photograph_app.public_photo_places
            WHERE withdrawn_at IS NULL
            """;
    private final JdbcClient jdbc;

    PlacesController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/places")
    Items<Place> places(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String city) {
        if (q.length() > 100 || city.length() > 100 || q.indexOf(0) >= 0 || city.indexOf(0) >= 0) {
            throw ApiException.invalid("地点关键词过长或格式无效。");
        }
        return new Items<>(jdbc.sql(PLACE_SELECT + """
                AND (:city = '' OR strpos(lower(city), lower(:city)) > 0)
                AND (:q = '' OR strpos(lower(name), lower(:q)) > 0 OR strpos(lower(city), lower(:q)) > 0)
                ORDER BY city, name, id LIMIT 30
                """).param("q", cityAlias(q)).param("city", cityAlias(city)).query(Place.class).list());
    }

    @GetMapping("/places/{id}")
    Place place(@PathVariable String id) {
        return jdbc.sql(PLACE_SELECT + " AND id = :id").param("id", id).query(Place.class).optional()
                .orElseThrow(ApiException::notFound);
    }

    @GetMapping("/map/markers")
    Items<Marker> markers(@RequestParam String bbox, @RequestParam double zoom,
                          @RequestParam(defaultValue = "") String category) {
        double[] bounds = bounds(bbox);
        if (!Double.isFinite(zoom) || zoom < 0 || zoom > 23 ||
                (!category.isEmpty() && !category.equals("SCENIC") && !category.equals("SPOT"))) {
            throw ApiException.invalid("地图缩放级别或地点分类无效。");
        }
        var items = jdbc.sql("""
                WITH visible AS (
                    SELECT id, name, category, location, public.ST_SnapToGrid(location, :cell) AS cell
                    FROM photograph_app.public_photo_places
                    WHERE withdrawn_at IS NULL AND (:category = '' OR category = :category)
                      AND (
                        (:west <= :east AND public.ST_Intersects(location,
                          public.ST_MakeEnvelope(:west, :south, :east, :north, 4326)))
                        OR
                        (:west > :east AND (public.ST_Intersects(location,
                          public.ST_MakeEnvelope(:west, :south, 180, :north, 4326))
                          OR public.ST_Intersects(location,
                          public.ST_MakeEnvelope(-180, :south, :east, :north, 4326))))
                      )
                )
                SELECT CASE WHEN count(*) = 1 THEN 'PLACE' ELSE 'CLUSTER' END AS type,
                       CASE WHEN count(*) = 1 THEN min(id) END AS place_id,
                       CASE WHEN count(*) = 1 THEN min(name) END AS name,
                       CASE WHEN count(*) = 1 THEN min(category) END AS category,
                       avg(public.ST_X(location)) AS longitude, avg(public.ST_Y(location)) AS latitude,
                       count(*) AS count
                FROM visible GROUP BY cell
                ORDER BY count(*) DESC, avg(public.ST_X(location)), avg(public.ST_Y(location))
                LIMIT 250
                """).param("west", bounds[0]).param("south", bounds[1]).param("east", bounds[2])
                .param("north", bounds[3]).param("cell", 45.0 / Math.pow(2, zoom)).param("category", category)
                .query(Marker.class).list();
        return new Items<>(items);
    }

    private static double[] bounds(String bbox) {
        try {
            String[] parts = bbox.split(",", -1);
            if (bbox.length() <= 180 && parts.length == 4) {
                double[] values = Arrays.stream(parts).mapToDouble(Double::parseDouble).toArray();
                if (Arrays.stream(values).allMatch(Double::isFinite) && Math.abs(values[0]) <= 180
                        && Math.abs(values[2]) <= 180 && Math.abs(values[1]) <= 90
                        && Math.abs(values[3]) <= 90 && values[1] < values[3] && values[0] != values[2]) {
                    return values;
                }
            }
        } catch (NumberFormatException ignored) {
            // Invalid bounds use one error response.
        }
        throw ApiException.invalid("地图范围须按西、南、东、北的顺序填写。");
    }

    private static String cityAlias(String input) {
        String value = input.strip();
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "beijing" -> "北京";
            case "shanghai" -> "上海";
            case "hangzhou" -> "杭州";
            case "new york", "new york city" -> "纽约";
            default -> value;
        };
    }

    record Place(String id, String name, String city, String category, String description, double longitude, double latitude) {}
    record Marker(String type, String placeId, String name, String category, double longitude, double latitude, int count) {}
    record Items<T>(List<T> items) {}
}
