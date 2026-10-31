package io.github.ziyuw_plank.gboardgloss;

/** Pure clipboard-retention rules shared by Gboard hooks and tests. */
public final class ClipboardPolicy {
  public static final int DEFAULT_LIMIT = 100;
  public static final int MAX_LIMIT = 1000;
  public static final int PINNED_FLAG = 1;

  private ClipboardPolicy() {}

  public static int normalizeLimit(int requested) {
    if (requested < 1) return DEFAULT_LIMIT;
    return Math.min(requested, MAX_LIMIT);
  }

  public static int deleteTarget(int total, int limit) {
    return Math.max(0, total - normalizeLimit(limit));
  }

  public static boolean isPinned(int itemType) {
    return (itemType & PINNED_FLAG) != 0;
  }
}
