package com.stagelog.media.application;

import com.stagelog.media.application.MediaTranscoder.SourceInfo;
import com.stagelog.media.domain.MediaAsset;
import com.stagelog.media.domain.MediaAssetRepository;
import com.stagelog.media.domain.MediaRendition;
import com.stagelog.media.domain.MediaRenditionRepository;
import com.stagelog.media.domain.RenditionFormat;
import com.stagelog.media.domain.RenditionProfile;
import com.stagelog.ops.application.Outbox;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 변환 원본 등록 공개 계약. 원본을 저장하는 기능(포스터 업로드·KOPIS 포스터 입수, 앨범 이미지 교체, 포토카드·네컷 생성)이 같은 트랜잭션에서 부른다.
 * asset 1행 + rendition 6행(3 profile × 2 format) + rendition 마다 Outbox 변환 메시지를 함께 쓰므로, 롤백되면 셋 다 없다.
 * jan.media.transcode.enabled 가 false 면 아무것도 하지 않는다(원본만 서빙하는 기존 동작).
 */
@Slf4j
@Service
public class MediaAssets {

    private final MediaAssetRepository assets;
    private final MediaRenditionRepository renditions;
    private final MediaTranscoder transcoder;
    private final Outbox outbox;
    private final boolean enabled;

    public MediaAssets(MediaAssetRepository assets, MediaRenditionRepository renditions, MediaTranscoder transcoder, Outbox outbox,
                       @Value("${jan.media.transcode.enabled:false}") boolean enabled) {
        this.assets = assets;
        this.renditions = renditions;
        this.transcoder = transcoder;
        this.outbox = outbox;
        this.enabled = enabled;
    }

    @Transactional
    public void register(MediaOwnerType ownerType, long ownerId, String originalKey, byte[] original) {
        register(ownerType, ownerId, originalKey, original, MediaTranscodeHandler.TRANSCODE_QUEUE);
    }

    // 같은 소유자·같은 내용이면 새로 만들지 않는다(재업로드·중복 수신). 만든 asset id, 이미 있으면 empty
    @Transactional
    public Optional<Long> register(MediaOwnerType ownerType, long ownerId, String originalKey, byte[] original, String queue) {
        if (!enabled || original == null || original.length == 0) {
            return Optional.empty();
        }
        // 1. 같은 내용이 이미 등록돼 있으면 key 만 맞춘다
        String sha256 = sha256(original);
        Optional<MediaAsset> existing = assets.findByOwnerTypeAndOwnerIdAndOriginalSha256(ownerType.name(), ownerId, sha256);
        if (existing.isPresent()) {
            if (!existing.get().getOriginalKey().equals(originalKey)) {
                existing.get().moveOriginal(originalKey);
            }
            return Optional.empty();
        }

        // 2. 형식·크기. 입구 검증에 실패한 원본도 행은 만든다: Worker 가 FFmpeg 전에 FAILED(INVALID_SOURCE) 로 끝내고, 서빙은 원본으로 대체한다
        SourceInfo info;
        try {
            info = transcoder.describe(original);
        } catch (TranscodeException e) {
            info = new SourceInfo("application/octet-stream", 0, 0, false);
        }
        MediaAsset asset = assets.save(new MediaAsset(ownerType.name(), ownerId, originalKey, sha256, info.width(), info.height(),
                info.contentType()));
        assets.flush();
        assets.stampCreatedNow(asset.getId());

        // 3. rendition 6행과 변환 메시지 (rendition 마다 1건이라 여러 Worker 가 나눠 처리한다)
        List<RenditionFormat> formats = info.formats();
        for (RenditionProfile profile : RenditionProfile.values()) {
            for (RenditionFormat format : formats) {
                MediaRendition rendition = renditions.save(new MediaRendition(asset.getId(), profile, format));
                outbox.append(queue, new MediaTranscodeHandler.Payload(rendition.getId()));
            }
        }
        log.info("[미디어 원본 등록] assetId={}, ownerType={}, ownerId={}, key={}, formats={}, queue={}", asset.getId(), ownerType,
                ownerId, originalKey, formats, queue);
        return Optional.of(asset.getId());
    }

    // 백필 재개: 이 소유자의 현재 key 로 이미 등록했으면 원본을 받지 않고 건너뛴다
    @Transactional(readOnly = true)
    public boolean isRegistered(MediaOwnerType ownerType, long ownerId, String originalKey) {
        return assets.existsByOwnerTypeAndOwnerIdAndOriginalKey(ownerType.name(), ownerId, originalKey);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }
}
