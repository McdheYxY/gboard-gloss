package io.github.ziyuw_plank.gboardgloss;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.InputEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Additive gloss on Gboard candidate SoftKeyViews.
 * Hot path: tagged gloss + soft secondary paint only.
 */
public class Hook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
  static final String GBOARD = "com.google.android.inputmethod.latin";
  static final String ME = "io.github.ziyuw_plank.gboardgloss";
  static final String SOFTKEY = "com.google.android.libraries.inputmethod.widgets.SoftKeyView";
  static final String SOFTKEY_DEF = "com.google.android.libraries.inputmethod.metadata.SoftKeyDef";
  static final int REMOVE_SUGGESTION_ACTION = -10031;

  static String modulePath;
  static XSharedPreferences prefs;
  static ZipFile zip;
  static Class<?> softKeyCls;
  static Field softKeyDefLabels;
  static Field softKeyDefField;
  static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  static boolean paintInit, bannerShown, toastShown;
  static int logBudget = 40;
  static long lastReload;
  static int lastSize = -1;
  static float lastDensity = -1;
  static final Set<Class<?>> hookedIme = new HashSet<>();
  static final Set<Method> hookedLongHandlers = new HashSet<>();
  static String lastUserGlossary;
  static final ThreadLocal<Boolean> CLIPBOARD_CLEANING = new ThreadLocal<>();
  static volatile WeakReference<InputMethodService> activeIme = new WeakReference<>(null);

  static final Dictionary.Opener OPENER = Hook::openAsset;

  static void log(String s) { XposedBridge.log("[gloss] " + s); }

  static InputStream openAsset(String name) throws java.io.IOException {
    if (zip == null) zip = new ZipFile(modulePath);
    ZipEntry e = zip.getEntry("assets/" + name);
    if (e == null) throw new FileNotFoundException(name);
    return zip.getInputStream(e);
  }

  @Override public void initZygote(StartupParam p) { modulePath = p.modulePath; }

  @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
    if (lp.packageName.equals(ME)) {
      try {
        XposedHelpers.findAndHookMethod(SettingsActivity.class.getName(), lp.classLoader,
            "isActive", XC_MethodReplacement.returnConstant(true));
      } catch (Throwable t) { log("self hook " + t); }
      return;
    }
    if (lp.packageName.equals("android")) { SysVisibility.install(lp.classLoader); return; }
    if (!lp.packageName.equals(GBOARD) || !lp.isFirstApplication) return;

    log("loaded in " + lp.processName + " module=" + modulePath);
    prefs = new XSharedPreferences(ME, Prefs.NAME);
    prefs.makeWorldReadable();
    reloadGlossary();
    log("prefs readable=" + prefs.getFile().canRead()
        + " enabled=" + prefs.getBoolean(Prefs.ENABLED, true)
        + " lang=" + prefs.getString(Prefs.LANG, "en")
        + " online=" + prefs.getBoolean(Prefs.ONLINE, false)
        + " glossary builtin=" + Dictionary.glossaryBuiltinCount()
        + " user=" + Dictionary.glossaryUserCount());

    try {
      softKeyCls = XposedHelpers.findClass(SOFTKEY, lp.classLoader);
      log("found " + SOFTKEY);
    } catch (Throwable t) {
      log("SoftKeyView NOT found: " + t);
    }

    try {
      Class<?> defCls = XposedHelpers.findClass(SOFTKEY_DEF, lp.classLoader);
      softKeyDefLabels = findLabelField(defCls);
      if (softKeyDefLabels != null) {
        log("SoftKeyDef labels field=" + softKeyDefLabels.getName()
            + " type=" + softKeyDefLabels.getType().getName());
      } else {
        log("SoftKeyDef labels field NOT found; TextView fallback only");
      }
    } catch (Throwable t) {
      log("SoftKeyDef labels: " + t);
    }

    try {
      int n = Dictionary.size(OPENER, "zh", "en");
      log("dict zh_en size=" + n + " via=" + Dictionary.loadedVia(OPENER, "zh", "en"));
    } catch (Throwable t) { log("dict prefetch " + t); }

    try {
      XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam p) {
          try {
            if (toastShown || !prefs.getBoolean(Prefs.BANNER, true)) return;
            toastShown = true;
            Context c = (Context) p.thisObject;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
              try { Toast.makeText(c, "GboardGloss 已注入 ✓", Toast.LENGTH_LONG).show(); }
              catch (Throwable t) { log("toast " + t); }
            }, 1500);
          } catch (Throwable t) { log("app.onCreate " + t); }
        }
      });
      log("hook installed: Application.onCreate");
    } catch (Throwable t) { log("hook fail Application.onCreate " + t); }

    try {
      installClipboardLimit(lp.classLoader);
    } catch (Throwable t) {
      log("clipboard limit hook failed " + t);
    }

    try {
      XposedHelpers.findAndHookMethod(InputMethodService.class, "onCreate", new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam p) {
          try {
            activeIme = new WeakReference<>((InputMethodService) p.thisObject);
            log("IME onCreate: " + p.thisObject.getClass().getName());
            hookImeClass(p.thisObject.getClass());
          } catch (Throwable t) { log("ime.onCreate " + t); }
        }
      });
      log("hook installed: InputMethodService.onCreate");
    } catch (Throwable t) { log("hook fail IMS.onCreate " + t); }

    try {
      XposedHelpers.findAndHookMethod(InputMethodService.class, "onStartInputView",
          EditorInfo.class, boolean.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
              InputMethodService ims = (InputMethodService) p.thisObject;
              activeIme = new WeakReference<>(ims);
              new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                  if (ims.getWindow() == null) return;
                  CandidatePopup.prepareTheme(ims.getWindow().getWindow().getDecorView());
                } catch (Throwable t) { log("theme prewarm " + t); }
              }, 200);
            }
          });
      log("hook installed: InputMethodService.onStartInputView theme prewarm");
    } catch (Throwable t) { log("hook fail IMS.onStartInputView " + t); }

    try {
      XposedHelpers.findAndHookMethod(View.class, "performLongClick", new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
          try {
            if (showTranslationChoices((View) p.thisObject)) p.setResult(true);
          } catch (Throwable t) {
            log("long-press translation " + t);
          }
        }
      });
      log("hook installed: View.performLongClick translation");
    } catch (Throwable t) {
      log("hook fail View.performLongClick " + t);
    }

    try {
      XposedHelpers.findAndHookMethod(View.class, "dispatchTouchEvent",
          MotionEvent.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
              Object self = p.thisObject;
              try {
                MotionEvent event = (MotionEvent) p.args[0];
                if (CandidateTouchWatcher.observeGlobal(event)) {
                  p.setResult(true);
                  return;
                }
                if (softKeyCls == null || !softKeyCls.isInstance(self)) return;
                View candidate = (View) self;
                boolean fired = CandidateTouchWatcher.handle(candidate, event);
                if (fired) p.setResult(true);
              } catch (Throwable t) {
                log("candidate dispatch touch " + t);
              }
            }
          });
      log("hook installed: SoftKeyView.dispatchTouchEvent long-press");
    } catch (Throwable t) {
      log("hook fail SoftKeyView.dispatchTouchEvent " + t);
    }

    try {
      XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchTouchEvent",
          MotionEvent.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
              try {
                MotionEvent event = (MotionEvent) p.args[0];
                if (CandidateTouchWatcher.observeGlobal(event)) p.setResult(true);
              } catch (Throwable t) {
                log("view group dispatch touch " + t);
              }
            }
          });
      log("hook installed: ViewGroup.dispatchTouchEvent long-press");
    } catch (Throwable t) {
      log("hook fail ViewGroup.dispatchTouchEvent " + t);
    }

    try {
      hookViewRootInput(lp.classLoader);
    } catch (Throwable t) {
      log("hook fail ViewRootImpl input " + t);
    }

    if (softKeyCls != null) {
      softKeyDefField = findSoftKeyDefField(softKeyCls);
      if (softKeyDefField != null) {
        log("SoftKeyView SoftKeyDef field=" + softKeyDefField.getName());
      }
      int n = hookSoftKeyBindMethods(softKeyCls, lp.classLoader);
      log("SoftKeyView bind hooks installed=" + n);
      try {
        hookSoftKeyLongPress(softKeyCls, lp.classLoader);
      } catch (Throwable t) {
        log("hook fail SoftKeyView long-press " + t);
      }
    }

    try {
      XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchDraw", Canvas.class, new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam p) {
          Object self = p.thisObject;
          if (softKeyCls == null || !softKeyCls.isInstance(self)) return;
          try {
            View v = (View) self;
            // Fallback if bind hook missed this SoftKeyView (obfuscation rename / early draw)
            if (v.getTag(R.id.gloss_word) == null && softKeyDefField != null) {
              try {
                Object def = softKeyDefField.get(v);
                if (def != null) onSoftKeyBound(v, def);
              } catch (Throwable ignored) {}
            }
            drawCachedGloss(v, (Canvas) p.args[0]);
          } catch (Throwable t) {
            if (logBudget > 0) { logBudget--; log("draw err " + t); }
          }
        }
      });
      log("hook installed: ViewGroup.dispatchDraw (SoftKeyView-filtered)");
    } catch (Throwable t) { log("hook fail dispatchDraw " + t); }
  }

  static void hookSoftKeyLongPress(Class<?> softKey, ClassLoader cl) {
    int installed = 0;
    for (Method method : softKey.getDeclaredMethods()) {
      if (!method.getName().equals("n") || method.getReturnType() != void.class
          || method.getParameterTypes().length != 1) continue;
      XC_MethodHook hook = new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam p) {
          try {
            hookLongPressHandler(p.args[0]);
          } catch (Throwable t) {
            log("long-press handler setup " + t);
          }
        }
      };
      XposedBridge.hookMethod(method, hook);
      installed++;
    }
    log("hook installed: SoftKeyView long-press handlers=" + installed);

    try {
      Class<?> candidateTouch = XposedHelpers.findClass("nsv", cl);
      XposedHelpers.findAndHookMethod(candidateTouch, "hE",
          softKey, MotionEvent.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
              try {
                View candidate = (View) p.args[0];
                MotionEvent event = (MotionEvent) p.args[1];
                boolean fired = CandidateTouchWatcher.handle(candidate, event);
                if (fired) p.setResult(true);
              } catch (Throwable t) {
                log("candidate touch " + t);
              }
            }
          });
      log("hook installed: candidate touch long-press");
    } catch (Throwable t) {
      log("hook fail candidate touch long-press " + t);
    }

    try {
      Class<?> suggestionLongClick = XposedHelpers.findClass("fzi", cl);
      XposedHelpers.findAndHookMethod(suggestionLongClick, "onLongClick",
          View.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
              try {
                if (showTranslationChoices((View) p.args[0])) p.setResult(true);
              } catch (Throwable t) {
                log("suggestion long-click " + t);
              }
            }
          });
      log("hook installed: Gboard suggestion long-click");
    } catch (Throwable t) {
      log("hook fail Gboard suggestion long-click " + t);
    }
  }

  static void hookViewRootInput(ClassLoader cl) {
    Class<?> viewRoot = XposedHelpers.findClass("android.view.ViewRootImpl", cl);
    int installed = 0;
    for (Method method : viewRoot.getDeclaredMethods()) {
      if (!method.getName().equals("dispatchInputEvent")
          || method.getParameterTypes().length != 1) continue;
      XposedBridge.hookMethod(method, new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
          if (!(p.args[0] instanceof MotionEvent)) return;
          try {
            if (CandidateTouchWatcher.observeGlobal((MotionEvent) p.args[0])
                && ((Method) p.method).getReturnType() == int.class) {
              p.setResult(2); // InputStage.FINISH_HANDLED
            }
          } catch (Throwable t) {
            log("view root touch " + t);
          }
        }
      });
      installed++;
    }
    log("hook installed: ViewRootImpl input handlers=" + installed);
  }

  static void hookLongPressHandler(Object handler) {
    if (handler == null || softKeyCls == null) return;
    for (Class<?> c = handler.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
      for (Method method : c.getDeclaredMethods()) {
        if (!method.getName().equals("hD") || method.getParameterTypes().length != 1
            || method.getParameterTypes()[0] != softKeyCls) continue;
        if (!hookedLongHandlers.add(method)) return;
        XposedBridge.hookMethod(method, new XC_MethodHook() {
          @Override protected void beforeHookedMethod(MethodHookParam p) {
            try {
              if (showTranslationChoices((View) p.args[0])) p.setResult(true);
            } catch (Throwable t) {
              log("long-press handler " + t);
            }
          }
        });
        log("hook installed: Gboard long-press handler " + c.getName());
        return;
      }
    }
  }

  static boolean showTranslationChoices(View v) {
    View candidate = findSoftKeyAncestor(v);
    if (candidate == null) return false;
    Object originalValue = candidate.getTag(R.id.gloss_word);
    Object translationValue = candidate.getTag(R.id.gloss_commit);
    if (!(originalValue instanceof String) || ((String) originalValue).isEmpty()
        || !(translationValue instanceof String) || ((String) translationValue).isEmpty()) {
      if (prefs.getBoolean(Prefs.DEBUG, false)) log("long-press candidate has no translation");
      return false;
    }
    prefs.reload();
    if (!prefs.getBoolean(Prefs.ENABLED, true)) return false;
    InputMethodService ims = activeIme.get();
    InputConnection input = ims == null ? null : ims.getCurrentInputConnection();
    if (input == null) return false;
    String original = (String) originalValue;
    String translation = (String) translationValue;
    if (Boolean.TRUE.equals(candidate.getTag(R.id.gloss_popup_open))) return true;
    stripNativeDeleteAction(candidate.getTag(R.id.gloss_soft_key_def));
    CandidatePopup.show(v, original, translation, choice -> {
      try {
        input.commitText(choice, 1);
        log("long-press committed chars=" + choice.length()
            + " translated=" + choice.equals(translation));
        return true;
      } catch (Throwable t) {
        log("long-press commit " + t);
        return false;
      }
    }, () -> candidate.setTag(R.id.gloss_popup_open, null));
    candidate.setTag(R.id.gloss_popup_open, true);
    log("long-press choices shown");
    return true;
  }

  static View findSoftKeyAncestor(View v) {
    if (softKeyCls == null) return null;
    if (softKeyCls.isInstance(v)) return v;
    for (ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
      if (softKeyCls.isInstance(p)) return (View) p;
    }
    return null;
  }

  static void updateTranslationLongPress(View v) {
    Object value = v.getTag(R.id.gloss_commit);
    boolean available = value instanceof String && !((String) value).isEmpty();
    boolean ours = Boolean.TRUE.equals(v.getTag(R.id.gloss_long_click_ours));
    if (available) {
      if (!ours) {
        v.setTag(R.id.gloss_long_click_original, v.isLongClickable());
        v.setTag(R.id.gloss_long_click_ours, true);
      }
      v.setLongClickable(true);
    } else if (ours) {
      Object original = v.getTag(R.id.gloss_long_click_original);
      v.setLongClickable(original instanceof Boolean && (Boolean) original);
      v.setTag(R.id.gloss_long_click_original, null);
      v.setTag(R.id.gloss_long_click_ours, null);
    }
  }

  static void stripNativeDeleteAction(Object softKeyDef) {
    if (softKeyDef == null) return;
    try {
      Object[] actionDefs = (Object[]) XposedHelpers.getObjectField(softKeyDef, "f");
      if (actionDefs == null || actionDefs.length == 0) return;
      ArrayList<Object> kept = new ArrayList<>();
      boolean removed = false;
      for (Object actionDef : actionDefs) {
        Object[] actions = (Object[]) XposedHelpers.getObjectField(actionDef, "d");
        boolean deleteAction = false;
        if (actions != null) {
          for (Object action : actions) {
            if (XposedHelpers.getIntField(action, "c") == REMOVE_SUGGESTION_ACTION) {
              deleteAction = true;
              break;
            }
          }
        }
        if (deleteAction) removed = true;
        else kept.add(actionDef);
      }
      if (!removed) return;
      Object filtered = java.lang.reflect.Array.newInstance(
          actionDefs.getClass().getComponentType(), kept.size());
      for (int i = 0; i < kept.size(); i++) {
        java.lang.reflect.Array.set(filtered, i, kept.get(i));
      }
      XposedHelpers.setObjectField(softKeyDef, "f", filtered);
    } catch (Throwable t) {
      log("remove native delete action failed " + t);
    }
  }

  static void installClipboardLimit(ClassLoader cl) {
    Class<?> provider = XposedHelpers.findClass(
        "com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider", cl);
    XC_MethodHook afterWrite = new XC_MethodHook() {
      @Override protected void afterHookedMethod(MethodHookParam p) {
        try {
          enforceClipboardLimit((ContentProvider) p.thisObject, (Uri) p.args[0]);
        } catch (Throwable t) {
          log("clipboard limit insert " + t);
        }
      }
    };
    XC_MethodHook beforeRead = new XC_MethodHook() {
      @Override protected void beforeHookedMethod(MethodHookParam p) {
        try {
          enforceClipboardLimit((ContentProvider) p.thisObject, (Uri) p.args[0]);
        } catch (Throwable t) {
          log("clipboard limit query " + t);
        }
      }
    };
    XposedHelpers.findAndHookMethod(provider, "insert",
        Uri.class, ContentValues.class, afterWrite);
    XposedHelpers.findAndHookMethod(provider, "query",
        Uri.class, String[].class, String.class, String[].class, String.class, beforeRead);
    log("hook installed: ClipboardContentProvider limit");
  }

  static void enforceClipboardLimit(ContentProvider provider, Uri uri) {
    if (provider == null || uri == null || !isClipsUri(uri)) return;
    if (Boolean.TRUE.equals(CLIPBOARD_CLEANING.get())) return;
    prefs.reload();
    if (!prefs.getBoolean(Prefs.CLIPBOARD_LIMIT_ENABLED, false)) return;

    int limit = ClipboardPolicy.normalizeLimit(
        prefs.getInt(Prefs.CLIPBOARD_LIMIT, ClipboardPolicy.DEFAULT_LIMIT));
    CLIPBOARD_CLEANING.set(true);
    Cursor c = null;
    try {
      c = provider.query(uri, new String[]{"_id", "item_type"},
          null, null, "timestamp ASC");
      if (c == null) return;
      int total = c.getCount();
      int need = ClipboardPolicy.deleteTarget(total, limit);
      if (need <= 0) return;

      int idCol = c.getColumnIndexOrThrow("_id");
      int typeCol = c.getColumnIndexOrThrow("item_type");
      List<Long> ids = new ArrayList<>(need);
      while (c.moveToNext() && ids.size() < need) {
        if (!ClipboardPolicy.isPinned(c.getInt(typeCol))) ids.add(c.getLong(idCol));
      }
      c.close();
      c = null;

      int removed = 0;
      for (Long id : ids) {
        try {
          if (provider.delete(ContentUris.withAppendedId(uri, id), null, null) > 0) removed++;
        } catch (Throwable t) {
          log("clipboard delete " + id + " " + t);
        }
      }
      if (removed > 0) log("clipboard limit=" + limit + " total=" + total + " removed=" + removed);
    } catch (Throwable t) {
      log("clipboard limit enforce " + t);
    } finally {
      if (c != null) try { c.close(); } catch (Throwable ignored) {}
      CLIPBOARD_CLEANING.remove();
    }
  }

  static boolean isClipsUri(Uri uri) {
    List<String> segments = uri.getPathSegments();
    return "content".equals(uri.getScheme()) && segments != null && !segments.isEmpty()
        && "clips".equals(segments.get(segments.size() - 1));
  }

  static void reloadGlossary() {
    String user = prefs != null ? prefs.getString(Prefs.USER_GLOSSARY, "") : "";
    if (user == null) user = "";
    if (glossaryLoadedSame(user)) return;
    lastUserGlossary = user;
    Dictionary.clearTransCache();
    Dictionary.loadGlossary(OPENER, user);
  }

  static boolean glossaryLoadedSame(String user) {
    return lastUserGlossary != null && lastUserGlossary.equals(user)
        && Dictionary.glossaryBuiltinCount() > 0;
  }

  static void hookImeClass(Class<?> c) {
    if (!hookedIme.add(c)) return;
    XC_MethodHook h = new XC_MethodHook() {
      @Override protected void afterHookedMethod(MethodHookParam p) {
        try { showBanner((InputMethodService) p.thisObject); }
        catch (Throwable t) { log("banner " + t); }
      }
    };
    for (String m : new String[]{"onStartInputView", "onWindowShown"}) {
      for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
        boolean done = false;
        for (Method mm : k.getDeclaredMethods()) {
          if (mm.getName().equals(m)) { XposedBridge.hookMethod(mm, h); done = true; }
        }
        if (done) { log("hook installed: " + k.getName() + "." + m); break; }
      }
    }
  }

  static void showBanner(InputMethodService ims) {
    if (bannerShown || !prefs.getBoolean(Prefs.BANNER, true)) return;
    final View decor = ims.getWindow().getWindow().getDecorView();
    bannerShown = true;
    decor.post(() -> {
      try {
        final float d = decor.getResources().getDisplayMetrics().density;
        Drawable dr = new Drawable() {
          final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
          final Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG);
          int a = 255;
          boolean night = (decor.getResources().getConfiguration().uiMode
              & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
          {
            bg.setColor(night ? 0xE03C4043 : 0xE0E8F0FE);
            tx.setColor(night ? 0xFFE8EAED : 0xFF174EA6);
            tx.setTextSize(13 * d);
            tx.setTextAlign(Paint.Align.CENTER);
            tx.setLetterSpacing(0.02f);
          }
          @Override public void draw(Canvas c) {
            Rect b = getBounds();
            String s = "✓ GboardGloss · "
                + LanguageMode.displayName(prefs.getString(Prefs.LANG, "en"));
            float w = tx.measureText(s) + 28 * d, h = 28 * d;
            float cx = b.width() / 2f, y = b.height() - h - 4 * d;
            c.drawRoundRect(cx - w / 2, y, cx + w / 2, y + h, h / 2, h / 2, bg);
            c.drawText(s, cx, y + h / 2 - (tx.ascent() + tx.descent()) / 2, tx);
          }
          @Override public void setAlpha(int x) {
            a = x; bg.setAlpha(x * 224 / 255); tx.setAlpha(x); invalidateSelf();
          }
          @Override public void setColorFilter(ColorFilter f) {}
          @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
        dr.setBounds(0, 0, decor.getWidth(), decor.getHeight());
        decor.getOverlay().add(dr);
        decor.invalidate();
        log("banner shown " + decor.getWidth() + "x" + decor.getHeight());
        android.animation.ObjectAnimator an =
            android.animation.ObjectAnimator.ofInt(dr, "alpha", 255, 0);
        an.setStartDelay(4500);
        an.setDuration(700);
        an.addUpdateListener(x -> decor.invalidate());
        an.addListener(new android.animation.AnimatorListenerAdapter() {
          @Override public void onAnimationEnd(android.animation.Animator x) {
            try { decor.getOverlay().remove(dr); } catch (Throwable ignored) {}
          }
        });
        an.start();
      } catch (Throwable t) { log("banner err " + t); }
    });
  }

  /** True for the leftmost visible SoftKeyView in its row (Gboard's top-ranked candidate). */
  static boolean isFirstCandidate(View v) {
    ViewParent p = v.getParent();
    if (!(p instanceof android.view.ViewGroup)) return false;
    android.view.ViewGroup g = (android.view.ViewGroup) p;
    for (int i = 0; i < g.getChildCount(); i++) {
      View c = g.getChildAt(i);
      if (c.getVisibility() != View.VISIBLE || !c.getClass().getName().endsWith("SoftKeyView")) continue;
      return c == v;
    }
    return false;
  }

  static boolean resolveIsCandidate(View v) {
    Object cached = v.getTag(R.id.gloss_is_candidate);
    if (cached instanceof Boolean) return (Boolean) cached;
    boolean yes = false;
    StringBuilder hier = logBudget > 0 ? new StringBuilder() : null;
    for (ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
      Class<?> k = p.getClass();
      String n = k.getName();
      if (hier != null) {
        if (hier.length() > 0) hier.append(" < ");
        hier.append(k.getSimpleName());
      }
      if (n.startsWith("android.") || n.startsWith("androidx.")) {
        if (n.contains("RecyclerView") || n.contains("ViewPager")) continue;
        break;
      }
      String simple = k.getSimpleName();
      if (containsCand(n) || containsCand(simple)) { yes = true; break; }
      if (n.contains("SoftKeyboardView") && !containsCand(n)) { yes = false; break; }
    }
    v.setTag(R.id.gloss_is_candidate, yes);
    if (hier != null && logBudget > 0) {
      logBudget--;
      log("hier cand=" + yes + " " + hier);
    }
    return yes;
  }

  static boolean containsCand(String n) {
    return n.contains("Candidate") || n.contains("Suggestion")
        || n.contains("CandidatesHolder") || n.contains("candidate");
  }


  /** Prefer CharSequence[] (pre-18.5 field g); else immutable list field (18.5+ field i). */
  static Field findLabelField(Class<?> defCls) {
    Field listField = null;
    for (Field f : defCls.getDeclaredFields()) {
      Class<?> t = f.getType();
      if (t == CharSequence[].class || t == String[].class) {
        f.setAccessible(true);
        return f;
      }
      // Guava/ImmutableList-style containers used by Gboard 18.5+ (defpackage.wns)
      if (!t.isPrimitive() && !t.getName().startsWith("java.lang.")
          && !t.getName().startsWith("android.")
          && !t.isArray()
          && (t.getName().contains("wns") || hasGet0(t))) {
        if (listField == null && !f.getName().equals("h") && !f.getName().equals("k")) {
          // SoftKeyDef: h=actionDefs, i=labels, k=icons — prefer name i when present
          listField = f;
        }
        if (f.getName().equals("i")) {
          f.setAccessible(true);
          return f;
        }
      }
    }
    if (listField != null) {
      listField.setAccessible(true);
      return listField;
    }
    return null;
  }

  static boolean hasGet0(Class<?> t) {
    try {
      Method m = t.getMethod("get", int.class);
      Class<?> r = m.getReturnType();
      return r == Object.class || CharSequence.class.isAssignableFrom(r) || r == String.class;
    } catch (Throwable ignored) {
      return false;
    }
  }

  static Field findSoftKeyDefField(Class<?> softKey) {
    for (Field f : softKey.getDeclaredFields()) {
      if (SOFTKEY_DEF.equals(f.getType().getName())) {
        f.setAccessible(true);
        return f;
      }
    }
    return null;
  }

  /** Hook setSoftKeyDef regardless of ProGuard name (r on 18.4, s on 18.5, plus c(SoftKeyDef)). */
  static int hookSoftKeyBindMethods(Class<?> softKeyCls, ClassLoader cl) {
    Class<?> defCls;
    try { defCls = XposedHelpers.findClass(SOFTKEY_DEF, cl); }
    catch (Throwable t) { log("SoftKeyDef missing " + t); return 0; }
    XC_MethodHook h = new XC_MethodHook() {
      @Override protected void afterHookedMethod(MethodHookParam p) {
        try {
          Object def = null;
          if (p.args != null) {
            for (Object a : p.args) {
              if (a != null && defCls.isInstance(a)) { def = a; break; }
            }
          }
          onSoftKeyBound((View) p.thisObject, def);
        } catch (Throwable t) {
          if (logBudget > 0) { logBudget--; log("bind err " + t); }
        }
      }
    };
    int n = 0;
    for (Method m : softKeyCls.getDeclaredMethods()) {
      Class<?>[] pt = m.getParameterTypes();
      if (pt.length == 0 || pt.length > 2) continue;
      if (!defCls.isAssignableFrom(pt[0])) continue;
      if (pt.length == 2 && pt[1] != long.class && pt[1] != Long.class) continue;
      // skip static helpers like SoftKeyView.r(SoftKeyDef) boolean predicate on 18.5
      if ((m.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) continue;
      try {
        XposedBridge.hookMethod(m, h);
        n++;
        log("hook installed: SoftKeyView." + m.getName() + arity(pt));
      } catch (Throwable t) {
        log("hook fail SoftKeyView." + m.getName() + " " + t);
      }
    }
    return n;
  }

  static String arity(Class<?>[] pt) {
    StringBuilder sb = new StringBuilder("(");
    for (int i = 0; i < pt.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(pt[i].getSimpleName());
    }
    return sb.append(')').toString();
  }

  static String extractLabel(View softKey, Object softKeyDef) {
    try {
      String fromDef = labelFromDef(softKeyDef);
      if (fromDef != null && !fromDef.isEmpty()) return fromDef;
    } catch (Throwable ignored) {}
    if (softKey instanceof ViewGroup) {
      StringBuilder sb = new StringBuilder();
      collectText((ViewGroup) softKey, sb);
      if (sb.length() > 0) return sb.toString().trim();
    }
    CharSequence cd = softKey.getContentDescription();
    return cd == null ? "" : cd.toString().trim();
  }

  static String labelFromDef(Object softKeyDef) throws Exception {
    if (softKeyDef == null || softKeyDefLabels == null) return null;
    Object labels = softKeyDefLabels.get(softKeyDef);
    if (labels == null) return null;
    if (labels instanceof CharSequence[]) {
      CharSequence[] cs = (CharSequence[]) labels;
      if (cs.length > 0 && cs[0] != null) {
        String s = cs[0].toString().trim();
        return s.isEmpty() ? null : s;
      }
      return null;
    }
    // 18.5+: wns / ImmutableList of CharSequence (SoftKeyDef.i)
    try {
      Method isEmpty = labels.getClass().getMethod("isEmpty");
      Object empty = isEmpty.invoke(labels);
      if (empty instanceof Boolean && (Boolean) empty) return null;
    } catch (Throwable ignored) {}
    try {
      Method get = labels.getClass().getMethod("get", int.class);
      Object first = get.invoke(labels, 0);
      if (first != null) {
        String s = first.toString().trim();
        return s.isEmpty() ? null : s;
      }
    } catch (Throwable ignored) {}
    return null;
  }

  static void collectText(View v, StringBuilder sb) {
    if (v.getVisibility() != View.VISIBLE) return;
    if (v instanceof TextView) {
      CharSequence t = ((TextView) v).getText();
      if (t != null && t.length() > 0 && sb.length() == 0) sb.append(t);
      return;
    }
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) collectText(g.getChildAt(i), sb);
    }
  }

  static void onSoftKeyBound(View v, Object softKeyDef) {
    long now = SystemClock.uptimeMillis();
    if (now - lastReload > 2000) {
      lastReload = now;
      prefs.reload();
      reloadGlossary();
    }
    if (!prefs.getBoolean(Prefs.ENABLED, true)) {
      clearGloss(v);
      return;
    }
    if (!resolveIsCandidate(v)) {
      if (v.getTag(R.id.gloss_text) != null
          || v.getTag(R.id.gloss_soft_key_def) != null) clearGloss(v);
      return;
    }
    String word = extractLabel(v, softKeyDef);
    if (word.isEmpty() || word.length() > 24) {
      clearGloss(v);
      return;
    }
    v.setTag(R.id.gloss_soft_key_def, softKeyDef);
    Object prev = v.getTag(R.id.gloss_word);
    if (word.equals(prev)) return;

    v.setTag(R.id.gloss_word, word);
    String src0 = Dictionary.detectPrimary(word);
    String lang = LanguageMode.resolveTarget(prefs.getString(Prefs.LANG, "en"), src0);
    String fullGloss = Dictionary.translate(OPENER, word, lang);
    String gloss = Dictionary.fitGlossToken(fullGloss, 22);
    v.setTag(R.id.gloss_text, gloss);
    v.setTag(R.id.gloss_commit, fullGloss);

    // Candidate #1: neural MT is primary (dictionary gloss above is the instant fallback until MT answers,
    // or if MT is off / unavailable / times out). Candidates #2+: dictionary first; MT only when the
    // dictionary has no confident result (miss, or a stitched phrase).
    boolean first = isFirstCandidate(v);
    boolean needMt = first || gloss == null || Dictionary.wasComposed(word, lang);
    if (needMt && src0 != null && !src0.equals(lang) && prefs.getBoolean(Prefs.MT, true)) {
      String hit = MtClient.peek(word, lang);
      if (hit != null) {
        v.setTag(R.id.gloss_text, Dictionary.fitGlossToken(hit, 40));
        v.setTag(R.id.gloss_commit, hit);
        gloss = hit;
      }
      else MtClient.request(v.getContext(), v, word, src0, lang);
    }

    if (gloss == null && prefs.getBoolean(Prefs.ONLINE, false)) {
      String src = Dictionary.detectPrimary(word);
      if (src != null && !src.equals(lang)) {
        String peek = OnlineTranslate.peek(word, lang);
        if (peek != null) {
          if (!peek.isEmpty()) {
            v.setTag(R.id.gloss_text, Dictionary.fitGlossToken(peek, 22));
            v.setTag(R.id.gloss_commit, peek);
          }
        } else {
          final String expect = word;
          OnlineTranslate.request(word, src, lang, (w, g) -> {
            try {
              if (!expect.equals(v.getTag(R.id.gloss_word))) return;
              if (g != null && !g.isEmpty()) {
                v.setTag(R.id.gloss_text, Dictionary.fitGlossToken(g, 22));
                v.setTag(R.id.gloss_commit, g);
                updateTranslationLongPress(v);
                v.invalidate();
              }
            } catch (Throwable t) { log("online cb " + t); }
          });
        }
      }
    }

    if (logBudget > 0 || prefs.getBoolean(Prefs.DEBUG, false)) {
      if (logBudget > 0) logBudget--;
      log("bind '" + word + "' (" + src0 + "→" + lang + ") → " + v.getTag(R.id.gloss_text));
    }
    Object commit = v.getTag(R.id.gloss_commit);
    if (commit instanceof String && !((String) commit).isEmpty()) {
      stripNativeDeleteAction(softKeyDef);
    }
    updateTranslationLongPress(v);
    v.invalidate();
  }

  static void clearGloss(View v) {
    if (v.getTag(R.id.gloss_text) != null || v.getTag(R.id.gloss_word) != null
        || v.getTag(R.id.gloss_commit) != null
        || v.getTag(R.id.gloss_soft_key_def) != null
        || Boolean.TRUE.equals(v.getTag(R.id.gloss_long_click_ours))) {
      v.setTag(R.id.gloss_word, null);
      v.setTag(R.id.gloss_text, null);
      v.setTag(R.id.gloss_commit, null);
      v.setTag(R.id.gloss_soft_key_def, null);
      updateTranslationLongPress(v);
      CandidateTouchWatcher.clear(v);
      v.invalidate();
    }
  }

  /** Soft secondary label derived from Gboard's themed candidate text. */
  static void drawCachedGloss(View v, Canvas c) {
    Object g = v.getTag(R.id.gloss_text);
    if (!(g instanceof String)) return;
    String gloss = (String) g;
    if (gloss.isEmpty()) return;

    float d = v.getResources().getDisplayMetrics().density;
    float sd = v.getResources().getDisplayMetrics().scaledDensity;
    int h = v.getHeight();
    // Too short to fit a secondary line without crowding the candidate glyph
    if (h < 22 * d) return;

    int size = prefs.getInt(Prefs.SIZE, 10);
    if (size < 8) size = 8;
    if (size > 14) size = 14;
    // Secondary label slightly smaller than the slider suggests
    float textPx = size * sd * 0.92f;
    // Shrink further on short keys
    if (h < 32 * d) textPx = Math.min(textPx, 9f * sd);

    if (!paintInit || size != lastSize || d != lastDensity) {
      paintInit = true;
      lastSize = size;
      lastDensity = d;
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
      paint.setLetterSpacing(0.04f);
      paint.setFakeBoldText(false);
    }
    int primary = CandidatePopup.candidateTextColor(v);
    paint.setColor(CandidatePopup.secondaryTextColor(primary));
    paint.setTextSize(textPx);

    float maxW = Math.max(8f, v.getWidth() - 6f * d);
    String draw = gloss;
    if (paint.measureText(draw) > maxW) {
      draw = fitToWidth(draw, paint, maxW);
      if (draw == null || draw.isEmpty()) return;
    }

    float x = v.getWidth() / 2f;
    float y = h - paint.descent() - 1.5f * d;
    // Keep above bottom padding; if it would sit on the glyph midline, skip
    float top = y + paint.ascent();
    if (top < h * 0.42f) {
      // Try one size smaller
      paint.setTextSize(textPx * 0.85f);
      y = h - paint.descent() - 1.2f * d;
      top = y + paint.ascent();
      if (top < h * 0.38f) return;
    }
    c.drawText(draw, x, y, paint);
  }

  /** Ellipsis only after a complete token; otherwise drop the gloss. */
  static String fitToWidth(String g, Paint p, float maxW) {
    if (p.measureText(g) <= maxW) return g;
    String[] parts = g.split(" (?=\\S)|(?<=·) ");
    if (parts.length == 1) {
      // Single token too long: shrink not mid-ellipsis
      float lo = p.getTextSize() * 0.7f, hi = p.getTextSize();
      float saved = p.getTextSize();
      for (int i = 0; i < 6; i++) {
        float mid = (lo + hi) / 2f;
        p.setTextSize(mid);
        if (p.measureText(g) <= maxW) lo = mid; else hi = mid;
      }
      p.setTextSize(lo);
      if (p.measureText(g) <= maxW) return g;
      p.setTextSize(saved);
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      String trial = i == 0 ? parts[0] : sb + " " + parts[i];
      String withEll = trial + "…";
      if (p.measureText(withEll) > maxW) {
        String base = sb.toString().trim();
        if (base.isEmpty()) return null;
        if (base.endsWith("·")) base = base.substring(0, base.length() - 1).trim();
        return base + "…";
      }
      if (i == 0) sb.append(parts[0]);
      else sb.append(' ').append(parts[i]);
    }
    return sb.toString();
  }
}
