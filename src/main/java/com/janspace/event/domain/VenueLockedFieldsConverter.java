package com.janspace.event.domain;

import jakarta.persistence.Converter;

@Converter
public class VenueLockedFieldsConverter extends EnumSetJsonConverter<VenueLockableField> {

    public VenueLockedFieldsConverter() {
        super(VenueLockableField.class);
    }
}
