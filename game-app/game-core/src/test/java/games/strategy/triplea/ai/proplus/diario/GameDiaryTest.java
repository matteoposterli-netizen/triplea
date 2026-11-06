package games.strategy.triplea.ai.proplus.diario;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameDiaryTest {
  @TempDir Path root;

  @Test
  void pruneKeepsTheNewestGamesAndNothingElse() throws IOException {
    for (int day = 1; day <= 22; day++) {
      final Path game = root.resolve(String.format("2026-11-%02d_10-00-00_Mappa", day));
      Files.createDirectories(game);
      Files.writeString(game.resolve(GameDiary.TEXT_FILE), "x");
    }
    Files.createDirectories(root.resolve("altro"));
    Files.writeString(root.resolve("note.txt"), "mie note");

    GameDiary.prune(root, 20);

    try (Stream<Path> children = Files.list(root)) {
      assertThat(children.map(p -> p.getFileName().toString()))
          .hasSize(22)
          .contains("altro", "note.txt", "2026-11-03_10-00-00_Mappa", "2026-11-22_10-00-00_Mappa")
          .doesNotContain("2026-11-01_10-00-00_Mappa", "2026-11-02_10-00-00_Mappa");
    }
  }

  @Test
  void folderNameIsSafeOnWindows() {
    assertThat(GameDiary.safeName("World War II Europe 1940 2nd Edition"))
        .isEqualTo("World_War_II_Europe_1940_2nd_Edition");
    assertThat(GameDiary.safeName("a:b\\c/d*?\"<>|")).isEqualTo("a_b_c_d_");
  }
}
