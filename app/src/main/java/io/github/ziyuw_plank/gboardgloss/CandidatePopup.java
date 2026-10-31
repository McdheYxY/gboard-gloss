package io.github.ziyuw_plank.gboardgloss;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/** Gboard-style, drag-selectable bubble for translated candidates. */
final class CandidatePopup {
  interface ChoiceHandler { boolean onChoice(String text); }

  private static final int CHOICE_ORIGINAL = 1;
  private static final int CHOICE_TRANSLATION = 2;
  private static final int CHOICE_CLOSE = 3;

  private static PopupWindow current;
  private static View currentAnchor;
  private static LinearLayout currentCard;
  private static LinearLayout originalRow;
  private static LinearLayout translationRow;
  private static LinearLayout closeRow;
  private static ChoiceHandler currentHandler;
  private static String currentOriginal;
  private static String currentTranslation;
  private static float density;
  private static int surfaceColor;
  private static int keyColor;
  private static int accentColor;
  private static int primaryText;
  private static int cachedSurfaceColor;
  private static long cachedSurfaceAt;
  private static int selected = CHOICE_TRANSLATION;

  private CandidatePopup() {}

  static void show(View anchor, String original, String translation,
      ChoiceHandler handler, Runnable onDismiss) {
    Context context = anchor.getContext();
    density = context.getResources().getDisplayMetrics().density;
    int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
    int width = Math.min(dp(232), screenWidth - dp(24));
    boolean night = (anchor.getResources().getConfiguration().uiMode
        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    primaryText = childTextColor(anchor);
    if (Color.alpha(primaryText) == 0) {
      primaryText = themeColor(context, android.R.attr.textColorPrimary,
          night ? 0xFFF1F3F4 : 0xFF202124);
    }
    ThemeColor theme = keyboardTheme(anchor, night);
    applyTheme(theme, night);

    LinearLayout card = new LinearLayout(context);
    currentCard = card;
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(6), dp(6), dp(6), dp(6));
    styleCard(card);
    card.setElevation(dp(8));

    originalRow = option(context, "原文", original);
    translationRow = option(context, "译文", translation);
    closeRow = closeOption(context);
    card.addView(originalRow, rowParams());
    card.addView(translationRow, rowParams());
    card.addView(closeRow, rowParams());

    if (current != null) current.dismiss();
    PopupWindow popup = new PopupWindow(card, width, ViewGroup.LayoutParams.WRAP_CONTENT, false);
    current = popup;
    currentAnchor = anchor;
    currentHandler = handler;
    currentOriginal = original;
    currentTranslation = translation;
    styleOption(originalRow, false);
    styleOption(translationRow, true);
    styleOption(closeRow, false);
    popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    popup.setOutsideTouchable(true);
    popup.setClippingEnabled(false);
    popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
    popup.setElevation(dp(8));
    popup.setOnDismissListener(() -> {
      if (current != popup) return;
      current = null;
      currentAnchor = null;
      currentCard = null;
      currentHandler = null;
      originalRow = null;
      translationRow = null;
      closeRow = null;
      selected = CHOICE_TRANSLATION;
      if (onDismiss != null) onDismiss.run();
    });
    originalRow.setOnClickListener(v -> applyChoice(original));
    translationRow.setOnClickListener(v -> applyChoice(translation));
    closeRow.setOnClickListener(v -> dismissCurrent());

    card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    int popupHeight = card.getMeasuredHeight();
    int[] location = new int[2];
    anchor.getLocationOnScreen(location);
    int gap = dp(6);
    int x = location[0] + (anchor.getWidth() - width) / 2;
    int y = location[1] - popupHeight + dp(2);
    if (y < dp(24)) y = location[1] + anchor.getHeight() + gap;
    int margin = dp(8);
    x = Math.max(margin, Math.min(x, screenWidth - width - margin));
    card.setAlpha(0f);
    card.setScaleX(0.9f);
    card.setScaleY(0.9f);
    popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, x, y);
    card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start();
    if (cachedSurfaceColor == 0
        || SystemClock.elapsedRealtime() - cachedSurfaceAt > 1500) {
      sampleKeyboardAsync(popup, anchor, primaryText, night);
    }
  }

  static void updateDrag(View anchor, float dy) {
    if (current == null || currentAnchor != anchor) return;
    int choice = direction(dy);
    if (choice == selected) return;
    selected = choice;
    styleOption(originalRow, selected == CHOICE_ORIGINAL);
    styleOption(translationRow, selected == CHOICE_TRANSLATION);
    styleOption(closeRow, selected == CHOICE_CLOSE);
    anchor.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
  }

  static boolean finishDrag(View anchor, float dy) {
    updateDrag(anchor, dy);
    if (current == null || currentAnchor != anchor) return false;
    if (selected == CHOICE_CLOSE) {
      dismissCurrent();
      return true;
    }
    if (selected == CHOICE_ORIGINAL) return applyChoice(currentOriginal);
    return applyChoice(currentTranslation);
  }

  static void dismissFor(View anchor) {
    if (current != null && currentAnchor == anchor) dismissCurrent();
  }

  static void prepareTheme(View anchor) {
    if (cachedSurfaceColor != 0
        && SystemClock.elapsedRealtime() - cachedSurfaceAt < 2000) return;
    boolean night = (anchor.getResources().getConfiguration().uiMode
        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    sampleKeyboardAsync(null, anchor, childTextColor(anchor), night);
  }

  static int candidateTextColor(View view) {
    return childTextColor(view);
  }

  /**
   * Tints the theme's primary candidate text toward the keyboard surface while
   * retaining most of its contrast. Falls back to the primary text color when
   * no reliable theme color is available.
   */
  static int secondaryTextColor(int primary) {
    if (Color.alpha(primary) == 0) return primary;
    int surface = cachedSurfaceColor;
    if (Color.alpha(surface) == 0) {
      return (primary & 0x00FFFFFF) | 0xE6000000;
    }
    float primaryContrast = contrast(surface, primary);
    float minimum = Math.max(2.5f, Math.min(4.5f, primaryContrast * 0.92f));
    for (float amount : new float[] {0.16f, 0.12f, 0.08f, 0.04f}) {
      int color = blend(primary, surface, amount);
      if (contrast(surface, color) >= minimum) return color;
    }
    return primary;
  }

  private static boolean applyChoice(String value) {
    ChoiceHandler handler = currentHandler;
    if (handler == null) return false;
    boolean accepted = handler.onChoice(value);
    if (accepted) dismissCurrent();
    return accepted;
  }

  private static void dismissCurrent() {
    if (current != null) current.dismiss();
  }

  private static int direction(float dy) {
    float threshold = dp(42);
    if (dy < -threshold) return CHOICE_ORIGINAL;
    if (dy > threshold * 2.6f) return CHOICE_CLOSE;
    return CHOICE_TRANSLATION;
  }

  private static LinearLayout option(Context context, String label, String value) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setPadding(dp(14), dp(10), dp(14), dp(10));

    TextView caption = new TextView(context);
    caption.setText(label);
    caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
    caption.setLetterSpacing(0.04f);
    row.addView(caption);

    TextView body = new TextView(context);
    body.setText(value);
    body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
    body.setPadding(0, dp(2), 0, 0);
    row.addView(body);
    row.setClickable(true);
    row.setFocusable(false);
    styleOption(row, false);
    return row;
  }

  private static LinearLayout closeOption(Context context) {
    return actionOption(context, "关闭");
  }

  private static LinearLayout actionOption(Context context, String label) {
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER);
    row.setPadding(dp(14), dp(9), dp(14), dp(9));
    TextView caption = new TextView(context);
    caption.setText(label);
    caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    row.addView(caption);
    row.setClickable(true);
    row.setFocusable(false);
    styleOption(row, false);
    return row;
  }

  private static void styleOption(LinearLayout row, boolean isSelected) {
    if (row == null) return;
    GradientDrawable background = new GradientDrawable();
    background.setColor(isSelected ? accentColor : keyColor);
    background.setCornerRadius(dp(16));
    row.setBackground(background);
    int textColor = isSelected
        ? (luminance(accentColor) > 0.55f ? 0xFF202124 : 0xFFFFFFFF)
        : primaryText;
    for (int i = 0; i < row.getChildCount(); i++) {
      View child = row.getChildAt(i);
      if (child instanceof TextView) ((TextView) child).setTextColor(textColor);
    }
  }

  private static LinearLayout.LayoutParams rowParams() {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(2);
    return params;
  }

  private static int themeColor(Context context, int attribute, int fallback) {
    TypedValue value = new TypedValue();
    if (context.getTheme().resolveAttribute(attribute, value, true)) {
      if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
          && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
        return value.data;
      }
      if (value.resourceId != 0) {
        try {
          return context.getResources().getColor(value.resourceId, context.getTheme());
        } catch (Throwable ignored) {}
      }
    }
    return fallback;
  }

  private static ThemeColor keyboardTheme(View anchor, boolean night) {
    int text = childTextColor(anchor);
    int surface = cachedSurfaceColor;
    if (Color.alpha(surface) == 0) surface = ancestorBackground(anchor);
    if (Color.alpha(surface) == 0) {
      surface = themeColor(anchor.getContext(), android.R.attr.colorBackground,
          night ? 0xFF202124 : 0xFFFFFFFF);
    }
    surface = Color.rgb(Color.red(surface), Color.green(surface), Color.blue(surface));
    if (Color.alpha(text) == 0 || contrast(surface, text) < 3f) {
      text = luminance(surface) > 0.58f ? 0xFF202124 : 0xFFFFFFFF;
    }
    return new ThemeColor(surface, text);
  }

  /**
   * PixelCopy reads Gboard's composited IME window, including image-backed
   * themes. Drawing the view tree into a software bitmap does not include
   * surfaces rendered by Gboard's hardware theme layer.
   */
  private static void sampleKeyboardAsync(PopupWindow popup, View anchor,
      int candidateText, boolean night) {
    InputMethodService ims = Hook.activeIme.get();
    if (ims == null || ims.getWindow() == null || ims.getWindow().getWindow() == null) {
      return;
    }
    Window window = ims.getWindow().getWindow();
    View decor = window.getDecorView();
    int width = decor.getWidth();
    int height = decor.getHeight();
    if (width < 2 || height < 2) return;
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    try {
      PixelCopy.request(window, bitmap, result -> {
        try {
          if (result != PixelCopy.SUCCESS
              || (popup != null && current != popup)) return;
          int[] decorLocation = new int[2];
          int[] anchorLocation = new int[2];
          decor.getLocationOnScreen(decorLocation);
          anchor.getLocationOnScreen(anchorLocation);
          int ax = anchorLocation[0] - decorLocation[0] + anchor.getWidth() / 2;
          int ay = anchorLocation[1] - decorLocation[1] + anchor.getHeight() / 2;
          int surface = dominantColor(bitmap, ax, ay,
              Math.max(anchor.getWidth(), anchor.getHeight()));
          if (Color.alpha(surface) == 0) return;
          cachedSurfaceColor = surface;
          cachedSurfaceAt = SystemClock.elapsedRealtime();
          if (popup == null) return;
          ThemeColor theme = new ThemeColor(surface, readableText(surface, candidateText));
          applyTheme(theme, night);
          styleOption(originalRow, selected == CHOICE_ORIGINAL);
          styleOption(translationRow, selected == CHOICE_TRANSLATION);
          styleOption(closeRow, selected == CHOICE_CLOSE);
          if (Hook.prefs != null && Hook.prefs.getBoolean(Prefs.DEBUG, false)) {
            Hook.log("popup theme sampled color=" + colorName(surface));
          }
        } catch (Throwable t) {
          Hook.log("popup theme sampling failed " + t);
        } finally {
          bitmap.recycle();
        }
      }, new Handler(Looper.getMainLooper()));
    } catch (Throwable t) {
      bitmap.recycle();
      Hook.log("popup theme PixelCopy unavailable " + t);
    }
  }

  private static void applyTheme(ThemeColor theme, boolean night) {
    surfaceColor = theme.surface;
    primaryText = theme.text;
    keyColor = blend(surfaceColor, primaryText, night ? 0.12f : 0.07f);
    accentColor = selectionColor(surfaceColor, primaryText);
    styleCard(currentCard);
  }

  private static void styleCard(LinearLayout card) {
    if (card == null) return;
    GradientDrawable background = new GradientDrawable();
    background.setColor(surfaceColor);
    background.setCornerRadius(dp(24));
    card.setBackground(background);
  }

  private static int readableText(int surface, int preferred) {
    if (contrast(surface, preferred) >= 4.5f) return preferred;
    return luminance(surface) > 0.58f ? 0xFF202124 : 0xFFFFFFFF;
  }

  private static String colorName(int color) {
    return Color.alpha(color) == 0 ? "transparent"
        : String.format("#%08X", color);
  }

  private static int dominantColor(Bitmap bitmap, int ax, int ay, int radius) {
    int[] counts = new int[8192];
    int[] colors = new int[8192];
    int width = bitmap.getWidth();
    int height = bitmap.getHeight();

    int edge = Math.max(dp(5), radius);
    for (int i = 0; i < 8; i++) {
      double angle = Math.PI * i / 4;
      addSample(bitmap, counts, colors, width, height,
          Math.round(ax + (float) Math.cos(angle) * edge),
          Math.round(ay + (float) Math.sin(angle) * edge));
    }

    // Window edges are generally free of key/candidate text and expose the
    // real theme background, including image-backed themes.
    for (int x : new int[] {width / 32, width - width / 32, width / 2}) {
      for (int y = height / 20; y < height; y += Math.max(dp(8), height / 32)) {
        addSample(bitmap, counts, colors, width, height, x, y);
      }
    }

    int best = -1;
    int bestCount = 0;
    for (int i = 0; i < counts.length; i++) {
      if (counts[i] > bestCount) {
        best = i;
        bestCount = counts[i];
      }
    }
    return best < 0 ? Color.TRANSPARENT : colors[best];
  }

  private static void addSample(Bitmap bitmap, int[] counts, int[] colors,
      int width, int height, int x, int y) {
    if (x < 0 || y < 0 || x >= width || y >= height) return;
    int color = bitmap.getPixel(x, y);
    if (Color.alpha(color) < 240) return;
    int r = (Color.red(color) >> 3) << 3;
    int g = (Color.green(color) >> 3) << 3;
    int b = (Color.blue(color) >> 3) << 3;
    int key = ((r >> 3) << 8) | ((g >> 3) << 4) | (b >> 3);
    counts[key]++;
    colors[key] = Color.rgb(r, g, b);
  }

  private static int ancestorBackground(View view) {
    for (ViewParent parent = view.getParent(); parent instanceof View;
        parent = parent.getParent()) {
      Drawable background = ((View) parent).getBackground();
      if (background instanceof ColorDrawable) {
        int color = ((ColorDrawable) background).getColor();
        if (Color.alpha(color) != 0) return color;
      }
    }
    return Color.TRANSPARENT;
  }

  private static int selectionColor(int surface, int text) {
    float light = luminance(surface);
    if (light > 0.58f) return blend(surface, 0xFF000000, 0.24f);
    if (light < 0.2f) return blend(surface, text, 0.22f);
    return blend(surface, 0xFF000000, 0.28f);
  }

  private static int childTextColor(View view) {
    if (view instanceof TextView) {
      int color = ((TextView) view).getCurrentTextColor();
      if (Color.alpha(color) != 0) return color;
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        int color = childTextColor(group.getChildAt(i));
        if (Color.alpha(color) != 0) return color;
      }
    }
    return Color.TRANSPARENT;
  }

  private static int blend(int background, int foreground, float amount) {
    return Color.rgb(
        Math.round(Color.red(background) * (1 - amount) + Color.red(foreground) * amount),
        Math.round(Color.green(background) * (1 - amount) + Color.green(foreground) * amount),
        Math.round(Color.blue(background) * (1 - amount) + Color.blue(foreground) * amount));
  }

  private static float luminance(int color) {
    return (Color.red(color) * 0.299f + Color.green(color) * 0.587f
        + Color.blue(color) * 0.114f) / 255f;
  }

  private static float contrast(int first, int second) {
    float a = channelLuminance(first);
    float b = channelLuminance(second);
    return (Math.max(a, b) + 0.05f) / (Math.min(a, b) + 0.05f);
  }

  private static float channelLuminance(int color) {
    return 0.2126f * linear(Color.red(color))
        + 0.7152f * linear(Color.green(color))
        + 0.0722f * linear(Color.blue(color));
  }

  private static float linear(int channel) {
    float value = channel / 255f;
    return value <= 0.04045f ? value / 12.92f
        : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
  }

  private static final class ThemeColor {
    final int surface;
    final int text;

    ThemeColor(int surface, int text) {
      this.surface = surface;
      this.text = text;
    }
  }

  private static int dp(int value) {
    return Math.round(value * density);
  }
}
