package io.github.ziyuw_plank.gboardgloss;

/** Target-language selection rules shared by Settings and the Gboard hook. */
public final class LanguageMode {
  public static final String AUTO_ZH_EN = "auto_zh_en";

  private LanguageMode() {}

  public static boolean isAuto(String target) {
    return AUTO_ZH_EN.equals(target);
  }

  /** Resolve a saved target against the language detected from the current candidate. */
  public static String resolveTarget(String configured, String source) {
    if (!isAuto(configured)) return configured;
    if ("zh".equals(source)) return "en";
    if ("en".equals(source)) return "zh";
    return "en";
  }

  public static String displayName(String configured) {
    return isAuto(configured) ? "中英自动" : configured;
  }
}
