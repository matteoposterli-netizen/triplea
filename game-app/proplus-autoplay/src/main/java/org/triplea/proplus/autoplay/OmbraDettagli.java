package org.triplea.proplus.autoplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Step R2a-bis: the rows of the shadow's checks from the diary files ({@code
 * campagna-ombra-R<N>-<alliance>.json}, {@code retrovie-ombra-R<N>-<alliance>.json}), complete (the
 * game state keeps only pruned lists). Compact arrays per alliance, for {@code ConfrontoOmbra}:
 *
 * <ul>
 *   <li>{@code difensori}: [horizon, type, at war 0/1, real TUV, M0, M1, M2];
 *   <li>{@code contrattacco}: [type, estimated hold, max enemy TUV in reach, attacked 0/1,
 *       committed TUV, held 0/1];
 *   <li>{@code battaglie_calcolatore}: [real loss, P60, P70, P80];
 *   <li>{@code fattibili}: [round, margin, feasible M0 0/1, feasible v2 0/1, model, nation,
 *       target];
 *   <li>{@code retrovie}: [nation, case, class, unit type, TUV, round];
 *   <li>{@code chiamate_calcolatore}, {@code metodi} (how the per-run losses were read), {@code
 *       errori_calcolatore}.
 * </ul>
 */
final class OmbraDettagli {
  private OmbraDettagli() {}

  private static final Pattern CAMPAGNA = Pattern.compile("campagna-ombra-R(\\d+)-(.+)\\.json");
  private static final Pattern RETROVIE = Pattern.compile("retrovie-ombra-R(\\d+)-(.+)\\.json");

  static JsonObject leggi(@Nullable final Path diary) {
    final JsonObject out = new JsonObject();
    if (diary == null || !Files.isDirectory(diary)) {
      return out;
    }
    final List<Path> files;
    try (Stream<Path> s = Files.list(diary)) {
      files = s.sorted().toList();
    } catch (final IOException e) {
      return out;
    }
    for (final Path f : files) {
      final String name = f.getFileName().toString();
      final Matcher c = CAMPAGNA.matcher(name);
      final Matcher r = RETROVIE.matcher(name);
      try {
        if (c.matches()) {
          campagna(alliance(out, c.group(2)), Integer.parseInt(c.group(1)), read(f));
        } else if (r.matches()) {
          retrovie(alliance(out, r.group(2)), Integer.parseInt(r.group(1)), read(f));
        }
      } catch (final IOException | RuntimeException e) {
        out.addProperty("errore", name + ": " + e);
      }
    }
    return out;
  }

  private static JsonObject read(final Path f) throws IOException {
    return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
  }

  private static JsonObject alliance(final JsonObject out, final String a) {
    if (!out.has(a)) {
      final JsonObject x = new JsonObject();
      for (final String k :
          List.of("difensori", "contrattacco", "battaglie_calcolatore", "fattibili", "retrovie")) {
        x.add(k, new JsonArray());
      }
      x.addProperty("chiamate_calcolatore", 0);
      x.addProperty("errori_calcolatore", 0);
      x.add("metodi", new JsonObject());
      out.add(a, x);
    }
    return out.getAsJsonObject(a);
  }

  private static void campagna(final JsonObject a, final int round, final JsonObject file) {
    if (!file.has("nazioni")) {
      return;
    }
    for (final var n : file.getAsJsonObject("nazioni").entrySet()) {
      final JsonObject o = n.getValue().getAsJsonObject();
      add(
          a,
          "chiamate_calcolatore",
          o.has("chiamate_calcolatore") ? o.get("chiamate_calcolatore").getAsInt() : 0);
      if (o.has("errore_calcolatore")) {
        add(a, "errori_calcolatore", 1);
      }
      final JsonObject v = o.has("verifica") ? o.getAsJsonObject("verifica") : new JsonObject();
      for (final JsonElement e : arr(v, "difensori_modelli")) {
        final JsonObject x = e.getAsJsonObject();
        final JsonArray row = new JsonArray();
        row.add(x.get("orizzonte").getAsInt());
        row.add(x.get("tipo").getAsString());
        row.add(x.get("guerra").getAsBoolean() ? 1 : 0);
        row.add(x.get("reali_tuv").getAsDouble());
        row.add(x.get("m0").getAsDouble());
        row.add(x.get("m1").getAsDouble());
        row.add(x.get("m2").getAsDouble());
        a.getAsJsonArray("difensori").add(row);
      }
      for (final JsonElement e : arr(v, "contrattacco")) {
        final JsonObject x = e.getAsJsonObject();
        final JsonArray row = new JsonArray();
        row.add(x.get("tipo").getAsString());
        row.add(x.get("tenuta_stimata").getAsDouble());
        row.add(x.get("massima_tuv").getAsDouble());
        row.add(x.get("attaccato").getAsBoolean() ? 1 : 0);
        row.add(x.get("impegnata_tuv").getAsDouble());
        row.add(x.get("tenuto").getAsBoolean() ? 1 : 0);
        a.getAsJsonArray("contrattacco").add(row);
      }
      for (final JsonElement e : arr(v, "battaglie_calcolatore")) {
        final JsonObject x = e.getAsJsonObject();
        final JsonArray row = new JsonArray();
        row.add(x.get("perdita_reale_tuv").getAsDouble());
        for (final String m : ConfrontoOmbra.MARGINI) {
          row.add(x.has("p" + m) ? x.get("p" + m).getAsDouble() : -1);
        }
        a.getAsJsonArray("battaglie_calcolatore").add(row);
      }
      for (final JsonElement e : arr(o, "campagne")) {
        final JsonObject c = e.getAsJsonObject();
        if (!c.has("margini_v2")) {
          continue;
        }
        for (final String m : ConfrontoOmbra.MARGINI) {
          final JsonObject m0 = c.getAsJsonObject("margini").getAsJsonObject(m);
          final JsonObject v2 = c.getAsJsonObject("margini_v2").getAsJsonObject(m);
          if (m0 == null || v2 == null) {
            continue;
          }
          final JsonArray row = new JsonArray();
          row.add(round);
          row.add(Integer.parseInt(m));
          row.add(m0.get("fattibile").getAsBoolean() ? 1 : 0);
          row.add(v2.get("fattibile").getAsBoolean() ? 1 : 0);
          row.add(v2.get("modello").getAsString());
          row.add(n.getKey());
          row.add(c.get("obiettivo").getAsString());
          a.getAsJsonArray("fattibili").add(row);
          for (final JsonElement t : arr(v2, "tappe")) {
            final JsonObject x = t.getAsJsonObject();
            if (x.has("calcolatore")) {
              final String how = x.getAsJsonObject("calcolatore").get("metodo").getAsString();
              final JsonObject mt = a.getAsJsonObject("metodi");
              mt.addProperty(how, (mt.has(how) ? mt.get(how).getAsInt() : 0) + 1);
            }
          }
        }
      }
    }
  }

  private static void retrovie(final JsonObject a, final int round, final JsonObject file) {
    if (!file.has("nazioni")) {
      return;
    }
    for (final var n : file.getAsJsonObject("nazioni").entrySet()) {
      for (final JsonElement e : n.getValue().getAsJsonArray()) {
        final JsonObject x = e.getAsJsonObject();
        final JsonArray row = new JsonArray();
        row.add(n.getKey());
        row.add(x.get("caso").getAsString());
        row.add(x.get("classe").getAsString());
        row.add(x.get("tipo").getAsString());
        row.add(x.get("tuv").getAsDouble());
        row.add(round);
        a.getAsJsonArray("retrovie").add(row);
      }
    }
  }

  private static JsonArray arr(final JsonObject o, final String key) {
    return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
  }

  private static void add(final JsonObject o, final String key, final int by) {
    o.addProperty(key, (o.has(key) ? o.get(key).getAsInt() : 0) + by);
  }
}
