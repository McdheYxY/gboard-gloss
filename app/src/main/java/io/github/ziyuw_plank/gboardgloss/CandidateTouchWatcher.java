package io.github.ziyuw_plank.gboardgloss;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.lang.ref.WeakReference;

/** Adds a long-press detector without replacing Gboard's candidate touch listener. */
final class CandidateTouchWatcher {
  private static WeakReference<View> activeCandidate = new WeakReference<>(null);
  private static float downRawX;
  private static float downRawY;
  private static long lastGlobalEventTime = -1;
  private static int lastGlobalAction = -1;

  private CandidateTouchWatcher() {}

  static boolean handle(View candidate, MotionEvent event) {
    if (candidate == null) return false;
    int action = event.getActionMasked();
    Object previousTime = candidate.getTag(R.id.gloss_touch_event_time);
    Object previousAction = candidate.getTag(R.id.gloss_touch_event_action);
    if ((previousTime instanceof Long) && (previousAction instanceof Integer)
        && (Long) previousTime == event.getEventTime()
        && (Integer) previousAction == action) {
      return Boolean.TRUE.equals(candidate.getTag(R.id.gloss_long_press_fired));
    }
    candidate.setTag(R.id.gloss_touch_event_time, event.getEventTime());
    candidate.setTag(R.id.gloss_touch_event_action, action);
    if (action == MotionEvent.ACTION_DOWN) {
      cancel(candidate);
      activeCandidate = new WeakReference<>(candidate);
      downRawX = event.getRawX();
      downRawY = event.getRawY();
      candidate.setTag(R.id.gloss_touch_down_x, event.getX());
      candidate.setTag(R.id.gloss_touch_down_y, event.getY());
      candidate.setTag(R.id.gloss_long_press_fired, null);
      CandidatePopup.prepareTheme(candidate);
      Runnable task = () -> {
        if (Hook.showTranslationChoices(candidate)) {
          candidate.setTag(R.id.gloss_long_press_fired, true);
          candidate.cancelLongPress();
          candidate.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
      };
      candidate.setTag(R.id.gloss_long_press_task, task);
      int timeout = Math.max(250, ViewConfiguration.getLongPressTimeout() - 150);
      candidate.postDelayed(task, timeout);
    } else if (action == MotionEvent.ACTION_MOVE) {
      if (Boolean.TRUE.equals(candidate.getTag(R.id.gloss_long_press_fired))) {
        CandidatePopup.updateDrag(candidate, deltaY(candidate, event));
        return true;
      }
      if (movedTooFar(candidate, event)) cancel(candidate);
    } else if (action == MotionEvent.ACTION_UP) {
      boolean fired = Boolean.TRUE.equals(candidate.getTag(R.id.gloss_long_press_fired));
      cancel(candidate);
      if (fired) {
        CandidatePopup.finishDrag(candidate, deltaY(candidate, event));
        resetTouch(candidate);
        return true;
      }
    } else if (action == MotionEvent.ACTION_CANCEL) {
      boolean fired = Boolean.TRUE.equals(candidate.getTag(R.id.gloss_long_press_fired));
      cancel(candidate);
      if (fired) CandidatePopup.dismissFor(candidate);
      resetTouch(candidate);
      return fired;
    }
    return Boolean.TRUE.equals(candidate.getTag(R.id.gloss_long_press_fired));
  }

  static void clear(View candidate) {
    cancel(candidate);
    CandidatePopup.dismissFor(candidate);
    resetTouch(candidate);
    candidate.setTag(R.id.gloss_popup_open, null);
  }

  /** Observe the whole IME window because Gboard may change touch targets after ACTION_DOWN. */
  static boolean observeGlobal(MotionEvent event) {
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN) return false;
    if (event.getEventTime() == lastGlobalEventTime && action == lastGlobalAction) {
      return false;
    }
    lastGlobalEventTime = event.getEventTime();
    lastGlobalAction = action;
    View candidate = activeCandidate.get();
    if (candidate == null) return false;
    boolean fired = Boolean.TRUE.equals(candidate.getTag(R.id.gloss_long_press_fired));
    float dx = event.getRawX() - downRawX;
    float dy = event.getRawY() - downRawY;
    if (action == MotionEvent.ACTION_MOVE) {
      if (fired) {
        CandidatePopup.updateDrag(candidate, dy);
        return true;
      } else {
        int slop = ViewConfiguration.get(candidate.getContext()).getScaledTouchSlop();
        if (dx * dx + dy * dy > slop * slop) {
          cancel(candidate);
          activeCandidate = new WeakReference<>(null);
        }
      }
      return false;
    } else if (action == MotionEvent.ACTION_UP) {
      if (fired) CandidatePopup.finishDrag(candidate, dy);
      cancel(candidate);
      resetTouch(candidate);
      activeCandidate = new WeakReference<>(null);
      return fired;
    } else if (action == MotionEvent.ACTION_CANCEL) {
      cancel(candidate);
      if (fired) CandidatePopup.dismissFor(candidate);
      resetTouch(candidate);
      activeCandidate = new WeakReference<>(null);
      return fired;
    }
    return false;
  }

  private static boolean movedTooFar(View candidate, MotionEvent event) {
    float dx = deltaX(candidate, event);
    float dy = deltaY(candidate, event);
    int slop = ViewConfiguration.get(candidate.getContext()).getScaledTouchSlop();
    return dx * dx + dy * dy > slop * slop;
  }

  private static float deltaX(View candidate, MotionEvent event) {
    Object x = candidate.getTag(R.id.gloss_touch_down_x);
    return x instanceof Float ? event.getX() - (Float) x : 0f;
  }

  private static float deltaY(View candidate, MotionEvent event) {
    Object y = candidate.getTag(R.id.gloss_touch_down_y);
    return y instanceof Float ? event.getY() - (Float) y : 0f;
  }

  private static void resetTouch(View candidate) {
    candidate.setTag(R.id.gloss_long_press_fired, null);
    candidate.setTag(R.id.gloss_touch_down_x, null);
    candidate.setTag(R.id.gloss_touch_down_y, null);
    candidate.setTag(R.id.gloss_touch_event_time, null);
    candidate.setTag(R.id.gloss_touch_event_action, null);
  }

  private static void cancel(View candidate) {
    Object task = candidate.getTag(R.id.gloss_long_press_task);
    if (task instanceof Runnable) candidate.removeCallbacks((Runnable) task);
    candidate.setTag(R.id.gloss_long_press_task, null);
  }
}
