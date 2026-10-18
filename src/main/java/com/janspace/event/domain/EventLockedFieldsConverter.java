package com.janspace.event.domain;

import jakarta.persistence.Converter;

@Converter
public class EventLockedFieldsConverter extends EnumSetJsonConverter<EventLockableField> {

    public EventLockedFieldsConverter() {
        super(EventLockableField.class);
    }
}
