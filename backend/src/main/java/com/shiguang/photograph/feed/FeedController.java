package com.shiguang.photograph.feed;

import com.shiguang.photograph.api.ApiException;
import com.shiguang.photograph.auth.AccountPrincipal;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class FeedController {
    private final JdbcClient jdbc;

    private final boolean communityEnabled;

    FeedController(JdbcClient jdbc, @Value("${photograph.community.enabled}") boolean communityEnabled) {
        this.jdbc = jdbc;
        this.communityEnabled = communityEnabled;
    }

    private void requireCommunity() {
        if (!communityEnabled) throw ApiException.notFound();
    }

    @GetMapping("/feed")
    Page<Post> posts(@AuthenticationPrincipal AccountPrincipal user, @RequestParam(defaultValue = "discover") String scope,
                     @RequestParam(defaultValue = "") String q, @RequestParam(required = false) String cursor,
                     @RequestParam(defaultValue = "20") int limit) {
        requireCommunity();
        Query query = parse(scope, q, cursor, limit);
        var items = feed("""
                SELECT post.id, author.id AS author_id, author.nickname, post.body, post.created_at,
                       follow.followed_id IS NOT NULL AS following
                FROM photograph_app.community_posts post
                JOIN photograph_app.accounts author ON author.id = post.author_id
                LEFT JOIN photograph_app.account_follows follow
                    ON follow.follower_id = CAST(:viewer AS uuid) AND follow.followed_id = author.id
                WHERE post.removed_at IS NULL
                  AND (:scope = 'discover' OR (:scope = 'following' AND follow.followed_id IS NOT NULL)
                       OR (:scope = 'mine' AND author.id = CAST(:viewer AS uuid)))
                  AND (:query = '' OR strpos(lower(post.body), lower(:query)) > 0
                       OR strpos(lower(author.nickname), lower(:query)) > 0)
                  AND (CAST(:time AS timestamptz) IS NULL OR (post.created_at, post.id) < (CAST(:time AS timestamptz), CAST(:id AS uuid)))
                ORDER BY post.created_at DESC, post.id DESC LIMIT :max
                """, query, user).query(FeedController::readPost).list();
        return page(items, limit, post -> new Anchor(post.createdAt(), post.id()));
    }

    @GetMapping("/posts/{id}")
    Post post(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id) {
        requireCommunity();
        return jdbc.sql("""
                SELECT post.id, author.id AS author_id, author.nickname, post.body, post.created_at,
                       follow.followed_id IS NOT NULL AS following
                FROM photograph_app.community_posts post
                JOIN photograph_app.accounts author ON author.id = post.author_id
                LEFT JOIN photograph_app.account_follows follow
                    ON follow.follower_id = CAST(:viewer AS uuid) AND follow.followed_id = author.id
                WHERE post.id = :id AND post.removed_at IS NULL
                """).param("viewer", viewer(user)).param("id", id).query(FeedController::readPost)
                .optional().orElseThrow(ApiException::notFound);
    }

    @PostMapping("/posts")
    ResponseEntity<Post> publish(@AuthenticationPrincipal AccountPrincipal user, @RequestBody Draft draft) {
        requireCommunity();
        String body = draft.body() == null ? "" : draft.body().strip();
        if (body.isEmpty() || body.length() > 2000 || body.indexOf(0) >= 0 || draft.clientPostId() == null) {
            throw ApiException.invalid("正文须为 1 至 2000 字。");
        }
        UUID id = draft.clientPostId();
        var created = jdbc.sql("""
                INSERT INTO photograph_app.community_posts (id, author_id, body)
                SELECT :id, account.id, :body FROM photograph_app.accounts account
                WHERE account.id = :author AND account.closed_at IS NULL FOR NO KEY UPDATE
                ON CONFLICT (id) DO NOTHING RETURNING id
                """).param("id", id).param("author", user.accountId()).param("body", body).query(UUID.class).optional();
        if (created.isEmpty()) {
            boolean matches = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM photograph_app.community_posts
                        WHERE id = :id AND author_id = :author AND body = :body AND removed_at IS NULL)
                    """).param("id", id).param("author", user.accountId()).param("body", body).query(Boolean.class).single();
            if (!matches) throw ApiException.conflict("帖子发送标识已使用。");
        }
        return ResponseEntity.created(URI.create("/api/v1/posts/" + id)).body(post(user, id));
    }

    @DeleteMapping("/me/posts/{id}")
    ResponseEntity<Void> remove(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id) {
        int changed = jdbc.sql("""
                UPDATE photograph_app.community_posts SET removed_at = coalesce(removed_at, now())
                WHERE id = :id AND author_id = :author
                """).param("id", id).param("author", user.accountId()).update();
        if (changed == 0) throw ApiException.notFound();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/works")
    Page<Work> works(@AuthenticationPrincipal AccountPrincipal user, @RequestParam(defaultValue = "discover") String scope,
                     @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String city,
                     @RequestParam(defaultValue = "") String style, @RequestParam(required = false) String cursor,
                     @RequestParam(defaultValue = "20") int limit) {
        Query query = parse(scope, q, cursor, limit);
        var items = feed("""
                SELECT work.id, profile.id AS photographer_id, author.id AS author_id, author.nickname, profile.city,
                       profile.bio, work.title, work.caption, work.thumbnail_url, work.published_at,
                       style.name AS style_name, follow.followed_id IS NOT NULL AS following
                FROM photograph_app.public_photography_works work
                JOIN photograph_app.photographer_profiles profile ON profile.id = work.photographer_id
                JOIN photograph_app.accounts author ON author.id = profile.account_id
                LEFT JOIN photograph_app.styles style ON style.id = work.style_id
                LEFT JOIN photograph_app.account_follows follow
                    ON follow.follower_id = CAST(:viewer AS uuid) AND follow.followed_id = author.id
                WHERE work.withdrawn_at IS NULL AND profile.approval_status = 'APPROVED'
                  AND (:scope = 'discover' OR (:scope = 'following' AND follow.followed_id IS NOT NULL)
                       OR (:scope = 'mine' AND author.id = CAST(:viewer AS uuid)))
                  AND (:city = '' OR strpos(lower(profile.city), lower(:city)) > 0)
                  AND (:style = '' OR work.style_id = :style)
                  AND (:query = '' OR strpos(lower(work.title), lower(:query)) > 0
                       OR strpos(lower(work.caption), lower(:query)) > 0
                       OR strpos(lower(author.nickname), lower(:query)) > 0
                       OR strpos(lower(profile.city), lower(:query)) > 0
                       OR strpos(lower(style.name), lower(:query)) > 0)
                  AND (CAST(:time AS timestamptz) IS NULL OR (work.published_at, work.id) < (CAST(:time AS timestamptz), CAST(:id AS uuid)))
                ORDER BY work.published_at DESC, work.id DESC LIMIT :max
                """, query, user).param("city", clean(city, 100)).param("style", clean(style, 100))
                .query((rs, row) -> new Work(rs.getObject("id", UUID.class), rs.getObject("photographer_id", UUID.class),
                        rs.getObject("author_id", UUID.class), rs.getString("nickname"), rs.getString("city"),
                        rs.getString("bio"), rs.getString("title"), rs.getString("caption"), rs.getString("thumbnail_url"),
                        rs.getString("style_name"), rs.getObject("published_at", OffsetDateTime.class),
                        rs.getBoolean("following"))).list();
        return page(items, limit, work -> new Anchor(work.publishedAt(), work.id()));
    }

    @PutMapping("/me/following/{id}")
    Follow follow(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id) {
        requireCommunity();
        if (id.equals(user.accountId())) throw ApiException.invalid("不能关注自己。");
        boolean available = jdbc.sql("""
                WITH open_accounts AS MATERIALIZED (
                    SELECT id FROM photograph_app.accounts
                    WHERE id IN (:viewer, :id) AND closed_at IS NULL
                    ORDER BY id FOR NO KEY UPDATE
                ), added AS (
                    INSERT INTO photograph_app.account_follows (follower_id, followed_id)
                    SELECT :viewer, :id WHERE (SELECT count(*) FROM open_accounts) = 2
                    ON CONFLICT DO NOTHING
                )
                SELECT count(*) = 2 FROM open_accounts
                """).param("viewer", user.accountId()).param("id", id).query(Boolean.class).single();
        if (!available) throw ApiException.notFound();
        return new Follow(true);
    }

    @DeleteMapping("/me/following/{id}")
    Follow unfollow(@AuthenticationPrincipal AccountPrincipal user, @PathVariable UUID id) {
        requireCommunity();
        jdbc.sql("DELETE FROM photograph_app.account_follows WHERE follower_id = :viewer AND followed_id = :id")
                .param("viewer", user.accountId()).param("id", id).update();
        return new Follow(false);
    }

    private JdbcClient.StatementSpec feed(String sql, Query query, AccountPrincipal user) {
        return jdbc.sql(sql).param("viewer", viewer(user)).param("scope", query.scope()).param("query", query.text())
                .param("time", query.anchor().time()).param("id", query.anchor().id()).param("max", query.limit() + 1);
    }

    private static Query parse(String scope, String q, String cursor, int limit) {
        if (!Set.of("discover", "following", "mine").contains(scope) || limit < 1 || limit > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FEED", "筛选或分页参数无效。");
        }
        Anchor anchor = new Anchor(null, null);
        if (cursor != null) {
            try {
                String[] parts = cursor.split("~", 2);
                if (parts.length != 2 || cursor.length() > 100) throw new IllegalArgumentException();
                anchor = new Anchor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (IllegalArgumentException | java.time.DateTimeException error) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "分页参数无效。");
            }
        }
        return new Query(scope, clean(q, 100), anchor, limit);
    }

    private static String clean(String raw, int max) {
        String text = raw.strip();
        if (text.length() > max || text.indexOf(0) >= 0) throw ApiException.invalid("筛选文字无效。");
        return text;
    }

    private static <T> Page<T> page(List<T> items, int limit, Function<T, Anchor> key) {
        if (items.size() <= limit) return new Page<>(items, null);
        Anchor last = key.apply(items.get(limit - 1));
        return new Page<>(items.subList(0, limit), last.time() + "~" + last.id());
    }

    private static UUID viewer(AccountPrincipal user) { return user == null ? null : user.accountId(); }

    private static Post readPost(ResultSet rs, int row) throws SQLException {
        return new Post(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class), rs.getString("nickname"),
                rs.getString("body"), rs.getObject("created_at", OffsetDateTime.class), rs.getBoolean("following"));
    }

    private record Anchor(OffsetDateTime time, UUID id) {}
    private record Query(String scope, String text, Anchor anchor, int limit) {}
    record Page<T>(List<T> items, String nextCursor) {}
    record Draft(UUID clientPostId, String body) {}
    record Follow(boolean following) {}
    record Post(UUID id, UUID authorId, String nickname, String body, OffsetDateTime createdAt, boolean following) {}
    record Work(UUID id, UUID photographerId, UUID authorId, String nickname, String city, String bio, String title,
                String caption, String thumbnailUrl, String styleName, OffsetDateTime publishedAt, boolean following) {}
}
