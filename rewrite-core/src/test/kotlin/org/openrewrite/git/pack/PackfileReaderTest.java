package org.openrewrite.git.pack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

public class PackfileReaderTest {
    @Test
    public void readPackFile() throws IOException {
        try (InputStream in = PackfileReader.class.getResourceAsStream("/push.pack")) {
            new PackfileReader().read(in.readAllBytes());
        }
    }
}
