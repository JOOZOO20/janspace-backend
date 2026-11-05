package com.stagelog.account.domain;

import com.stagelog.common.entity.BaseEntity;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 사람이 아닌 호출자(MCP 진단 서버)의 계정. API key 원문은 저장하지 않고 SHA-256 hex 로만 찾는다.
 * 발급과 회수는 운영자가 SQL 로 한다 (db/schema/0160_agent_access.sql 주석).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "service_accounts")
public class ServiceAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String name;

    @Column(name = "key_hash", nullable = false, length = 64, unique = true, columnDefinition = "CHAR(64)")
    private String keyHash;

    @Convert(converter = RolesConverter.class)
    @Column(nullable = false, columnDefinition = "json")
    private Set<ServiceAccountRole> roles = EnumSet.noneOf(ServiceAccountRole.class);

    @Column(name = "disabled_at", columnDefinition = "DATETIME(6)")
    private Instant disabledAt;

    public ServiceAccount(String name, String keyHash, Set<ServiceAccountRole> roles) {
        this.name = name;
        this.keyHash = keyHash;
        this.roles = roles.isEmpty() ? EnumSet.noneOf(ServiceAccountRole.class) : EnumSet.copyOf(roles);
    }

    public Set<ServiceAccountRole> getRoles() {
        return Set.copyOf(roles);
    }

    public boolean isActive() {
        return disabledAt == null;
    }

    // roles JSON 배열 <-> enum 집합. 이름을 정렬해 저장한다
    static class RolesConverter implements AttributeConverter<Set<ServiceAccountRole>, String> {

        private static final JsonMapper MAPPER = JsonMapper.builder().build();

        @Override
        public String convertToDatabaseColumn(Set<ServiceAccountRole> attribute) {
            return MAPPER.writeValueAsString(attribute == null ? Set.of() : attribute.stream().map(Enum::name).sorted().toList());
        }

        @Override
        public Set<ServiceAccountRole> convertToEntityAttribute(String dbData) {
            EnumSet<ServiceAccountRole> set = EnumSet.noneOf(ServiceAccountRole.class);
            if (dbData != null && !dbData.isBlank()) {
                for (String role : MAPPER.readValue(dbData, String[].class)) {
                    set.add(ServiceAccountRole.valueOf(role));
                }
            }
            return set;
        }
    }
}
