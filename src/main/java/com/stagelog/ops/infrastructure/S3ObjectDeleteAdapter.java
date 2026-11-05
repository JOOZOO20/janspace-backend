package com.stagelog.ops.infrastructure;

import com.stagelog.integration.s3.S3ObjectStorage;
import com.stagelog.ops.application.port.ObjectDeletePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ObjectDeletePort 의 S3 구현.
 */
@Component
@RequiredArgsConstructor
public class S3ObjectDeleteAdapter implements ObjectDeletePort {

    private final S3ObjectStorage storage;

    @Override
    public void delete(String key) {
        storage.delete(key);
    }
}
