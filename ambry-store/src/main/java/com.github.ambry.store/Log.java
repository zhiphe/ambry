package com.github.ambry.store;

import com.github.ambry.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;


/**
 * The underlying file is backed by a log abstraction. This allows writes as append only operations.
 * For pre-allocated files, this tracks the end of valid file to ensure appends happen correctly.
 * It provides ability to read from arbitrary offset into the file. It can also provide a static view
 * of the log for a given set of offset,size pairs.
 */
public class Log implements Write, Read {

  private AtomicLong currentWriteOffset;
  private final FileChannel fileChannel;
  private final File file;
  private final long capacityInBytes;
  private static final String Log_File_Name = "log_current";
  private Logger logger = LoggerFactory.getLogger(getClass());
  private final StoreMetrics metrics;

  public Log(String dataDir, long capacityInBytes, StoreMetrics metrics)
      throws IOException {
    file = new File(dataDir, Log_File_Name);
    if (!file.exists()) {
      // if the file does not exist, preallocate it
      Utils.preAllocateFileIfNeeded(file, capacityInBytes);
    }
    this.capacityInBytes = capacityInBytes;
    fileChannel = Utils.openChannel(file, true);
    // A log's write offset will always be set to the start of the log.
    // External components is responsible for setting it the right value
    currentWriteOffset = new AtomicLong(0);
    this.metrics = metrics;
  }

  MessageReadSet getView(List<BlobReadOptions> readOptions)
      throws IOException {
    return new StoreMessageReadSet(file, fileChannel, readOptions, currentWriteOffset.get());
  }

  public long sizeInBytes()
      throws IOException {
    return fileChannel.size();
  }

  public void setLogEndOffset(long endOffset)
      throws IOException {
    if (endOffset < 0 || endOffset > capacityInBytes) {
      logger.error("endOffset {} outside the file size {}", endOffset, capacityInBytes);
      throw new IllegalArgumentException("endOffset " + endOffset + " outside the file size " + capacityInBytes);
    }
    fileChannel.position(endOffset);
    logger.trace("Setting log end offset {}", endOffset);
    this.currentWriteOffset.set(endOffset);
  }

  public long getLogEndOffset() {
    return currentWriteOffset.get();
  }

  @Override
  public int appendFrom(ByteBuffer buffer)
      throws IOException {
    if (currentWriteOffset.get() + buffer.remaining() > capacityInBytes) {
      metrics.overflowWriteError.inc(1);
      logger.error("Error trying to append to log from buffer since new data size {} exceeds total log size {}",
          buffer.remaining(), capacityInBytes);
      throw new IllegalArgumentException("Error trying to append to log from buffer since new data size " +
          buffer.remaining() + " exceeds total log size " + capacityInBytes);
    }
    int bytesWritten = fileChannel.write(buffer, currentWriteOffset.get());
    currentWriteOffset.addAndGet(bytesWritten);
    logger.trace("Bytes appended to the log from bytebuffer for logfile {} byteswritten: {}", file.getPath(),
        bytesWritten);
    return bytesWritten;
  }

  @Override
  public long appendFrom(ReadableByteChannel channel, long size)
      throws IOException {
    if (currentWriteOffset.get() + size > capacityInBytes) {
      metrics.overflowWriteError.inc(1);
      logger.error("Error trying to append to log from channel since new data size {} exceeds total log size {}", size,
          capacityInBytes);
      throw new IllegalArgumentException("Error trying to append to log from channel since new data size " +
          size + "exceeds total log size " + capacityInBytes);
    }
    long bytesWritten = fileChannel.transferFrom(channel, currentWriteOffset.get(), size);
    currentWriteOffset.addAndGet(bytesWritten);
    logger.trace("Bytes appended to the log from read channel for logfile {} byteswritten: {}", file.getPath(),
        bytesWritten);
    return bytesWritten;
  }

  /**
   * Close this log
   */
  void close()
      throws IOException {
    fileChannel.close();
  }

  public void flush()
      throws IOException {
    fileChannel.force(true);
  }

  @Override
  public void readInto(ByteBuffer buffer, long position)
      throws IOException {
    if (sizeInBytes() < position || (position + buffer.remaining() > sizeInBytes())) {
      metrics.overflowReadError.inc(1);
      logger
          .error("Error trying to read outside the log range. log end position {} input buffer size {}", sizeInBytes(),
              buffer.remaining());
      throw new IllegalArgumentException("Error trying to read outside the log range. log end position " +
          sizeInBytes() + " input buffer size " + buffer.remaining());
    }
    fileChannel.read(buffer, position);
  }
}
