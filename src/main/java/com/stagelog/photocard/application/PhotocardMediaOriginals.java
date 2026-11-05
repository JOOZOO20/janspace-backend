package com.stagelog.photocard.application;

import com.stagelog.media.application.MediaOriginals;
import com.stagelog.media.application.MediaOwnerType;
import com.stagelog.photocard.domain.PhotocardKind;
import com.stagelog.photocard.domain.PhotocardRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미디어 백필에 포토카드(카드·네컷) 원본을 내준다 (media 의 역방향 계약 구현).
 */
@Component
@RequiredArgsConstructor
public class PhotocardMediaOriginals implements MediaOriginals {

    private final PhotocardRepository photocards;

    @Override
    public boolean supports(MediaOwnerType ownerType) {
        return ownerType == MediaOwnerType.PHOTOCARD || ownerType == MediaOwnerType.FOURCUT;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Entry> after(MediaOwnerType ownerType, long afterOwnerId, int limit) {
        PhotocardKind kind = ownerType == MediaOwnerType.FOURCUT ? PhotocardKind.FOURCUT : PhotocardKind.CARD;
        return photocards.findImageKeysAfter(kind, afterOwnerId, PageRequest.of(0, limit)).stream()
                .map(row -> new Entry((Long) row[0], (String) row[1])).toList();
    }
}
