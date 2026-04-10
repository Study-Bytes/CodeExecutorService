package com.example.demo.core.docker;

import com.example.demo.api.dto.OutputBlob;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class StreamUtil {
    private StreamUtil() {
    }

    static OutputBlob readStreamLimited(InputStream is, int limitBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        boolean truncated = false;

        while (true) {
            int r = is.read(buf);
            if (r == -1) break;

            if (total < limitBytes) {
                int canWrite = Math.min(r, limitBytes - total);
                if (canWrite > 0) {
                    baos.write(buf, 0, canWrite);
                    total += canWrite;
                }
                if (canWrite < r) {
                    truncated = true;
                }
            } else {
                // лимит уже достигнут — просто дреним поток, чтобы процесс не завис на заполненном pipe
                truncated = true;
            }
        }

        String data = baos.toString(StandardCharsets.UTF_8);
        return new OutputBlob(data, truncated);
    }
}
