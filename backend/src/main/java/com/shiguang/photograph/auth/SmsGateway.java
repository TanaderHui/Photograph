package com.shiguang.photograph.auth;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

interface SmsGateway {
    void sendOtp(String phone, UUID challengeId, String code);
}

@Configuration
class SmsConfiguration {
    @Bean
    @Profile("local")
    SmsGateway localSms() {
        var logger = LoggerFactory.getLogger("photograph.local.sms");
        return (phone, id, code) -> logger.info("Local login code for challenge {} is {}", id, code);
    }

    @Bean
    @Profile("!local")
    SmsGateway smsWebhook(@Value("${photograph.sms.url:}") String url,
                          @Value("${photograph.sms.token:}") String token) {
        if (url.isBlank() || token.isBlank()) {
            return (phone, id, code) -> { throw new IllegalStateException("SMS gateway is unconfigured"); };
        }
        URI endpoint = URI.create(url);
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("SMS gateway must use an HTTPS endpoint");
        }
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        RestClient client = RestClient.builder().requestFactory(requestFactory).build();
        return (phone, id, code) -> client.post().uri(endpoint)
                .headers(headers -> headers.setBearerAuth(token))
                .body(Map.of("phone", phone, "challengeId", id, "code", code, "expiresIn", 300))
                .retrieve().toBodilessEntity();
    }
}
