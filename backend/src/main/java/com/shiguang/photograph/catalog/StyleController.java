package com.shiguang.photograph.catalog;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StyleController {
    private final JdbcClient jdbc;

    StyleController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/styles")
    List<Style> getStyles() {
        return jdbc.sql("""
                SELECT id, name
                FROM photograph_app.styles
                ORDER BY display_order, id
                """)
                .query((row, index) -> new Style(row.getString("id"), row.getString("name")))
                .list();
    }

    record Style(String id, String name) {}
}
