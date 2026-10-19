package com.janspace.ticket.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.exception.predefined.ConflictException;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.ticket.domain.TicketSkin;
import com.janspace.ticket.domain.TicketSkinRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 티켓 스킨: 공개 목록(오늘 UTC 배포 중인 시즌 스킨 + 기본 스킨), 관리자 전체 목록·upsert·삭제. 기본 스킨은 최대 하나이고 지울 수 없다.
 * 최대 하나는 DB(uk_ticket_skins_default)가 보장한다. 0개는 허용한다(프론트가 내장 기본색으로 그린다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TicketSkinService {

    private final TicketSkinRepository skins;

    /** 관리자 upsert 입력. id 가 있으면 그 스킨을 갱신한다. isDefault 가 null 이면 기존 값 유지(신규는 false). */
    public record Upsert(Long id, String name, String season, String paper, String accent, String ink, LocalDate availableFrom,
                         LocalDate availableTo, Boolean isDefault) {
    }

    // 스킨 수가 수십 개 수준이라 전부 읽고 거른다
    @Transactional(readOnly = true)
    public List<TicketSkin> listed() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return skins.findAllByOrderByIdAsc().stream().filter(s -> s.isListedOn(today)).toList();
    }

    @Transactional(readOnly = true)
    public List<TicketSkin> all() {
        return skins.findAllByOrderByIdAsc();
    }

    public TicketSkin upsert(Upsert cmd) {
        if (cmd.availableFrom() != null && cmd.availableTo() != null && cmd.availableFrom().isAfter(cmd.availableTo())) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "availableFrom 은 availableTo 보다 늦을 수 없습니다.");
        }
        TicketSkin skin = cmd.id() == null ? TicketSkin.create()
                : skins.findById(cmd.id()).orElseThrow(() -> new NotFoundException(ErrorCode.TICKET_SKIN_NOT_FOUND));
        skin.update(cmd.name(), cmd.season(), cmd.paper(), cmd.accent(), cmd.ink(), cmd.availableFrom(), cmd.availableTo());
        if (Boolean.TRUE.equals(cmd.isDefault())) {
            // 기본은 하나: 기존 기본 스킨을 내리고 먼저 반영한다. 올리는 UPDATE·INSERT 가 앞서면 유일 제약에 걸린다
            skins.findAllByIsDefaultTrue().forEach(other -> other.markDefault(false));
            skins.flush();
        }
        if (cmd.isDefault() != null) {
            skin.markDefault(cmd.isDefault());
        }
        TicketSkin saved;
        try {
            saved = skins.saveAndFlush(skin);
        } catch (DataIntegrityViolationException e) {
            // 두 관리자가 동시에 서로 다른 스킨을 기본으로 올리면 늦은 쪽이 여기서 막힌다
            log.warn("[검증 실패 - 기본 스킨 동시 지정] skinId={}", cmd.id());
            throw new ConflictException(ErrorCode.CONFLICT, "다른 스킨이 방금 기본으로 지정됐습니다. 새로 고친 뒤 다시 시도해 주세요.");
        }
        log.info("[티켓 스킨 upsert] skinId={}, 신규={}, isDefault={}", saved.getId(), cmd.id() == null, saved.isDefault());
        return saved;
    }

    // 그 스킨을 쓰던 티켓의 skin_id 는 FK(ON DELETE SET NULL)가 비운다
    public void delete(Long id) {
        TicketSkin skin = skins.findById(id).orElseThrow(() -> new NotFoundException(ErrorCode.TICKET_SKIN_NOT_FOUND));
        if (skin.isDefault()) {
            throw new BadRequestException(ErrorCode.TICKET_SKIN_DEFAULT);
        }
        skins.delete(skin);
        log.info("[티켓 스킨 삭제] skinId={}", id);
    }
}
