package com.domwil.xrag.adapter.out.notify;

import com.domwil.xrag.domain.port.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Notifications par webhook entrant, payload {@code {"text": "..."}}
 * compatible Slack / Mattermost / Rocket.Chat (NOTIFY_WEBHOOK_URL dans .env).
 * Tout échec d'envoi est loggé puis avalé : le batch ne dépend jamais du canal.
 */
public class WebhookNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    private final RestClient http;
    private final String url;

    public WebhookNotifier(RestClient http, String url) {
        this.http = http;
        this.url = url;
    }

    @Override
    public void alert(String title, String message) {
        send(":rotating_light: *" + title + "*\n" + message);
    }

    @Override
    public void info(String title, String message) {
        send(":white_check_mark: *" + title + "*\n" + message);
    }

    private void send(String text) {
        try {
            http.post().uri(url)
                    .body(Map.of("text", text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Notification webhook en échec (le batch continue) : {}", e.getMessage());
        }
    }
}
