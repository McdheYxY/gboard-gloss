package device;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.android.uiautomator.core.UiDevice;
import com.android.uiautomator.core.UiObject;
import com.android.uiautomator.core.UiSelector;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * End-to-end device test for translated-candidate long press.
 *
 * The test switches to the English keyboard, enters "hello", invokes
 * ACTION_LONG_CLICK on the candidate, checks the popup, chooses "译文",
 * and verifies that the translated text was committed.
 */
public class CandidatePopupProbe extends UiAutomatorTestCase {
  private static final String GBOARD_ID = "com.google.android.inputmethod.latin:id/";
  private static final String INPUT_ID =
      "io.github.ziyuw_plank.gboardgloss:id/try_input";
  private static final String NEXT_LANGUAGE_ID =
      GBOARD_ID + "key_pos_switch_to_next_language";
  private static final String SPACE_ID =
      GBOARD_ID + "key_pos_space";

  public void testProbe() throws Exception {
    UiDevice device = getUiDevice();
    UiAutomation automation = automation(device);
    enableInteractiveWindows(automation);
    device.wakeUp();

    AccessibilityNodeInfo scroll = findNode(
        new UiSelector().className("android.widget.ScrollView"));
    assertNotNull("ScrollView", scroll);
    for (int i = 0; i < 4; i++) {
      scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
      sleep(400);
    }

    AccessibilityNodeInfo input = findNode(new UiSelector().resourceId(INPUT_ID));
    assertNotNull("try_input", input);
    assertTrue("focus try_input",
        input.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    sleep(2500);

    Bundle text = new Bundle();
    text.putCharSequence(
        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "hello");
    input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text);
    sleep(2500);

    ensureEnglishKeyboard(automation);
    input = findNode(new UiSelector().resourceId(INPUT_ID));
    assertNotNull("try_input after language switch", input);
    input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text);
    sleep(2500);

    AccessibilityNodeInfo candidate =
        findDescriptionInWindows(automation, "hello");
    assertNotNull("hello candidate", candidate);
    device.takeScreenshot(new File("/sdcard/gboard_candidates.png"));
    assertTrue("long-click candidate",
        candidate.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK));
    sleep(1500);
    device.takeScreenshot(new File("/sdcard/gboard_bubble.png"));
    dumpWindows(automation, "/sdcard/gboard_windows_after_long.txt");

    AccessibilityNodeInfo translated =
        findTextPrefixInWindows(automation, "译文");
    assertNotNull("translated popup choice", translated);
    AccessibilityNodeInfo translatedButton = findClickableAncestor(translated);
    assertNotNull("clickable translated popup choice", translatedButton);
    assertTrue("click translated popup choice",
        translatedButton.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    sleep(1500);

    input = findNode(new UiSelector().resourceId(INPUT_ID));
    assertNotNull("try_input after choice", input);
    assertTrue("translation committed",
        input.getText().toString().contains("你好"));
    device.dumpWindowHierarchy("/sdcard/gboard_probe.xml");
    device.takeScreenshot(new File("/sdcard/gboard_probe.png"));
    dumpWindows(automation, "/sdcard/gboard_windows.txt");

    input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text);
    sleep(2000);
    candidate = findDescriptionInWindows(automation, "hello");
    assertNotNull("hello candidate for physical long-press", candidate);
    Rect bounds = new Rect();
    candidate.getBoundsInScreen(bounds);
    assertTrue("inject physical long-press", device.swipe(
        bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY(), 180));
    sleep(1500);
    input = findIdInWindows(automation, INPUT_ID);
    assertNotNull("try_input after physical long-press", input);
    assertTrue("physical long-press committed translation",
        input.getText().toString().contains("你好"));

    input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text);
    sleep(2000);
    candidate = findDescriptionInWindows(automation, "hello");
    assertNotNull("hello candidate for close gesture", candidate);
    candidate.getBoundsInScreen(bounds);
    assertTrue("inject close gesture", device.swipe(
        bounds.centerX(), bounds.centerY(),
        bounds.centerX(), bounds.centerY() + 480, 100));
    sleep(2000);
    assertNull("translation bubble closed",
        findTextPrefixInWindows(automation, "译文"));
  }

  private static void enableInteractiveWindows(UiAutomation automation) {
    AccessibilityServiceInfo info = automation.getServiceInfo();
    info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
    info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
    automation.setServiceInfo(info);
  }

  private void ensureEnglishKeyboard(UiAutomation automation) {
    for (int i = 0; i < 2; i++) {
      AccessibilityNodeInfo space = findIdInWindows(automation, SPACE_ID);
      CharSequence description = space == null ? null : space.getContentDescription();
      if (description != null && "Space".contentEquals(description)) return;

      AccessibilityNodeInfo nextLanguage =
          findIdInWindows(automation, NEXT_LANGUAGE_ID);
      assertNotNull("next language key", nextLanguage);
      assertTrue("switch language",
          nextLanguage.performAction(AccessibilityNodeInfo.ACTION_CLICK));
      sleep(2500);
    }
    fail("English keyboard not selected");
  }

  private static UiAutomation automation(UiDevice device) throws Exception {
    Field bridge = UiDevice.class.getDeclaredField("mUiAutomationBridge");
    bridge.setAccessible(true);
    Object value = bridge.get(device);
    for (Class<?> c = value.getClass(); c != null; c = c.getSuperclass()) {
      try {
        Field automation = c.getDeclaredField("mUiAutomation");
        automation.setAccessible(true);
        return (UiAutomation) automation.get(value);
      } catch (NoSuchFieldException ignored) {
        // The field is declared on UiAutomatorBridge, not ShellUiAutomatorBridge.
      }
    }
    throw new NoSuchFieldException("mUiAutomation");
  }

  private static AccessibilityNodeInfo findNode(UiSelector selector) throws Exception {
    UiObject object = new UiObject(selector);
    Method method =
        UiObject.class.getDeclaredMethod("findAccessibilityNodeInfo", long.class);
    method.setAccessible(true);
    return (AccessibilityNodeInfo) method.invoke(object, 0L);
  }

  private static AccessibilityNodeInfo findIdInWindows(
      UiAutomation automation, String id) {
    for (AccessibilityWindowInfo window : automation.getWindows()) {
      AccessibilityNodeInfo found = findId(window.getRoot(), id);
      if (found != null) return found;
    }
    return null;
  }

  private static AccessibilityNodeInfo findId(
      AccessibilityNodeInfo root, String id) {
    if (root == null) return null;
    if (id.equals(root.getViewIdResourceName())) return root;
    for (int i = 0; i < root.getChildCount(); i++) {
      AccessibilityNodeInfo found = findId(root.getChild(i), id);
      if (found != null) return found;
    }
    return null;
  }

  private static AccessibilityNodeInfo findDescriptionInWindows(
      UiAutomation automation, String description) {
    for (AccessibilityWindowInfo window : automation.getWindows()) {
      AccessibilityNodeInfo found = findDescription(window.getRoot(), description);
      if (found != null) return found;
    }
    return null;
  }

  private static AccessibilityNodeInfo findDescription(
      AccessibilityNodeInfo root, String description) {
    if (root == null) return null;
    CharSequence value = root.getContentDescription();
    if (value != null && description.contentEquals(value)) return root;
    for (int i = 0; i < root.getChildCount(); i++) {
      AccessibilityNodeInfo found = findDescription(root.getChild(i), description);
      if (found != null) return found;
    }
    return null;
  }

  private static AccessibilityNodeInfo findTextPrefixInWindows(
      UiAutomation automation, String prefix) {
    for (AccessibilityWindowInfo window : automation.getWindows()) {
      AccessibilityNodeInfo found = findTextPrefix(window.getRoot(), prefix);
      if (found != null) return found;
    }
    return null;
  }

  private static AccessibilityNodeInfo findTextPrefix(
      AccessibilityNodeInfo root, String prefix) {
    if (root == null) return null;
    CharSequence value = root.getText();
    if (value != null && value.toString().startsWith(prefix)) return root;
    for (int i = 0; i < root.getChildCount(); i++) {
      AccessibilityNodeInfo found = findTextPrefix(root.getChild(i), prefix);
      if (found != null) return found;
    }
    return null;
  }

  private static AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
    for (AccessibilityNodeInfo current = node; current != null; current = current.getParent()) {
      if (current.isClickable()) return current;
    }
    return null;
  }

  private static void dumpWindows(UiAutomation automation, String path)
      throws Exception {
    StringBuilder out = new StringBuilder();
    List<AccessibilityWindowInfo> windows = automation.getWindows();
    for (AccessibilityWindowInfo window : windows) {
      out.append("WINDOW type=").append(window.getType()).append('\n');
      appendNode(out, window.getRoot(), 0);
    }
    try (FileOutputStream stream = new FileOutputStream(path)) {
      stream.write(out.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  private static void appendNode(
      StringBuilder out, AccessibilityNodeInfo node, int depth) {
    if (node == null) return;
    Rect bounds = new Rect();
    node.getBoundsInScreen(bounds);
    for (int i = 0; i < depth; i++) out.append("  ");
    out.append(node.getClassName())
        .append(" id=").append(node.getViewIdResourceName())
        .append(" text=").append(node.getText())
        .append(" desc=").append(node.getContentDescription())
        .append(" click=").append(node.isClickable())
        .append(" long=").append(node.isLongClickable())
        .append(" bounds=").append(bounds)
        .append('\n');
    for (int i = 0; i < node.getChildCount(); i++) {
      appendNode(out, node.getChild(i), depth + 1);
    }
  }
}
