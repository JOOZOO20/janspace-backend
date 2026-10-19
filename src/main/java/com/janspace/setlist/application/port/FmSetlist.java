package com.janspace.setlist.application.port;

import java.util.List;

/**
 * setlist.fm 셋리 하나를 setlist 기능이 보는 형태로 줄인 것. songNames 는 세트 순서대로 편 곡명 (커버·테이프 포함).
 */
public record FmSetlist(List<String> songNames) {
}
