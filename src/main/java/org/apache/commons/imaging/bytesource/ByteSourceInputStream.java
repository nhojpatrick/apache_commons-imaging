/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.imaging.bytesource;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.common.Allocator;
import org.apache.commons.imaging.common.BinaryFunctions;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.build.AbstractOrigin.InputStreamOrigin;

class ByteSourceInputStream extends ByteSource {

    private class CacheBlock {

        public final byte[] bytes;
        private CacheBlock next;
        private boolean triedNext;

        CacheBlock(final byte[] bytes) {
            this.bytes = bytes;
        }

        public CacheBlock getNext() throws IOException {
            if (null != next) {
                return next;
            }
            if (triedNext) {
                return null;
            }
            triedNext = true;
            next = readBlock();
            return next;
        }

    }

    private class CachingInputStream extends InputStream {
        private CacheBlock block;
        private boolean readFirst;
        private int blockIndex;

        @Override
        public int read() throws IOException {
            if (null == block) {
                if (readFirst) {
                    return -1;
                }
                block = getFirstBlock();
                readFirst = true;
            }

            if (block != null && blockIndex >= block.bytes.length) {
                block = block.getNext();
                blockIndex = 0;
            }

            if (null == block) {
                return -1;
            }

            if (blockIndex >= block.bytes.length) {
                return -1;
            }

            return 0xff & block.bytes[blockIndex++];
        }

        @Override
        public int read(final byte[] array, final int off, final int len) throws IOException {
            // first section copied verbatim from InputStream
            Objects.requireNonNull(array, "array");
            if ((off < 0) || (off > array.length) || (len < 0)
                    || ((off + len) > array.length) || ((off + len) < 0)) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }

            // optimized block read

            if (null == block) {
                if (readFirst) {
                    return -1;
                }
                block = getFirstBlock();
                readFirst = true;
            }

            if (block != null && blockIndex >= block.bytes.length) {
                block = block.getNext();
                blockIndex = 0;
            }

            if (null == block) {
                return -1;
            }

            if (blockIndex >= block.bytes.length) {
                return -1;
            }

            final int readSize = Math.min(len, block.bytes.length - blockIndex);
            System.arraycopy(block.bytes, blockIndex, array, off, readSize);
            blockIndex += readSize;
            return readSize;
        }

        @Override
        public long skip(final long n) throws IOException {

            long remaining = n;

            if (n <= 0) {
                return 0;
            }

            while (remaining > 0) {
                // read the first block
                if (null == block) {
                    if (readFirst) {
                        return -1;
                    }
                    block = getFirstBlock();
                    readFirst = true;
                }

                // get next block
                if (block != null && blockIndex >= block.bytes.length) {
                    block = block.getNext();
                    blockIndex = 0;
                }

                if (null == block) {
                    break;
                }

                if (blockIndex >= block.bytes.length) {
                    break;
                }

                final int readSize = Math.min((int) Math.min(BLOCK_SIZE, remaining), block.bytes.length - blockIndex);

                blockIndex += readSize;
                remaining -= readSize;
            }

            return n - remaining;
        }

    }

    private static final int BLOCK_SIZE = 1024;
    private final InputStream is;
    private CacheBlock cacheHead;
    private byte[] readBuffer;
    private long streamLength = -1;

    ByteSourceInputStream(final InputStream is, final String fileName) {
        super(new InputStreamOrigin(is), fileName);
        this.is = new BufferedInputStream(is);
    }

    @Override
    public byte[] getByteArray(final long from, final int length) throws IOException {
        // We include a separate check for int overflow.
        if ((from < 0) || (length < 0)
                || (from + length < 0)
                || (from + length > size())) {
            throw new ImagingException("Could not read block (block start: "
                    + from + ", block length: " + length
                    + ", data length: " + streamLength + ").");
        }

        final InputStream cis = getInputStream();
        BinaryFunctions.skipBytes(cis, from);

        final byte[] bytes = Allocator.byteArray(length);
        int total = 0;
        while (true) {
            final int read = cis.read(bytes, total, bytes.length - total);
            if (read < 1) {
                throw new ImagingException("Could not read block.");
            }
            total += read;
            if (total >= length) {
                return bytes;
            }
        }
    }

    private CacheBlock getFirstBlock() throws IOException {
        if (null == cacheHead) {
            cacheHead = readBlock();
        }
        return cacheHead;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new CachingInputStream();
    }

    private CacheBlock readBlock() throws IOException {
        if (null == readBuffer) {
            readBuffer = new byte[BLOCK_SIZE];
        }

        final int read = is.read(readBuffer);
        if (read < 1) {
            return null;
        }
        if (read < BLOCK_SIZE) {
            // return a copy.
            return new CacheBlock(Arrays.copyOf(readBuffer, read));
        }
        // return current buffer.
        final byte[] result = readBuffer;
        readBuffer = null;
        return new CacheBlock(result);
    }

    @Override
    public long size() throws IOException {
        if (streamLength >= 0) {
            return streamLength;
        }

        try (InputStream cis = getInputStream()) {
            final long result = IOUtils.consume(cis);
            streamLength = result;
            return result;
        }
    }

}
