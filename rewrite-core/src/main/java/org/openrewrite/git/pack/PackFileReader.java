package org.openrewrite.git.pack;

import org.apache.commons.io.Charsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * This is a utility used in understanding the format of a Git pack file.
 *
 * To verify a pack file is valid first, use `git verify-pack`.
 */
@SuppressWarnings("ResultOfMethodCallIgnored")
public class PackFileReader {
    private Logger logger = LoggerFactory.getLogger(PackFileReader.class);

    public void read(byte[] bytes) {
        PositionReadingByteArrayInputStream in = new PositionReadingByteArrayInputStream(bytes);

        try {
            assert "PACK".equals(new String(in.readNBytes(4)));
            assert ByteBuffer.wrap(in.readNBytes(4)).getInt() == 2;

            int entries = ByteBuffer.wrap(in.readNBytes(4)).getInt();
            logger.debug("{} entries", entries);

            for (int entry = 0; entry < entries; entry++) {
                logger.debug("Entry {} (address {}, 0x{})", (entry + 1), in.getPos(), Integer.toHexString(in.getPos()));

                int n = in.read();

                ElementType elementType = ElementType.fromType((n >> 4) & 0b0111);

                logger.debug("Type {}", elementType);
                n &= 0b10001111;

                int dataSize = 0;
                for (int i = 0; ; n = in.read(), i++) {
                    int shift = (4 * Math.min(i, 1)) + (7 * Math.max(i - 2, 0));
                    dataSize |= (n & 0b01111111) << shift;

                    if (n >> 7 == 0) {
                        break;
                    }
                }

                logger.debug("Data size {}", dataSize);

                switch(elementType) {
                    case OBJ_TAG:
                    case OBJ_COMMIT: {
                        byte[] commit = inflate(bytes, in, dataSize);
                        logger.debug("Data:\n{}", new String(commit, Charsets.UTF_8));
                        break;
                    }
                    case OBJ_REF_DELTA: {
                        byte[] baseObjectName = in.readNBytes(20);
                        logger.debug("Base object name " + hexString(baseObjectName));

                        byte[] inflatedData = inflate(bytes, in, dataSize);
                        logger.debug("Data:\n{}", hexString(inflatedData).replaceAll("(.)(.)", "$1....$2    "));
                        logger.debug("Data:\n{}", binaryString(inflatedData).replaceAll("(.{4})(.{4})", "$1.$2 "));

                        PositionReadingByteArrayInputStream deltaIn = new PositionReadingByteArrayInputStream(inflatedData);
                        logger.debug("Source length {}", readVariableLengthInteger(deltaIn, deltaIn.read()));
                        logger.debug("Target length {}", readVariableLengthInteger(deltaIn, deltaIn.read()));

                        while(deltaIn.available() > 0) {
                            int commandByte = deltaIn.read();
                            switch(DeltaCommandType.fromType(commandByte >> 7)) {
                                case COPY:
                                    logger.debug("COPY bytes");

                                    /*
                                     * +----------+---------+---------+---------+---------+-------+-------+-------+
                                     * | 1xxxxxxx | offset1 | offset2 | offset3 | offset4 | size1 | size2 | size3 |
                                     * +----------+---------+---------+---------+---------+-------+-------+-------+
                                     *
                                     * All offset and size bytes are optional. This is to reduce the instruction size
                                     * when encoding small offsets or sizes. The first seven bits in the first octet
                                     * determines which of the next seven octets is present. If bit zero is set,
                                     * offset1 is present. If bit one is set offset2 is present and so on.
                                     */

                                    int offset = 0;
                                    for(int i = 0; i < 4; i++, commandByte >>= 1) {
                                        if((commandByte & 1) == 1) {
                                            offset |= deltaIn.read() << (8 * i);
                                        }
                                    }
                                    logger.debug("From offset: " + offset);

                                    int size = 0;
                                    for(int i = 0; i < 3; i++, commandByte >>= 1) {
                                        if((commandByte & 1) == 1) {
                                            size |= deltaIn.read() << (8 * i);
                                        }
                                    }
                                    logger.debug("Size: " + size);
                                    break;
                                case INSERT:
                                    logger.debug("INSERT {} bytes", commandByte);
                                    logger.debug("Data:\n{}", hexString(deltaIn.readNBytes(commandByte)));
//                                    logger.debug("Data:\n{}", new String(deltaIn.readNBytes(commandByte), Charsets.UTF_8));
                            }
                        }

                        break;
                    }
                    default:
                        throw new UnsupportedOperationException("This reader doesn't yet support " + elementType + " elements");
                }
            }
        } catch (IOException | DataFormatException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] inflate(byte[] bytes, PositionReadingByteArrayInputStream in, int dataSize) throws DataFormatException {
        Inflater decompresser = new Inflater();
        decompresser.setInput(bytes, in.getPos(), bytes.length - in.getPos());
        byte[] inflatedData = new byte[dataSize];
        decompresser.inflate(inflatedData);
        long bytesRead = decompresser.getBytesRead();
        in.skip(bytesRead);
        decompresser.end();
        return inflatedData;
    }

    private int readVariableLengthInteger(PositionReadingByteArrayInputStream in, int n) {
        int result = 0;
        for (int i = 0; ; n = in.read(), i++) {
            int shift = 7 * i;
            result |= (n & 0b01111111) << shift;

            if (n >> 7 == 0) {
                break;
            }
        }
        return result;
    }

    private String hexString(byte[] bytes) {
        BigInteger bigInteger = new BigInteger(1, bytes);
        return String.format("%0" + (bytes.length << 1) + "x", bigInteger);
    }

    private String binaryString(byte[] bytes) {
        BigInteger bigInteger = new BigInteger(1, bytes);
        StringBuilder bin = new StringBuilder(bigInteger.toString(2));
        while(bin.length() % 4 != 0) {
            bin.insert(0, "0");
        }
        return bin.toString();
    }

    private static class PositionReadingByteArrayInputStream extends ByteArrayInputStream {
        public PositionReadingByteArrayInputStream(byte[] buf) {
            super(buf);
        }

        public int getPos() {
            return pos;
        }
    }
}
