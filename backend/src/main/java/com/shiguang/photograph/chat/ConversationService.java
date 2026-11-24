package com.shiguang.photograph.chat;

import com.shiguang.photograph.api.ApiException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ConversationService {
    private static final String INBOX_SELECT = """
            SELECT thread.id, peer.id AS peer_id, peer.nickname AS peer_name,
                   peer.closed_at IS NOT NULL AS peer_closed, thread.updated_at,
                   latest.text AS last_text,
                   (SELECT count(*) FROM photograph_app.conversation_messages unread
                    WHERE unread.conversation_id = thread.id AND unread.sender_id <> :account
                      AND unread.seq > coalesce(receipt.read_seq, 0)) AS unread_count
            FROM photograph_app.conversations thread
            JOIN photograph_app.accounts peer ON peer.id =
                CASE WHEN thread.first_account_id = :account THEN thread.second_account_id ELSE thread.first_account_id END
            LEFT JOIN photograph_app.conversation_messages latest
                ON latest.conversation_id = thread.id AND latest.seq = thread.last_seq
            LEFT JOIN photograph_app.conversation_reads receipt
                ON receipt.conversation_id = thread.id AND receipt.account_id = :account
            WHERE :account IN (thread.first_account_id, thread.second_account_id)
            """;
    private static final String MESSAGE_SELECT = """
            SELECT seq, sender_id, client_message_id, text, created_at
            FROM photograph_app.conversation_messages WHERE conversation_id = :id
            """;

    private final JdbcClient jdbc;

    ConversationService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Conversation open(UUID account, UUID photographer, UUID booking) {
        if ((photographer == null) == (booking == null)) throw ApiException.invalid("请选择摄影师或约拍订单。");
        UUID peer = booking == null ? photographerAccount(photographer) : bookingPeer(account, booking);
        if (account.equals(peer)) throw ApiException.invalid("不能和自己发起私聊。");
        lockOpenAccounts(account, peer);
        UUID id = jdbc.sql("""
                INSERT INTO photograph_app.conversations (first_account_id, second_account_id)
                VALUES (least(:account, :peer), greatest(:account, :peer))
                ON CONFLICT (first_account_id, second_account_id) DO NOTHING RETURNING id
                """).param("account", account).param("peer", peer).query(UUID.class).optional().orElseGet(() ->
                jdbc.sql("""
                        SELECT id FROM photograph_app.conversations
                        WHERE first_account_id = least(:account, :peer) AND second_account_id = greatest(:account, :peer)
                        """).param("account", account).param("peer", peer).query(UUID.class).single());
        return jdbc.sql(INBOX_SELECT + " AND thread.id = :id").param("account", account).param("id", id)
                .query(Conversation.class).single();
    }

    Page<Conversation> inbox(UUID account, String cursor, int limit) {
        checkLimit(limit);
        Anchor anchor = parseCursor(cursor);
        var items = jdbc.sql(INBOX_SELECT + """
                 AND thread.last_seq > 0
                 AND (CAST(:updated AS timestamptz) IS NULL OR (thread.updated_at, thread.id) < (:updated, CAST(:id AS uuid)))
                 ORDER BY thread.updated_at DESC, thread.id DESC LIMIT :size
                """).param("account", account).param("updated", anchor.updatedAt()).param("id", anchor.id())
                .param("size", limit + 1).query(Conversation.class).list();
        return page(items, limit, last -> last.updatedAt() + "~" + last.id());
    }

    Page<Message> history(UUID account, UUID id, Long before, Long after, int limit) {
        checkLimit(limit);
        if ((before != null && before < 1) || (after != null && after < 0) || (before != null && after != null)) {
            throw ApiException.invalid("聊天分页参数无效。");
        }
        requireParticipant(account, id);
        String criteria = after != null ? " AND seq > :anchor ORDER BY seq ASC"
                : " AND seq < :anchor ORDER BY seq DESC";
        long seq = after != null ? after : before != null ? before : Long.MAX_VALUE;
        var items = jdbc.sql(MESSAGE_SELECT + criteria + " LIMIT :size").param("id", id).param("anchor", seq)
                .param("size", limit + 1).query(Message.class).list();
        return page(items, limit, last -> Long.toString(last.seq()));
    }

    @Transactional
    public Message send(UUID account, UUID id, Send request) {
        String text = request.text() == null ? "" : request.text().strip();
        if (request.clientMessageId() == null || text.isBlank() || text.length() > 2000 || text.indexOf(0) >= 0) {
            throw ApiException.invalid("请输入 1 至 2000 字的聊天消息。");
        }
        Thread thread = requireParticipant(account, id);
        lockOpenAccounts(thread.firstAccountId(), thread.secondAccountId());
        var saved = jdbc.sql(MESSAGE_SELECT + " AND sender_id = :sender AND client_message_id = :client")
                .param("id", id).param("sender", account).param("client", request.clientMessageId())
                .query(Message.class).optional();
        if (saved.isPresent()) {
            if (!saved.get().text().equals(text)) throw ApiException.conflict("消息标识已使用。");
            return saved.get();
        }
        long seq = jdbc.sql("""
                UPDATE photograph_app.conversations SET last_seq = last_seq + 1, updated_at = clock_timestamp()
                WHERE id = :id RETURNING last_seq
                """).param("id", id).query(Long.class).single();
        return jdbc.sql("""
                INSERT INTO photograph_app.conversation_messages (conversation_id, seq, sender_id, client_message_id, text)
                VALUES (:id, :seq, :sender, :client, :text) RETURNING seq, sender_id, client_message_id, text, created_at
                """).param("id", id).param("seq", seq).param("sender", account).param("client", request.clientMessageId())
                .param("text", text).query(Message.class).single();
    }

    void read(UUID account, UUID id, Long seq) {
        Thread thread = requireParticipant(account, id);
        if (seq == null || seq < 1 || seq > thread.lastSeq()) throw ApiException.invalid("已读消息序号无效。");
        jdbc.sql("""
                INSERT INTO photograph_app.conversation_reads AS receipt (conversation_id, account_id, read_seq)
                VALUES (:id, :account, :seq) ON CONFLICT (conversation_id, account_id)
                DO UPDATE SET read_seq = greatest(receipt.read_seq, EXCLUDED.read_seq)
                """).param("id", id).param("account", account).param("seq", seq).update();
    }

    private UUID photographerAccount(UUID photographer) {
        return jdbc.sql("""
                SELECT account_id FROM photograph_app.photographer_profiles
                WHERE id = :id AND approval_status = 'APPROVED'
                """).param("id", photographer).query(UUID.class).optional().orElseThrow(ApiException::notFound);
    }

    private UUID bookingPeer(UUID account, UUID booking) {
        return jdbc.sql("""
                SELECT CASE WHEN customer_id = :account THEN photographer_account_id ELSE customer_id END
                FROM photograph_app.bookings
                WHERE id = :id AND :account IN (customer_id, photographer_account_id)
                """).param("id", booking).param("account", account).query(UUID.class).optional().orElseThrow(ApiException::notFound);
    }

    private void lockOpenAccounts(UUID first, UUID second) {
        var accounts = jdbc.sql("""
                SELECT id FROM photograph_app.accounts WHERE id IN (:first, :second) AND closed_at IS NULL
                ORDER BY id FOR NO KEY UPDATE
                """).param("first", first).param("second", second).query(UUID.class).list();
        if (accounts.size() != 2) throw ApiException.conflict("对方账号已注销或暂不可联系。");
    }

    private Thread requireParticipant(UUID account, UUID id) {
        return jdbc.sql("""
                SELECT first_account_id, second_account_id, last_seq FROM photograph_app.conversations
                WHERE id = :id AND :account IN (first_account_id, second_account_id)
                """).param("id", id).param("account", account).query(Thread.class).optional().orElseThrow(ApiException::notFound);
    }

    private static Anchor parseCursor(String cursor) {
        if (cursor == null) return new Anchor(null, null);
        if (cursor.length() <= 100) {
            String[] pieces = cursor.split("~", -1);
            try {
                if (pieces.length == 2) return new Anchor(OffsetDateTime.parse(pieces[0]), UUID.fromString(pieces[1]));
            } catch (IllegalArgumentException | DateTimeParseException ignored) {
                // Malformed cursors share the same response.
            }
        }
        throw ApiException.invalid("聊天分页参数无效。");
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > 50) throw ApiException.invalid("每页记录数须介于 1 至 50。");
    }

    private static <T> Page<T> page(List<T> items, int limit, Function<T, String> cursor) {
        return items.size() > limit ? new Page<>(items.subList(0, limit), cursor.apply(items.get(limit - 1)))
                : new Page<>(items, null);
    }

    record Send(UUID clientMessageId, String text) {}
    record Message(long seq, UUID senderId, UUID clientMessageId, String text, OffsetDateTime createdAt) {}
    record Conversation(UUID id, UUID peerId, String peerName, boolean peerClosed, OffsetDateTime updatedAt,
                        String lastText, long unreadCount) {}
    record Page<T>(List<T> items, String nextCursor) {}
    private record Thread(UUID firstAccountId, UUID secondAccountId, long lastSeq) {}
    private record Anchor(OffsetDateTime updatedAt, UUID id) {}
}
