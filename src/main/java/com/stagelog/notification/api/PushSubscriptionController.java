package com.stagelog.notification.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.common.auth.CurrentUser;
import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.notification.application.SubscriptionCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 웹 푸시 공개 API. 공개키 GET 은 공개, 구독 등록 POST·해지 DELETE 는 로그인 + 동의 필수 (SecurityConfig·ConsentInterceptor).
 */
@Tag(name = "웹 푸시 (Push)", description = "VAPID 공개키 조회와 브라우저 구독 등록·해지 API")
@RestController
@RequestMapping(V1_BASE + "/push")
public class PushSubscriptionController {

    private final SubscriptionCommand subscriptionCommand;
    private final String vapidPublicKey;

    public PushSubscriptionController(SubscriptionCommand subscriptionCommand,
                                      @Value("${stagelog.push.vapid.public-key:}") String vapidPublicKey) {
        this.subscriptionCommand = subscriptionCommand;
        this.vapidPublicKey = vapidPublicKey;
    }

    @Operation(summary = "VAPID 공개키", description = "pushManager.subscribe({ applicationServerKey }) 에 넣을 서버 공개키를 냅니다.")
    @GetMapping("/vapid-public-key")
    public ResponseDTO<VapidPublicKeyResponse> vapidPublicKey() {
        return ResponseDTO.okWithData(new VapidPublicKeyResponse(vapidPublicKey));
    }

    @Operation(summary = "푸시 구독 등록", description = "PushSubscription.toJSON() 을 그대로 보냅니다. 같은 endpoint 는 키·사용자를 갱신합니다 (400 PUSH_SUBSCRIPTION_INVALID).")
    @PostMapping("/subscriptions")
    public ResponseDTO<Void> subscribe(
            @RequestBody @Valid PushSubscriptionRequest request,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        subscriptionCommand.subscribe(user.userId(), request.endpoint(), request.keys().p256dh(), request.keys().auth(), userAgent);
        return ResponseDTO.ok();
    }

    @Operation(summary = "푸시 구독 해지",
            description = "브라우저 PushSubscription.endpoint 로 내 구독을 해지합니다. 그 구독으로 아직 나가지 않은 알림은 취소됩니다. "
                    + "없는 endpoint 이거나 다른 사용자의 구독이면 아무것도 하지 않고 OK 를 냅니다(멱등). "
                    + "로그아웃·알림 끄기 때 pushManager 구독 해제와 함께 부릅니다.")
    @DeleteMapping("/subscriptions")
    public ResponseDTO<Void> unsubscribe(
            @RequestBody @Valid PushUnsubscribeRequest request,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        subscriptionCommand.unsubscribe(user.userId(), request.endpoint());
        return ResponseDTO.ok();
    }
}
