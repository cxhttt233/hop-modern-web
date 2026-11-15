/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.hop.modern.web.document;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Atomic, revision-checked replacement anchored by an open workspace directory.
 * A pre-save path check alone is insufficient: another process can replace any
 * parent with an external symlink before Files.createTempFile / Files.move.
 */
final class SecureWorkspaceSave {
  private SecureWorkspaceSave() {}

  static final class ExternalModificationException extends IOException {
    ExternalModificationException(String message) { super(message); }
    ExternalModificationException(String message, Throwable cause) { super(message, cause); }
  }

  static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** Replaces only the originally opened native document; no filesystem fallback. */
  static byte[] write(Path source, byte[] expectedSha256, byte[] next,
      Path workspaceRoot) throws IOException {
    if (source == null || expectedSha256 == null || expectedSha256.length != 32 || next == null) {
      throw new IllegalArgumentException("valid source, expected SHA-256 and bytes are required");
    }
    Path target = source.toAbsolutePath().normalize();
    Path root = workspaceRoot == null
        ? target.getParent().toRealPath(LinkOption.NOFOLLOW_LINKS)
        : workspaceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
    if (root == null || Files.isSymbolicLink(root)
        || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        || !target.startsWith(root) || target.equals(root)) {
      throw new ExternalModificationException("workspace path changed");
    }
    Path relative = root.relativize(target);
    List<SecureDirectoryStream<Path>> nested = new ArrayList<>();
    Path temporary = null;
    try (DirectoryStream<Path> rootDir = Files.newDirectoryStream(root)) {
      if (!(rootDir instanceof SecureDirectoryStream<?>)) {
        // A non-secure provider must fail closed: path-based replacement can escape.
        throw new IOException("SecureDirectoryStream required for safe document saving");
      }
      @SuppressWarnings("unchecked")
      SecureDirectoryStream<Path> current = (SecureDirectoryStream<Path>) rootDir;
      try {
        for (int i = 0; i < relative.getNameCount() - 1; ++i) {
          try {
            current = current.newDirectoryStream(relative.getName(i), LinkOption.NOFOLLOW_LINKS);
          } catch (IOException e) {
            throw new ExternalModificationException("opened document parent changed", e);
          }
          nested.add(current);
        }
        Path name = relative.getFileName();
        checkOriginal(current, name, expectedSha256);
        temporary = Path.of(".hop-secure-" + UUID.randomUUID() + ".tmp");
        try (SeekableByteChannel output = current.newByteChannel(temporary,
            Set.<OpenOption>of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS))) {
          ByteBuffer buffer = ByteBuffer.wrap(next);
          while (buffer.hasRemaining()) output.write(buffer);
        }
        checkOriginal(current, name, expectedSha256);
        // On Unix this uses descriptor-relative renameat, not a vulnerable path.
        current.move(temporary, current, name);
        temporary = null;
      } finally {
        if (temporary != null) {
          try { current.deleteFile(temporary); } catch (IOException ignored) { /* retain original error */ }
        }
        for (int i = nested.size() - 1; i >= 0; --i) nested.get(i).close();
      }
    }
    return next;
  }

  private static void checkOriginal(SecureDirectoryStream<Path> dir, Path name,
      byte[] expectedSha256) throws IOException {
    try (SeekableByteChannel input = dir.newByteChannel(name,
        Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      byte[] current = Channels.newInputStream(input).readAllBytes();
      if (!MessageDigest.isEqual(expectedSha256, digest(current))) {
        throw new ExternalModificationException("native document changed on disk");
      }
    } catch (ExternalModificationException e) {
      throw e;
    } catch (IOException e) {
      throw new ExternalModificationException("native document file was replaced or removed", e);
    }
  }
}
