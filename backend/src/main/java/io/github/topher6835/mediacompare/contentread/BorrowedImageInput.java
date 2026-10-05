package io.github.topher6835.mediacompare.contentread;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import javax.imageio.stream.ImageInputStreamImpl;

/** No spool, pathname or channel ownership. Closing this wrapper never closes the supplied channel. */
public final class BorrowedImageInput extends ImageInputStreamImpl {
    private final SeekableByteChannel channel;
    public BorrowedImageInput(SeekableByteChannel channel) throws IOException {
        this.channel = channel;
        streamPos = channel.position();
    }
    @Override public int read() throws IOException {
        byte[] value = new byte[1];
        return read(value, 0, 1) < 0 ? -1 : value[0] & 255;
    }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        checkClosed();
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        bitOffset = 0;
        if (length == 0) return 0;
        int count;
        do { count = channel.read(ByteBuffer.wrap(bytes, offset, length)); } while (count == 0);
        if (count > 0) streamPos += count;
        return count;
    }
    @Override public void seek(long position) throws IOException {
        super.seek(position);
        channel.position(position);
    }
    @Override public long length() {
        try { return channel.size(); } catch (IOException failure) { return -1; }
    }
}
