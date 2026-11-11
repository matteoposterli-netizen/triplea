package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nullable;

/**
 * Step R3a: the rows of the register of the commitment's decisions ({@code
 * impegno-decisioni.jsonl} in the diary), complete, for {@code ConfrontoR3a}.
 */
final class RegistroLettura {
  private RegistroLettura() {}

  static final String FILE = "impegno-decisioni.jsonl";

  static JsonArray leggi(@Nullable final Path diary) {
    final JsonArray out = new JsonArray();
    if (diary == null || !Files.isRegularFile(diary.resolve(FILE))) {
      return out;
    }
    try {
      for (final String line : Files.readAllLines(diary.resolve(FILE), StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        try {
          out.add(JsonParser.parseString(line));
        } catch (final RuntimeException e) {
          // a broken line (game stopped while writing) is skipped
        }
      }
    } catch (final IOException e) {
      return out;
    }
    return out;
  }
}
