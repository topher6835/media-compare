package io.github.topher6835.mediacompare.preview;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import javax.imageio.stream.ImageInputStreamImpl;

/** Seek directly in a no-follow file channel; no ImageIO memory/disk spool of the source. */
final class PreviewImageInput extends ImageInputStreamImpl {
    private final SeekableByteChannel channel;
    private final byte[] singleByte = new byte[1];

    PreviewImageInput(Path file) throws IOException {
        channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }

    @Override
    public int read() throws IOException {
        return read(singleByte, 0, 1) == -1 ? -1 : singleByte[0] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        checkClosed();
        bitOffset = 0;
        int count = channel.read(ByteBuffer.wrap(bytes, offset, length));
        if (count > 0) {
            streamPos += count;
        }
        return count;
    }

    @Override
    public void seek(long position) throws IOException {
        super.seek(position);
        channel.position(position);
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            channel.close();
        }
    }
}
