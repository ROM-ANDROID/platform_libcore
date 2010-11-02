/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package java.util.zip;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteOrder;
import java.nio.charset.ModifiedUtf8;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import libcore.base.Streams;
import org.apache.harmony.luni.platform.OSMemory;

/**
 * This class provides an implementation of {@code FilterInputStream} that
 * decompresses data from a <i>ZIP-archive</i> input stream.
 * <p>
 * A <i>ZIP-archive</i> is a collection of compressed (or uncompressed) files -
 * the so called ZIP entries. Therefore when reading from a {@code
 * ZipInputStream} first the entry's attributes will be retrieved with {@code
 * getNextEntry} before its data is read.
 * <p>
 * While {@code InflaterInputStream} can read a compressed <i>ZIP-archive</i>
 * entry, this extension can read uncompressed entries as well.
 * <p>
 * Use {@code ZipFile} if you can access the archive as a file directly.
 *
 * @see ZipEntry
 * @see ZipFile
 */
public class ZipInputStream extends InflaterInputStream implements ZipConstants {
    static final int DEFLATED = 8;

    static final int STORED = 0;

    static final int ZIPLocalHeaderVersionNeeded = 20;

    private boolean entriesEnd = false;

    private boolean hasDD = false;

    private int entryIn = 0;

    private int inRead, lastRead = 0;

    ZipEntry currentEntry;

    private final byte[] hdrBuf = new byte[LOCHDR - LOCVER];

    private final CRC32 crc = new CRC32();

    private byte[] nameBuf = new byte[256];

    private char[] charBuf = new char[256];

    /**
     * Constructs a new {@code ZipInputStream} from the specified input stream.
     *
     * @param stream
     *            the input stream to representing a ZIP archive.
     */
    public ZipInputStream(InputStream stream) {
        super(new PushbackInputStream(stream, BUF_SIZE), new Inflater(true));
        if (stream == null) {
            throw new NullPointerException();
        }
    }

    /**
     * Closes this {@code ZipInputStream}.
     *
     * @throws IOException
     *             if an {@code IOException} occurs.
     */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closeEntry(); // Close the current entry
            super.close();
        }
    }

    /**
     * Closes the current ZIP entry and positions to read the next entry.
     *
     * @throws IOException
     *             if an {@code IOException} occurs.
     */
    public void closeEntry() throws IOException {
        checkClosed();
        if (currentEntry == null) {
            return;
        }
        if (currentEntry instanceof java.util.jar.JarEntry) {
            Attributes temp = ((JarEntry) currentEntry).getAttributes();
            if (temp != null && temp.containsKey("hidden")) {
                return;
            }
        }

        /*
         * The following code is careful to leave the ZipInputStream in a
         * consistent state, even when close() results in an exception. It does
         * so by:
         *  - pushing bytes back into the source stream
         *  - reading a data descriptor footer from the source stream
         *  - resetting fields that manage the entry being closed
         */

        // Ensure all entry bytes are read
        Exception failure = null;
        try {
            Streams.skipAll(this);
        } catch (Exception e) {
            failure = e;
        }

        int inB, out;
        if (currentEntry.compressionMethod == DEFLATED) {
            inB = inf.getTotalIn();
            out = inf.getTotalOut();
        } else {
            inB = inRead;
            out = inRead;
        }
        int diff = entryIn - inB;
        // Pushback any required bytes
        if (diff != 0) {
            ((PushbackInputStream) in).unread(buf, len - diff, diff);
        }

        try {
            readAndVerifyDataDescriptor(inB, out);
        } catch (Exception e) {
            if (failure == null) { // otherwise we're already going to throw
                failure = e;
            }
        }

        inf.reset();
        lastRead = inRead = entryIn = len = 0;
        crc.reset();
        currentEntry = null;

        if (failure != null) {
            if (failure instanceof IOException) {
                throw (IOException) failure;
            } else if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            AssertionError error = new AssertionError();
            error.initCause(failure);
            throw error;
        }
    }

    private void readAndVerifyDataDescriptor(int inB, int out) throws IOException {
        if (hasDD) {
            in.read(hdrBuf, 0, EXTHDR);
            long sig = OSMemory.peekInt(hdrBuf, 0, ByteOrder.LITTLE_ENDIAN);
            if (sig != EXTSIG) {
                throw new ZipException(String.format("unknown format (EXTSIG=%x)", sig));
            }
            currentEntry.crc = OSMemory.peekInt(hdrBuf, EXTCRC, ByteOrder.LITTLE_ENDIAN);
            currentEntry.compressedSize = OSMemory.peekInt(hdrBuf, EXTSIZ, ByteOrder.LITTLE_ENDIAN);
            currentEntry.size = OSMemory.peekInt(hdrBuf, EXTLEN, ByteOrder.LITTLE_ENDIAN);
        }
        if (currentEntry.crc != crc.getValue()) {
            throw new ZipException("CRC mismatch");
        }
        if (currentEntry.compressedSize != inB || currentEntry.size != out) {
            throw new ZipException("Size mismatch");
        }
    }

    /**
     * Reads the next entry from this {@code ZipInputStream} or {@code null} if
     * no more entries are present.
     *
     * @return the next {@code ZipEntry} contained in the input stream.
     * @throws IOException
     *             if an {@code IOException} occurs.
     * @see ZipEntry
     */
    public ZipEntry getNextEntry() throws IOException {
        closeEntry();
        if (entriesEnd) {
            return null;
        }

        int x = 0, count = 0;
        while (count != 4) {
            count += x = in.read(hdrBuf, count, 4 - count);
            if (x == -1) {
                return null;
            }
        }
        long hdr = OSMemory.peekInt(hdrBuf, 0, ByteOrder.LITTLE_ENDIAN);
        if (hdr == CENSIG) {
            entriesEnd = true;
            return null;
        }
        if (hdr != LOCSIG) {
            return null;
        }

        // Read the local header
        count = 0;
        while (count != (LOCHDR - LOCVER)) {
            count += x = in.read(hdrBuf, count, (LOCHDR - LOCVER) - count);
            if (x == -1) {
                throw new EOFException();
            }
        }
        int version = OSMemory.peekShort(hdrBuf, 0, ByteOrder.LITTLE_ENDIAN) & 0xff;
        if (version > ZIPLocalHeaderVersionNeeded) {
            throw new ZipException("Cannot read local header version " + version);
        }
        int flags = OSMemory.peekShort(hdrBuf, LOCFLG - LOCVER, ByteOrder.LITTLE_ENDIAN);
        hasDD = ((flags & ZipFile.GPBF_DATA_DESCRIPTOR_FLAG) != 0);
        int cetime = OSMemory.peekShort(hdrBuf, LOCTIM - LOCVER, ByteOrder.LITTLE_ENDIAN);
        int cemodDate = OSMemory.peekShort(hdrBuf, LOCTIM - LOCVER + 2, ByteOrder.LITTLE_ENDIAN);
        int cecompressionMethod = OSMemory.peekShort(hdrBuf, LOCHOW - LOCVER, ByteOrder.LITTLE_ENDIAN);
        long cecrc = 0, cecompressedSize = 0, cesize = -1;
        if (!hasDD) {
            cecrc = OSMemory.peekInt(hdrBuf, LOCCRC - LOCVER, ByteOrder.LITTLE_ENDIAN);
            cecompressedSize = OSMemory.peekInt(hdrBuf, LOCSIZ - LOCVER, ByteOrder.LITTLE_ENDIAN);
            cesize = OSMemory.peekInt(hdrBuf, LOCLEN - LOCVER, ByteOrder.LITTLE_ENDIAN);
        }
        int flen = OSMemory.peekShort(hdrBuf, LOCNAM - LOCVER, ByteOrder.LITTLE_ENDIAN);
        if (flen == 0) {
            throw new ZipException("Entry is not named");
        }
        int elen = OSMemory.peekShort(hdrBuf, LOCEXT - LOCVER, ByteOrder.LITTLE_ENDIAN);

        count = 0;
        if (flen > nameBuf.length) {
            nameBuf = new byte[flen];
            charBuf = new char[flen];
        }
        while (count != flen) {
            count += x = in.read(nameBuf, count, flen - count);
            if (x == -1) {
                throw new EOFException();
            }
        }
        currentEntry = createZipEntry(ModifiedUtf8.decode(nameBuf, charBuf, 0, flen));
        currentEntry.time = cetime;
        currentEntry.modDate = cemodDate;
        currentEntry.setMethod(cecompressionMethod);
        if (cesize != -1) {
            currentEntry.setCrc(cecrc);
            currentEntry.setSize(cesize);
            currentEntry.setCompressedSize(cecompressedSize);
        }
        if (elen > 0) {
            count = 0;
            byte[] e = new byte[elen];
            while (count != elen) {
                count += x = in.read(e, count, elen - count);
                if (x == -1) {
                    throw new EOFException();
                }
            }
            currentEntry.setExtra(e);
        }
        return currentEntry;
    }

    /* Read 4 bytes from the buffer and store it as an int */

    /**
     * Reads up to the specified number of uncompressed bytes into the buffer
     * starting at the offset.
     *
     * @param buffer
     *            a byte array
     * @param start
     *            the starting offset into the buffer
     * @param length
     *            the number of bytes to read
     * @return the number of bytes read
     */
    @Override
    public int read(byte[] buffer, int start, int length) throws IOException {
        checkClosed();
        // avoid int overflow, check null buffer
        if (start > buffer.length || length < 0 || start < 0
                || buffer.length - start < length) {
            throw new ArrayIndexOutOfBoundsException();
        }

        if (inf.finished() || currentEntry == null) {
            return -1;
        }

        if (currentEntry.compressionMethod == STORED) {
            int csize = (int) currentEntry.size;
            if (inRead >= csize) {
                return -1;
            }
            if (lastRead >= len) {
                lastRead = 0;
                if ((len = in.read(buf)) == -1) {
                    eof = true;
                    return -1;
                }
                entryIn += len;
            }
            int toRead = length > (len - lastRead) ? len - lastRead : length;
            if ((csize - inRead) < toRead) {
                toRead = csize - inRead;
            }
            System.arraycopy(buf, lastRead, buffer, start, toRead);
            lastRead += toRead;
            inRead += toRead;
            crc.update(buffer, start, toRead);
            return toRead;
        }
        if (inf.needsInput()) {
            fill();
            if (len > 0) {
                entryIn += len;
            }
        }
        int read;
        try {
            read = inf.inflate(buffer, start, length);
        } catch (DataFormatException e) {
            throw new ZipException(e.getMessage());
        }
        if (read == 0 && inf.finished()) {
            return -1;
        }
        crc.update(buffer, start, read);
        return read;
    }

    @Override
    public int available() throws IOException {
        checkClosed();
        // The InflaterInputStream contract says we must only return 0 or 1.
        return (currentEntry == null || inRead < currentEntry.size) ? 1 : 0;
    }

    /**
     * creates a {@link ZipEntry } with the given name.
     *
     * @param name
     *            the name of the entry.
     * @return the created {@code ZipEntry}.
     */
    protected ZipEntry createZipEntry(String name) {
        return new ZipEntry(name);
    }

    private void checkClosed() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
    }
}
