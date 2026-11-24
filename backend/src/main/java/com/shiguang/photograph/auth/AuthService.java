package com.shiguang.photograph.auth;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.shiguang.photograph.api.ApiException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long ACCESS_SECONDS = 900;
    private final JdbcClient jdbc;
    private final SmsGateway sms;
    private final SecretKeySpec otpKey;

    AuthService(JdbcClient jdbc, SmsGateway sms, @Value("${photograph.otp.secret}") String secret) {
        if (secret.length() < 32) {
            throw new IllegalArgumentException("PHOTOGRAPH_OTP_SECRET must be at least 32 characters");
        }
        this.jdbc = jdbc;
        this.sms = sms;
        this.otpKey = new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256");
    }

    UUID requestCode(String phoneInput) {
        String phone = normalizePhone(phoneInput);
        UUID id = UUID.randomUUID();
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        int written = jdbc.sql("""
                INSERT INTO photograph_app.otp_requests AS otp
                    (phone, challenge_id, code_digest, sent_at, expires_at, window_started_at, window_count)
                VALUES (:phone, :id, :digest, now(), now() + interval '5 minutes', now(), 1)
                ON CONFLICT (phone) DO UPDATE SET
                    challenge_id = EXCLUDED.challenge_id, code_digest = EXCLUDED.code_digest,
                    sent_at = EXCLUDED.sent_at, expires_at = EXCLUDED.expires_at,
                    window_started_at = CASE WHEN otp.window_started_at <= now() - interval '1 hour'
                                           THEN now() ELSE otp.window_started_at END,
                    window_count = CASE WHEN otp.window_started_at <= now() - interval '1 hour'
                                        THEN 1 ELSE otp.window_count + 1 END,
                    attempts_left = 5, consumed_at = NULL
                WHERE otp.sent_at <= now() - interval '1 minute'
                  AND (otp.window_count < 5 OR otp.window_started_at <= now() - interval '1 hour')
                """).param("phone", phone).param("id", id).param("digest", otpDigest(id, code)).update();
        if (written == 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMITED", "验证码请求过于频繁，请稍后再试。");
        }
        try {
            sms.sendOtp(phone, id, code);
        } catch (RuntimeException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_UNAVAILABLE", "短信发送暂不可用，请稍后再试。");
        }
        return id;
    }

    @Transactional
    public Optional<SessionTokens> login(UUID challengeId, String code) {
        if (challengeId == null || code == null || !code.matches("[0-9]{6}")) {
            throw ApiException.invalid("请输入有效的六位验证码。");
        }
        Optional<OtpRow> request = jdbc.sql("""
                SELECT phone, code_digest FROM photograph_app.otp_requests
                WHERE challenge_id = :id AND expires_at > now() AND consumed_at IS NULL AND attempts_left > 0
                FOR UPDATE
                """).param("id", challengeId)
                .query((rs, row) -> new OtpRow(rs.getString("phone"), rs.getString("code_digest"))).optional();
        if (request.isEmpty()) {
            return Optional.empty();
        }
        OtpRow otp = request.get();
        if (!MessageDigest.isEqual(otp.digest().getBytes(UTF_8), otpDigest(challengeId, code).getBytes(UTF_8))) {
            jdbc.sql("UPDATE photograph_app.otp_requests SET attempts_left = attempts_left - 1 WHERE challenge_id = :id")
                    .param("id", challengeId).update();
            return Optional.empty();
        }
        jdbc.sql("UPDATE photograph_app.otp_requests SET consumed_at = now() WHERE challenge_id = :id")
                .param("id", challengeId).update();
        UUID accountId = jdbc.sql("""
                INSERT INTO photograph_app.accounts (phone, nickname) VALUES (:phone, '新用户')
                ON CONFLICT (phone) DO UPDATE SET phone = EXCLUDED.phone RETURNING id
                """).param("phone", otp.phone()).query(UUID.class).single();
        SessionTokens tokens = newTokens();
        jdbc.sql("""
                INSERT INTO photograph_app.auth_sessions
                    (account_id, access_digest, refresh_digest, access_expires_at, refresh_expires_at)
                VALUES (:account, :access, :refresh, now() + interval '15 minutes', now() + interval '30 days')
                """).param("account", accountId)
                .param("access", digest(tokens.accessToken())).param("refresh", digest(tokens.refreshToken())).update();
        return Optional.of(tokens);
    }

    Optional<SessionTokens> refresh(String refreshToken) {
        if (!validToken(refreshToken)) {
            return Optional.empty();
        }
        SessionTokens tokens = newTokens();
        int count = jdbc.sql("""
                UPDATE photograph_app.auth_sessions
                SET access_digest = :access, refresh_digest = :refresh, access_expires_at = now() + interval '15 minutes'
                WHERE refresh_digest = :old AND refresh_expires_at > now()
                """).param("access", digest(tokens.accessToken())).param("refresh", digest(tokens.refreshToken()))
                .param("old", digest(refreshToken)).update();
        return count == 1 ? Optional.of(tokens) : Optional.empty();
    }

    public Optional<AccountPrincipal> authenticate(String accessToken) {
        if (!validToken(accessToken)) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT account.id, account.nickname, account.is_operator, session.id AS session_id
                FROM photograph_app.auth_sessions session JOIN photograph_app.accounts account ON account.id = session.account_id
                WHERE account.closed_at IS NULL AND session.access_digest = :digest
                  AND session.access_expires_at > now() AND session.refresh_expires_at > now()
                """).param("digest", digest(accessToken))
                .query((rs, row) -> new AccountPrincipal(rs.getObject("id", UUID.class),
                        rs.getObject("session_id", UUID.class), rs.getString("nickname"), rs.getBoolean("is_operator")))
                .optional();
    }

    void logout(AccountPrincipal principal) {
        jdbc.sql("DELETE FROM photograph_app.auth_sessions WHERE id = :session AND account_id = :account")
                .param("session", principal.sessionId()).param("account", principal.accountId()).update();
    }

    @Transactional
    public void closeAccount(AccountPrincipal principal) {
        UUID id = principal.accountId();
        // Lock the OTP row before the account, in the same order as OTP login.
        jdbc.sql("""
                DELETE FROM photograph_app.otp_requests WHERE phone =
                    (SELECT phone FROM photograph_app.accounts WHERE id = :id AND NOT is_operator)
                """).param("id", id).update();
        var account = jdbc.sql("""
                SELECT is_operator FROM photograph_app.accounts WHERE id = :id AND closed_at IS NULL FOR NO KEY UPDATE
                """).param("id", id).query(Boolean.class).optional();
        if (account.isEmpty()) return;
        if (account.get()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OPERATOR_ACCOUNT", "运营账号请联系平台管理员处理。");
        }

        // Booking creation locks the customer account and photographer profile before inserting.
        var profile = jdbc.sql("""
                SELECT id FROM photograph_app.photographer_profiles WHERE account_id = :id FOR UPDATE
                """).param("id", id).query(UUID.class).optional();
        boolean activeBookings = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM photograph_app.bookings
                    WHERE (customer_id = :id OR photographer_account_id = :id)
                      AND status IN ('PENDING', 'CONFIRMED', 'AWAITING_DELIVERY', 'DELIVERED'))
                """).param("id", id).query(Boolean.class).single();
        if (activeBookings) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_BOOKINGS", "请先处理进行中的约拍订单，或联系客服。");
        }

        if (profile.isPresent()) {
            UUID photographer = profile.get();
            jdbc.sql("""
                    UPDATE photograph_app.photographer_profiles SET approval_status = 'SUSPENDED',
                        city = '已注销', bio = '', calendar_visibility = 'PRIVATE', version = version + 1
                    WHERE id = :id
                    """).param("id", photographer).update();
            jdbc.sql("DELETE FROM photograph_app.photographer_styles WHERE photographer_id = :id")
                    .param("id", photographer).update();
            jdbc.sql("DELETE FROM photograph_app.availability_windows WHERE photographer_id = :id")
                    .param("id", photographer).update();
            jdbc.sql("DELETE FROM photograph_app.public_photography_works WHERE photographer_id = :id")
                    .param("id", photographer).update();
            jdbc.sql("UPDATE photograph_app.photography_packages SET is_listed = false WHERE photographer_id = :id")
                    .param("id", photographer).update();
        }

        jdbc.sql("DELETE FROM photograph_app.auth_sessions WHERE account_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM photograph_app.community_posts WHERE author_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM photograph_app.account_follows WHERE follower_id = :id OR followed_id = :id")
                .param("id", id).update();
        jdbc.sql("DELETE FROM photograph_app.booking_requests WHERE account_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM photograph_app.booking_notifications WHERE account_id = :id").param("id", id).update();
        jdbc.sql("""
                UPDATE photograph_app.accounts SET closed_at = now(), phone = NULL, nickname = '已注销用户',
                    accepts_paid_bookings = false, accepts_tfp_bookings = false WHERE id = :id
                """).param("id", id).update();
    }

    private static String normalizePhone(String raw) {
        if (raw == null) {
            throw ApiException.invalid("请输入手机号。");
        }
        String phone = raw.strip().replace(" ", "").replace("-", "");
        if (phone.matches("1[3-9][0-9]{9}")) {
            phone = "+86" + phone;
        }
        if (!phone.matches("\\+[1-9][0-9]{7,14}")) {
            throw ApiException.invalid("请输入有效的手机号或带区号的国际号码。");
        }
        return phone;
    }

    private String otpDigest(UUID id, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(otpKey);
            return HexFormat.of().formatHex(mac.doFinal((id + ":" + code).getBytes(UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    private static boolean validToken(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    private static SessionTokens newTokens() {
        return new SessionTokens(token(), token(), ACCESS_SECONDS, "Bearer");
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record OtpRow(String phone, String digest) {}
    record SessionTokens(String accessToken, String refreshToken, long expiresIn, String tokenType) {}
}
