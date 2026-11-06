package org.triplea.proplus;

import lombok.experimental.UtilityClass;
import org.triplea.config.product.ProductVersionReader;

/** Names shown in the UI so that the Pro+ build is easy to tell apart from official TripleA. */
@UtilityClass
public class ProPlusBranding {
  public static final String PRODUCT_NAME = "TripleA Pro+";

  /** Window title, e.g. "TripleA Pro+ 2026-11.16.15894". */
  public static String windowTitle() {
    return PRODUCT_NAME + " " + ProductVersionReader.getCurrentVersion();
  }

  /** Engine version as shown in the start screen info box, e.g. "2026-11.16.15894 (Pro+)". */
  public static String engineVersionLabel() {
    return ProductVersionReader.getCurrentVersion() + " (Pro+)";
  }
}
