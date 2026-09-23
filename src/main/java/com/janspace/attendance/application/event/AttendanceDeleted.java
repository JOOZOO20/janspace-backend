package com.janspace.attendance.application.event;

/**
 * 기록이 소프트 삭제됐다. photocard 가 연결된 포토카드의 attendance_id 를 끊는다.
 */
public record AttendanceDeleted(Long attendanceId, Long userId) {
}
