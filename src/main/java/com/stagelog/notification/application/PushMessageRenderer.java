package com.stagelog.notification.application;

import com.stagelog.common.locale.LocalizedText;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.notification.domain.PushKind;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 서비스 워커가 읽는 페이로드 {kind, eventId, title, body, url} 를 사용자 로케일로 만든다.
 * 문구는 notification/push-messages 번들(기본 ko), 행사 이름은 LocalizedText 폴백(zh-TW/ja -> en -> ko -> 원어).
 */
@Component
@RequiredArgsConstructor
public class PushMessageRenderer {

    public record PushPayload(String kind, Long eventId, String title, String body, String url) {
    }

    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;

    public PushPayload payload(PushKind kind, Long eventId, LocalizedText eventName, SupportedLocale locale) {
        SupportedLocale effective = locale == null ? SupportedLocale.KO : locale;
        Locale javaLocale = Locale.forLanguageTag(effective.tag());
        Object[] args = {eventName.resolve(effective)};
        String title = messageSource.getMessage(kind.messageKey() + ".title", args, javaLocale);
        String body = messageSource.getMessage(kind.messageKey() + ".body", args, javaLocale);
        return new PushPayload(kind.payloadKind(), eventId, title, body, "/events/" + eventId);
    }

    public String render(PushKind kind, Long eventId, LocalizedText eventName, SupportedLocale locale) {
        return objectMapper.writeValueAsString(payload(kind, eventId, eventName, locale));
    }
}
