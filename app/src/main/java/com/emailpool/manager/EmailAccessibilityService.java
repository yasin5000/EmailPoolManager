package com.emailpool.manager;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Lets the floating PASTE button insert text into whichever editable field has input focus
 * in another app. Android offers no other way to do this.
 */
public class EmailAccessibilityService extends AccessibilityService {
    public static final int OK = 0, NO_FIELD = 1, FAILED = 2;
    static volatile EmailAccessibilityService instance;

    private AccessibilityNodeInfo lastEditable;

    @Override protected void onServiceConnected() { instance = this; }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        AccessibilityNodeInfo src = e.getSource();
        if (src != null && src.isEditable() && !getPackageName().equals(String.valueOf(src.getPackageName()))) {
            lastEditable = src;   // remember the last field the user focused
        }
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(android.content.Intent i) { instance = null; lastEditable = null; return super.onUnbind(i); }

    @Override public void onDestroy() { instance = null; super.onDestroy(); }

    /** Pastes the clipboard into the focused field; falls back to setting the text directly. */
    int pasteIntoFocused(String fallbackText) {
        AccessibilityNodeInfo target = null;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) target = f;
        }
        if (target == null && lastEditable != null && lastEditable.refresh() && lastEditable.isVisibleToUser()) {
            target = lastEditable;
        }
        if (target == null) return NO_FIELD;

        if (target.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return OK;
        if (fallbackText != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, fallbackText);
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return OK;
        }
        return FAILED;
    }
}
