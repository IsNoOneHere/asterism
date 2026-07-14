package com.asterism.attachment;

public interface StoragePort {
    String save(String sha256, byte[] content);

    byte[] read(String storagePath);
}
