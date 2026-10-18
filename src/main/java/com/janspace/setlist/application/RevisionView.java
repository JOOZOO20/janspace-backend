package com.janspace.setlist.application;

import com.janspace.setlist.domain.RevisionSource;
import java.time.Instant;
import java.util.List;

/**
 * 리비전 읽기 모델. 편집자는 어떤 형태로도 담지 않는다.
 */
public record RevisionView(int version, Instant changedAt, RevisionSource source, List<SetlistEntry> entries) {
}
