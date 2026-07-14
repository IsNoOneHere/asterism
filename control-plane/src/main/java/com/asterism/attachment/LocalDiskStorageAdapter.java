package com.asterism.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class LocalDiskStorageAdapter implements StoragePort {
    private final Path root;

    public LocalDiskStorageAdapter(@Value("${asterism.storage.root:runtime/attachments}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public String save(String sha256, byte[] content) {
        var relative = Path.of(sha256.substring(0, 2), sha256);
        var target = root.resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            if (Files.notExists(target)) Files.write(target, content);
            return relative.toString();
        } catch (IOException error) {
            throw new IllegalStateException("附件写入失败", error);
        }
    }

    @Override
    public byte[] read(String storagePath) {
        var target = root.resolve(storagePath).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("附件存储路径不合法");
        try {
            return Files.readAllBytes(target);
        } catch (IOException error) {
            throw new IllegalStateException("附件读取失败", error);
        }
    }
}
