/*
 * Copyright (C) 2014-2017 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package org.whispersystems.signalservice.api.crypto;

import org.signal.libsignal.protocol.InvalidMacException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.incrementalmac.ChunkSizeChoice;
import org.signal.libsignal.protocol.incrementalmac.IncrementalMacInputStream;
import org.signal.libsignal.protocol.kdf.HKDFv3;
import org.signal.libsignal.protocol.logging.Log;
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream;
import org.whispersystems.signalservice.internal.util.ContentLengthInputStream;
import org.whispersystems.signalservice.internal.util.Util;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Class for streaming an encrypted push attachment off disk.
 *
 * @author Moxie Marlinspike
 */

public class AttachmentCipherInputStream extends FilterInputStream {

  private static final int BLOCK_SIZE      = 16;
  private static final int CIPHER_KEY_SIZE = 32;
  private static final int MAC_KEY_SIZE    = 32;

  private Cipher  cipher;
  private boolean done;
  private long    totalDataSize;
  private long    totalRead;
  private byte[]  overflowBuffer;

  public static InputStream createForAttachment(File file, long plaintextLength, byte[] combinedKeyMaterial, byte[] digest, byte[] incrementalDigest)
      throws InvalidMessageException, IOException
  {
    try {
      byte[][] parts = Util.split(combinedKeyMaterial, CIPHER_KEY_SIZE, MAC_KEY_SIZE);
      Mac      mac   = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(parts[1], "HmacSHA256"));

      if (file.length() <= BLOCK_SIZE + mac.getMacLength()) {
        throw new InvalidMessageException("Message shorter than crypto overhead!");
      }

      if (digest == null) {
        throw new InvalidMacException("Missing digest!");
      }


      final InputStream wrappedStream;
      boolean hasIncrementalMac = incrementalDigest != null && incrementalDigest.length > 0;
      if (!hasIncrementalMac) {
        try (FileInputStream macVerificationStream = new FileInputStream(file)) {
          verifyMac(macVerificationStream, file.length(), mac, digest);
        }
        wrappedStream = new FileInputStream(file);
      } else {
        final int             dataSize   = Math.toIntExact(AttachmentCipherStreamUtil.getCiphertextLength(PaddingInputStream.getPaddedSize(plaintextLength)));
        final ChunkSizeChoice sizeChoice = ChunkSizeChoice.inferChunkSize(dataSize);
        wrappedStream = new IncrementalMacInputStream(
            new FileInputStream(file),
            parts[1],
            sizeChoice,
            incrementalDigest);
      }
      InputStream inputStream = new AttachmentCipherInputStream(wrappedStream, parts[0], file.length() - BLOCK_SIZE - mac.getMacLength());

      if (plaintextLength != 0) {
        inputStream = new ContentLengthInputStream(inputStream, plaintextLength);
      }

      return inputStream;
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new AssertionError(e);
    } catch (InvalidMacException e) {
      throw new InvalidMessageException(e);
    }
  }

  public static InputStream createForStickerData(byte[] data, byte[] packKey)
      throws InvalidMessageException, IOException
  {
    try {
      byte[]   combinedKeyMaterial = new HKDFv3().deriveSecrets(packKey, "Sticker Pack".getBytes(), 64);
      byte[][] parts               = Util.split(combinedKeyMaterial, CIPHER_KEY_SIZE, MAC_KEY_SIZE);
      Mac      mac                 = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(parts[1], "HmacSHA256"));

      if (data.length <= BLOCK_SIZE + mac.getMacLength()) {
        throw new InvalidMessageException("Message shorter than crypto overhead!");
      }

      try (InputStream inputStream = new ByteArrayInputStream(data)) {
        verifyMac(inputStream, data.length, mac, null);
      }

      return new AttachmentCipherInputStream(new ByteArrayInputStream(data), parts[0], data.length - BLOCK_SIZE - mac.getMacLength());
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new AssertionError(e);
    } catch (InvalidMacException e) {
      throw new InvalidMessageException(e);
    }
  }

  private AttachmentCipherInputStream(InputStream inputStream, byte[] cipherKey, long totalDataSize)
      throws IOException
  {
    super(inputStream);

    try {
      byte[] iv = new byte[BLOCK_SIZE];
      readFully(iv);

      this.cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      this.cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cipherKey, "AES"), new IvParameterSpec(iv));

      this.done          = false;
      this.totalRead     = 0;
      this.totalDataSize = totalDataSize;
    } catch (NoSuchAlgorithmException | InvalidKeyException | NoSuchPaddingException | InvalidAlgorithmParameterException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public int read() throws IOException {
    byte[] buffer = new byte[1];
    int    read;

    //noinspection StatementWithEmptyBody
    while ((read = read(buffer)) == 0);

    return (read == -1) ? -1 : ((int) buffer[0]) & 0xFF;
  }

  @Override
  public int read(byte[] buffer) throws IOException {
    return read(buffer, 0, buffer.length);
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if      (totalRead != totalDataSize) return readIncremental(buffer, offset, length);
    else if (!done)                      return readFinal(buffer, offset, length);
    else                                 return -1;
  }

  @Override
  public boolean markSupported() {
    return false;
  }

  @Override
  public long skip(long byteCount) throws IOException {
    long skipped = 0L;
    while (skipped < byteCount) {
      byte[] buf  = new byte[Math.min(4096, (int)(byteCount-skipped))];
      int    read = read(buf);

      skipped += read;
    }

    return skipped;
  }

  private int readFinal(byte[] buffer, int offset, int length) throws IOException {
    try {
      byte[] internal = new byte[buffer.length];
      int actualLength = Math.min(length, cipher.doFinal(internal, 0));
      System.arraycopy(internal, 0, buffer, offset, actualLength);

      done = true;
      return actualLength;
    } catch (IllegalBlockSizeException | BadPaddingException | ShortBufferException e) {
      throw new IOException(e);
    }
  }

  private int readIncremental(byte[] buffer, int offset, int length) throws IOException {
    int readLength = 0;
    if (null != overflowBuffer) {
      if (overflowBuffer.length > length) {
        System.arraycopy(overflowBuffer, 0, buffer, offset, length);
        overflowBuffer = Arrays.copyOfRange(overflowBuffer, length, overflowBuffer.length);
        return length;
      } else if (overflowBuffer.length == length) {
        System.arraycopy(overflowBuffer, 0, buffer, offset, length);
        overflowBuffer = null;
        return length;
      } else {
        System.arraycopy(overflowBuffer, 0, buffer, offset, overflowBuffer.length);
        readLength += overflowBuffer.length;
        offset += readLength;
        length -= readLength;
        overflowBuffer = null;
      }
    }

    if (length + totalRead > totalDataSize)
      length = (int)(totalDataSize - totalRead);

    byte[] internalBuffer = new byte[length];
    int read              = super.read(internalBuffer, 0, internalBuffer.length <= cipher.getBlockSize() ? internalBuffer.length : internalBuffer.length - cipher.getBlockSize());
    totalRead            += read;

    try {
      int outputLen = cipher.getOutputSize(read);

      if (outputLen <= length) {
        readLength += cipher.update(internalBuffer, 0, read, buffer, offset);
        return readLength;
      }

      byte[] transientBuffer = new byte[outputLen];
      outputLen = cipher.update(internalBuffer, 0, read, transientBuffer, 0);
      if (outputLen <= length) {
        System.arraycopy(transientBuffer, 0, buffer, offset, outputLen);
        readLength += outputLen;
      } else {
        System.arraycopy(transientBuffer, 0, buffer, offset, length);
        overflowBuffer = Arrays.copyOfRange(transientBuffer, length, outputLen);
        readLength += length;
      }
      return readLength;
    } catch (ShortBufferException e) {
      throw new AssertionError(e);
    }
  }

  private static void verifyMac(InputStream inputStream, long length, Mac mac, byte[] theirDigest)
      throws InvalidMacException
  {
    try {
      MessageDigest   digest        = MessageDigest.getInstance("SHA256");
      int             remainingData = Util.toIntExact(length) - mac.getMacLength();
      byte[]          buffer        = new byte[4096];

      while (remainingData > 0) {
        int read = inputStream.read(buffer, 0, Math.min(buffer.length, remainingData));
        mac.update(buffer, 0, read);
        digest.update(buffer, 0, read);
        remainingData -= read;
      }

      byte[] ourMac   = mac.doFinal();
      byte[] theirMac = new byte[mac.getMacLength()];
      Util.readFully(inputStream, theirMac);

      if (!MessageDigest.isEqual(ourMac, theirMac)) {
        throw new InvalidMacException("MAC doesn't match!");
      }

      byte[] ourDigest = digest.digest(theirMac);

      if (theirDigest != null && !MessageDigest.isEqual(ourDigest, theirDigest)) {
        throw new InvalidMacException("Digest doesn't match!");
      }

    } catch (IOException | ArithmeticException e1) {
      throw new InvalidMacException(e1);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private void readFully(byte[] buffer) throws IOException {
    int offset = 0;

    for (;;) {
      int read = super.read(buffer, offset, buffer.length - offset);

      if (read + offset < buffer.length) offset += read;
      else                		           return;
    }
  }
}
