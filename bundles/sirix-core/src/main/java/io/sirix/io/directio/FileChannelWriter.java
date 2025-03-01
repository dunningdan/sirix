/*
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met: * Redistributions of source code must retain the
 * above copyright notice, this list of conditions and the following disclaimer. * Redistributions
 * in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.sirix.io.directio;

import com.github.benmanes.caffeine.cache.AsyncCache;
import io.sirix.access.ResourceConfiguration;
import io.sirix.api.PageReadOnlyTrx;
import io.sirix.exception.SirixIOException;
import io.sirix.io.*;
import io.sirix.page.*;
import io.sirix.page.interfaces.Page;
import net.openhft.chronicle.bytes.Bytes;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Objects.requireNonNull;

/**
 * File Writer for providing read/write access for file as a Sirix backend.
 *
 * @author Marc Kramis, Seabix
 * @author Sebastian Graf, University of Konstanz
 * @author Johannes Lichtenberger
 */
public final class FileChannelWriter extends AbstractForwardingReader implements Writer {

  /**
   * Random access to work on.
   */
  private final FileChannel dataFileChannel;

  /**
   * {@link FileChannelReader} reference for this writer.
   */
  private final FileChannelReader reader;

  private final SerializationType serializationType;

  private final FileChannel revisionsFileChannel;

  private final PagePersister pagePersister;

  private final AsyncCache<Integer, RevisionFileData> cache;

  private boolean isFirstUberPage;

  private final Bytes<ByteBuffer> byteBufferBytes = Bytes.elasticHeapByteBuffer(1_000);

  /**
   * Constructor.
   *
   * @param dataFileChannel            the data file channel
   * @param revisionsOffsetFileChannel the channel to the file, which holds pointers to the revision root pages
   * @param serializationType          the serialization type (for the transaction log or the data file)
   * @param pagePersister              transforms in-memory pages into byte-arrays and back
   * @param cache                      the revision file data cache
   * @param reader                     the reader delegate
   */
  public FileChannelWriter(final FileChannel dataFileChannel, final FileChannel revisionsOffsetFileChannel,
      final SerializationType serializationType, final PagePersister pagePersister,
      final AsyncCache<Integer, RevisionFileData> cache, final FileChannelReader reader) {
    this.dataFileChannel = dataFileChannel;
    this.serializationType = requireNonNull(serializationType);
    this.revisionsFileChannel = revisionsOffsetFileChannel;
    this.pagePersister = requireNonNull(pagePersister);
    this.cache = requireNonNull(cache);
    this.reader = requireNonNull(reader);
  }

  @Override
  public Writer truncateTo(final PageReadOnlyTrx pageReadOnlyTrx, final int revision) {
    try {
      final var dataFileRevisionRootPageOffset =
          cache.get(revision, (unused) -> getRevisionFileData(revision)).get(5, TimeUnit.SECONDS).offset();

      // Read page from file.
      final var buffer = ByteBuffer.allocateDirect(IOStorage.OTHER_BEACON).order(ByteOrder.nativeOrder());

      dataFileChannel.read(buffer, dataFileRevisionRootPageOffset);

      buffer.position(0);
      final int dataLength = buffer.getInt();

      dataFileChannel.truncate(dataFileRevisionRootPageOffset + IOStorage.OTHER_BEACON + dataLength);
    } catch (InterruptedException | ExecutionException | TimeoutException | IOException e) {
      throw new IllegalStateException(e);
    }

    return this;
  }

  @Override
  public FileChannelWriter write(final ResourceConfiguration resourceConfiguration, final PageReference pageReference,
      final Bytes<ByteBuffer> bufferedBytes) {
    try {
      final long offset = getOffset(bufferedBytes);
      return writePageReference(resourceConfiguration, pageReference, bufferedBytes, offset);
    } catch (final IOException e) {
      throw new SirixIOException(e);
    }
  }

  private long getOffset(Bytes<ByteBuffer> bufferedBytes) throws IOException {
    final long fileSize = dataFileChannel.size();
    long offset;

    if (fileSize == 0) {
      offset = (long) DirectIOUtils.BLOCK_SIZE * 3;
      offset += bufferedBytes.writePosition();
    } else {
      offset = fileSize + DirectIOUtils.nearestMultipleOfBlockSize(bufferedBytes.writePosition());
    }

    return offset;
  }

  @NonNull
  private FileChannelWriter writePageReference(final ResourceConfiguration resourceConfiguration,
      final PageReference pageReference, final Bytes<ByteBuffer> bufferedBytes, long offset) {
    // Perform byte operations.
    try {
      // Serialize page.
      final Page page = pageReference.getPage();
      assert page != null;

      pagePersister.serializePage(resourceConfiguration, byteBufferBytes, page, serializationType);
      final var byteArray = byteBufferBytes.toByteArray();

      final byte[] serializedPage;

      try (final ByteArrayOutputStream output = new ByteArrayOutputStream(byteArray.length)) {
        try (final DataOutputStream dataOutput = new DataOutputStream(reader.getByteHandler().serialize(output))) {
          dataOutput.write(byteArray);
          dataOutput.flush();
        }
        serializedPage = output.toByteArray();
      }

      byteBufferBytes.clear();

      final long nearestMultipleOfBlockSize = DirectIOUtils.nearestMultipleOfBlockSize(serializedPage.length);

      final long offsetToAdd = nearestMultipleOfBlockSize - serializedPage.length - Integer.BYTES;

      bufferedBytes.writeInt(serializedPage.length);
      bufferedBytes.write(serializedPage);

      if (offsetToAdd > 0) {
        final byte[] bytesToAdd = new byte[(int) offsetToAdd];
        bufferedBytes.write(bytesToAdd);
      }

      if (bufferedBytes.writePosition() > FLUSH_SIZE) {
        flushBuffer(bufferedBytes);
      }

      // Remember page coordinates.
      pageReference.setKey(offset);

      if (page instanceof KeyValueLeafPage keyValueLeafPage) {
        pageReference.setHash(keyValueLeafPage.getHashCode());
      } else {
        pageReference.setHash(reader.hashFunction.hashBytes(serializedPage).asBytes());
      }

      if (serializationType == SerializationType.DATA) {
        if (page instanceof RevisionRootPage revisionRootPage) {
          ByteBuffer buffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
          buffer.putLong(offset);
          buffer.position(8);
          buffer.putLong(revisionRootPage.getRevisionTimestamp());
          buffer.position(0);
          final long revisionsFileOffset;
          if (revisionRootPage.getRevision() == 0) {
            revisionsFileOffset = revisionsFileChannel.size() + IOStorage.FIRST_BEACON;
          } else {
            revisionsFileOffset = revisionsFileChannel.size();
          }
          revisionsFileChannel.write(buffer, revisionsFileOffset);
          final long currOffset = offset;
          cache.put(revisionRootPage.getRevision(),
                    CompletableFuture.supplyAsync(() -> new RevisionFileData(currOffset,
                                                                             Instant.ofEpochMilli(revisionRootPage.getRevisionTimestamp()))));
        } else if (page instanceof UberPage && isFirstUberPage) {
          ByteBuffer buffer = ByteBuffer.allocateDirect(Writer.UBER_PAGE_BYTE_ALIGN).order(ByteOrder.nativeOrder());
          buffer.put(serializedPage);
          buffer.position(0);
          revisionsFileChannel.write(buffer, 0);
          buffer.position(0);
          revisionsFileChannel.write(buffer, Writer.UBER_PAGE_BYTE_ALIGN);
        }
      }

      return this;
    } catch (final IOException e) {
      throw new SirixIOException(e);
    }
  }

  @Override
  public void close() {
    try {
      if (dataFileChannel != null) {
        dataFileChannel.force(true);
      }
      if (revisionsFileChannel != null) {
        revisionsFileChannel.force(true);
      }
      if (reader != null) {
        reader.close();
      }
    } catch (final IOException e) {
      throw new SirixIOException(e);
    }
  }

  @Override
  public Writer writeUberPageReference(final ResourceConfiguration resourceConfiguration,
      final PageReference pageReference, final Bytes<ByteBuffer> bufferedBytes) {
    try {
      if (bufferedBytes.writePosition() > 0) {
        flushBuffer(bufferedBytes);
      }

      isFirstUberPage = true;
      writePageReference(resourceConfiguration, pageReference, bufferedBytes, DirectIOUtils.BLOCK_SIZE);
      isFirstUberPage = false;
      writePageReference(resourceConfiguration, pageReference, bufferedBytes, DirectIOUtils.BLOCK_SIZE * 2L);

      @SuppressWarnings("DataFlowIssue") final var buffer = bufferedBytes.underlyingObject();
      buffer.limit((int) bufferedBytes.readLimit());
      dataFileChannel.write(buffer.alignedSlice(DirectIOUtils.BLOCK_SIZE).order(ByteOrder.nativeOrder()),
                            DirectIOUtils.BLOCK_SIZE);
      dataFileChannel.force(false);
      bufferedBytes.clear();
    } catch (final IOException e) {
      throw new SirixIOException(e);
    }

    return this;
  }

  private void flushBuffer(Bytes<ByteBuffer> bufferedBytes) throws IOException {
    final long fileSize = dataFileChannel.size();
    long offset;

    if (fileSize == 0) {
      offset = DirectIOUtils.BLOCK_SIZE * 3L;
    } else {
      offset = fileSize;
    }

    @SuppressWarnings("DataFlowIssue") final var buffer = bufferedBytes.underlyingObject();
    buffer.limit((int) bufferedBytes.readLimit());
    dataFileChannel.write(buffer.alignedSlice(DirectIOUtils.BLOCK_SIZE).order(ByteOrder.nativeOrder()), offset);
    dataFileChannel.force(false);
    bufferedBytes.clear();
  }

  @Override
  protected Reader delegate() {
    return reader;
  }

  @Override
  public Writer truncate() {
    try {
      dataFileChannel.truncate(0);

      if (revisionsFileChannel != null) {
        revisionsFileChannel.truncate(0);
      }
    } catch (final IOException e) {
      throw new SirixIOException(e);
    }

    return this;
  }
}
