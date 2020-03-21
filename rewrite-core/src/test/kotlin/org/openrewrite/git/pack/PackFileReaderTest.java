package org.openrewrite.git.pack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

public class PackFileReaderTest {
    @Test
    public void readPackFile() throws IOException {
        try (InputStream in = PackFileReader.class.getResourceAsStream("/push.pack")) {
            new PackFileReader().read(in.readAllBytes());
        }
    }
}
