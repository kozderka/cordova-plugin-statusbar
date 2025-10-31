/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.cordova.statusbar;

import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import android.webkit.WebView;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONException;

public class StatusBar extends CordovaPlugin {
    private static final String TAG = "StatusBar";

    private static final String ACTION_HIDE = "hide";
    private static final String ACTION_SHOW = "show";
    private static final String ACTION_READY = "_ready";
    private static final String ACTION_BACKGROUND_COLOR_BY_HEX_STRING = "backgroundColorByHexString";
    private static final String ACTION_OVERLAYS_WEB_VIEW = "overlaysWebView";
    private static final String ACTION_STYLE_DEFAULT = "styleDefault";
    private static final String ACTION_STYLE_LIGHT_CONTENT = "styleLightContent";

    private static final String STYLE_DEFAULT = "default";
    private static final String STYLE_LIGHT_CONTENT = "lightcontent";

    // When true, prevent any overlay behavior on Android (status bar will never overlay the WebView)
    private static final boolean FORCE_DISABLE_OVERLAY = true;

    private AppCompatActivity activity;
    private Window window;

    /**
     * Sets the context of the Command. This can then be used to do things like
     * get file paths associated with the Activity.
     *
     * @param cordova The context of the main Activity.
     * @param webView The CordovaWebView Cordova is running in.
     */
    @Override
    public void initialize(final CordovaInterface cordova, CordovaWebView webView) {
        LOG.v(TAG, "StatusBar: initialization");
        super.initialize(cordova, webView);

        activity = this.cordova.getActivity();
        window = activity.getWindow();

        activity.runOnUiThread(() -> {
            // Read 'StatusBarOverlaysWebView' from config.xml, default is false.
            boolean overlaysWebView = preferences.getBoolean("StatusBarOverlaysWebView", false);

            // If plugin is configured to force-disable overlay, override any preference
            if (FORCE_DISABLE_OVERLAY) {
                overlaysWebView = false;
            }

            setStatusBarTransparent(overlaysWebView);

            // Read 'StatusBarStyle' from config.xml, default is 'lightcontent'.
            setStatusBarStyle(
                preferences.getString("StatusBarStyle", STYLE_LIGHT_CONTENT).toLowerCase()
            );

            // Try to ensure the activity content view participates in window insets
            ensureRootFitsSystemWindows();
            // If running on Android 14 (API 34) or lower, clear any existing paddings/margins
            // to avoid stale offsets (user requested no paddings for <= Android 14)
            if (Build.VERSION.SDK_INT <= 34) {
                LOG.d(TAG, "SDK " + Build.VERSION.SDK_INT + " detected -> clearing WebView padding/margins per request");
                try {
                    clearWebViewPadding();
                    if (webView != null && webView.getView() != null) {
                        clearParentBottomMargin(webView.getView());
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action            The action to execute.
     * @param args              JSONArry of arguments for the plugin.
     * @param callbackContext   The callback id used when calling back into JavaScript.
     * @return                  True if the action was valid, false otherwise.
     */
    @Override
    public boolean execute(final String action, final CordovaArgs args, final CallbackContext callbackContext) {
        LOG.v(TAG, "Executing action: " + action);

        switch (action) {
            case ACTION_READY:
                boolean statusBarVisible = (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) == 0;
                callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, statusBarVisible));
                return true;

            case ACTION_SHOW:
                activity.runOnUiThread(() -> {
                    int uiOptions = window.getDecorView().getSystemUiVisibility();
                    uiOptions &= ~View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                    uiOptions &= ~View.SYSTEM_UI_FLAG_FULLSCREEN;

                    window.getDecorView().setSystemUiVisibility(uiOptions);

                    // CB-11197 We still need to update LayoutParams to force status bar
                    // to be hidden when entering e.g. text fields
                    LOG.d(TAG, "Non-overlay mode applied: decorFitsSystemWindows=true, navigationBarColor=#000000");
                    // Re-ensure root fits system windows in case OEM reset it
                    ensureRootFitsSystemWindows();
                    window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                });
                return true;

            case ACTION_HIDE:
                activity.runOnUiThread(() -> {
                    int uiOptions = window.getDecorView().getSystemUiVisibility()
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN;

                    window.getDecorView().setSystemUiVisibility(uiOptions);

                    // CB-11197 We still need to update LayoutParams to force status bar
                    // to be hidden when entering e.g. text fields
                    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                });
                return true;

            case ACTION_BACKGROUND_COLOR_BY_HEX_STRING:
                activity.runOnUiThread(() -> {
                    // No-op: runtime color changes have been removed. This action is kept for compatibility but does nothing.
                    try {
                        args.getString(0); // consume argument to validate format
                        callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK));
                    } catch (JSONException ignore) {
                        LOG.e(TAG, "Invalid hexString argument, use f.i. '#777777'");
                        callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
                    }
                });
                return true;

            case ACTION_OVERLAYS_WEB_VIEW:
                activity.runOnUiThread(() -> {
                    try {
                        boolean requested = args.getBoolean(0);

                        if (FORCE_DISABLE_OVERLAY) {
                            // Ignore requests to enable overlay if forced off
                            if (requested) {
                                LOG.w(TAG, "Overlay request ignored because FORCE_DISABLE_OVERLAY=true");
                            }
                            setStatusBarTransparent(false);
                        } else {
                            setStatusBarTransparent(requested);
                        }
                    } catch (JSONException ignore) {
                        LOG.e(TAG, "Invalid boolean argument");
                    }
                });
                return true;

            case ACTION_STYLE_DEFAULT:
                activity.runOnUiThread(() -> setStatusBarStyle(STYLE_DEFAULT));
                return true;

            case ACTION_STYLE_LIGHT_CONTENT:
                activity.runOnUiThread(() -> setStatusBarStyle(STYLE_LIGHT_CONTENT));
                return true;

            default:
                return false;
        }
    }

    // Color-setting methods removed per request: plugin no longer modifies status/navigation bar colors.

    private void setStatusBarTransparent(final boolean isTransparent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        
        final Window window = cordova.getActivity().getWindow();
        final View decorView = window.getDecorView();
        
        if (isTransparent) {
            // Transparent/overlay mode: status bar overlays content
            window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            // Let content lay out behind system bars
            WindowCompat.setDecorFitsSystemWindows(window, false);
            decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | 
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
            
            window.setStatusBarColor(Color.TRANSPARENT);
            
            // Remove any padding from WebView in overlay mode
            clearWebViewPadding();
        } else {
            // Non-overlay mode: content starts below status bar
            // Let the system apply window insets so content is laid out below the status bar
            WindowCompat.setDecorFitsSystemWindows(window, true);
            window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            // Ensure navigation bar is not translucent and is drawn behind an opaque bar
            try {
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
                // Clear translucent navigation flag; navigation bar color will be applied from preferences later
            } catch (Exception ignored) {}
            
            // Remove layout flags that allow content under system bars; ensure visible system UI
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            
                    // Apply configured background color (fallback to #2b9441) instead of hard-coded black
                // Color application removed. The plugin will not change status/navigation bar colors.

            // Force WebView to respect system insets
            adjustWebViewForStatusBar();
            LOG.d(TAG, "Non-overlay mode applied: decorFitsSystemWindows=true, navigationBarColor=#000000");
        }
    }
    
    private void adjustWebViewForStatusBar() {
        activity.runOnUiThread(() -> {
            // Adjust the Cordova WebView view directly so the app content is moved below the status bar
            if (webView != null && webView.getView() != null) {
                View webViewView = webView.getView();
                int statusBarHeight = getStatusBarHeight();
                int navigationBarHeight = getNavigationBarHeight();

                android.view.ViewGroup.LayoutParams lp = webViewView.getLayoutParams();
                if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams mlp = (android.view.ViewGroup.MarginLayoutParams) lp;
                    boolean changed = false;
                    if (mlp.topMargin != statusBarHeight) {
                        mlp.topMargin = statusBarHeight;
                        changed = true;
                    }
                    if (changed) {
                        webViewView.setLayoutParams(mlp);
                    }
                    // Apply bottom padding to keep content above navigation bar
                    if (navigationBarHeight == 0) {
                        navigationBarHeight = dpToPx(48); // Fallback to 48dp if navigation bar height is missing
                    }
                    // Do not set paddings on Android 14 (API 34) and lower per user request — only use margins/insets there.
                    if (Build.VERSION.SDK_INT > 34) {
                        webViewView.setPadding(webViewView.getPaddingLeft(), webViewView.getPaddingTop(), webViewView.getPaddingRight(), navigationBarHeight);
                    } else {
                        LOG.d(TAG, "Skipping WebView padding apply on SDK " + Build.VERSION.SDK_INT + " (<=34)");
                    }
                    webViewView.requestApplyInsets();
                } else {
                    // Fallback to padding if margin params aren't available
                    if (Build.VERSION.SDK_INT > 34) {
                        webViewView.setPadding(
                            webViewView.getPaddingLeft(),
                            statusBarHeight,
                            webViewView.getPaddingRight(),
                            navigationBarHeight
                        );
                    } else {
                        LOG.d(TAG, "Skipping fallback WebView padding on SDK " + Build.VERSION.SDK_INT + " (<=34)");
                    }
                    webViewView.requestApplyInsets();
                }
                // Also install a WindowInsets listener to handle OEM/system changes (Android 15 fixes)
                applyWindowInsetsListenerToWebView(webViewView);
                // If fallback bottom padding applied, also set it as a parent margin so fixed-position elements
                // inside the WebView are less likely to be covered by nav buttons.
                if (navigationBarHeight > 0) {
                    if (Build.VERSION.SDK_INT > 34) {
                        setParentBottomMargin(webViewView, navigationBarHeight);
                    } else {
                        LOG.d(TAG, "Skipping setParentBottomMargin on SDK " + Build.VERSION.SDK_INT + " (<=34)");
                    }
                }
            }
        });
    }
    
    private void clearWebViewPadding() {
        activity.runOnUiThread(() -> {
            if (webView != null && webView.getView() != null) {
                View webViewView = webView.getView();

                android.view.ViewGroup.LayoutParams lp = webViewView.getLayoutParams();
                if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams mlp = (android.view.ViewGroup.MarginLayoutParams) lp;
                    if (mlp.topMargin != 0) {
                        mlp.topMargin = 0;
                        webViewView.setLayoutParams(mlp);
                        webViewView.requestApplyInsets();
                    }
                } else {
                    // Fallback: clear padding (top + bottom)
                    webViewView.setPadding(
                        webViewView.getPaddingLeft(),
                        0,
                        webViewView.getPaddingRight(),
                        0
                    );
                    webViewView.requestApplyInsets();
                }
                // Ensure insets listener is present so changes to system bars will be handled
                applyWindowInsetsListenerToWebView(webViewView);
                // Clear any parent bottom margin we may have applied as a fallback
                clearParentBottomMargin(webViewView);
            }
        });
    }

    // No JS injection fallback: prefer native window insets and view padding/margins only.

    private void applyWindowInsetsListenerToWebView(final View webViewView) {
        // Use ViewCompat to set a listener that applies system bar insets to the WebView top margin
        ViewCompat.setOnApplyWindowInsetsListener(webViewView, (v, insets) -> {
            try {
                Insets sysInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                int top = sysInsets.top;
                int bottom = sysInsets.bottom;

                // Robust gesture detection: check navigationBars visibility and systemGestures inset size.
                boolean gesturesDetected = false;
                try {
                    boolean navVisible = insets.isVisible(WindowInsetsCompat.Type.navigationBars());
                    if (!navVisible) {
                        gesturesDetected = true;
                    } else {
                        try {
                            Insets gestureInsets = insets.getInsets(WindowInsetsCompat.Type.systemGestures());
                            int gestureBottom = gestureInsets.bottom;
                            int gestureThreshold = dpToPx(20);
                            if (gestureBottom > 0 && gestureBottom <= gestureThreshold) {
                                gesturesDetected = true;
                                LOG.d(TAG, "Detected small systemGestures.bottom=" + gestureBottom + " <= " + gestureThreshold + " -> treat as gestures");
                            }
                        } catch (Exception ignoredGest) {
                            // ignore
                        }
                    }
                } catch (Exception ignored) {
                    // Fallback: check system UI flags
                    try {
                        View decor = activity.getWindow().getDecorView();
                        int sysUi = decor.getSystemUiVisibility();
                        boolean navHidden = (sysUi & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0;
                        if (navHidden) gesturesDetected = true;
                    } catch (Exception ignored2) {}
                }

                if (gesturesDetected) {
                    bottom = 0;
                    try { clearParentBottomMargin(v); } catch (Exception ignored) {}
                    LOG.d(TAG, "Gesture navigation detected -> bottom padding set to 0");
                } else if (bottom == 0) {
                    try {
                        bottom = dpToPx(48);
                        LOG.d(TAG, "Using fallback bottom inset=" + bottom + " because reported bottom was 0 and nav appears visible");
                    } catch (Exception ignored) {}
                }

                android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams mlp = (android.view.ViewGroup.MarginLayoutParams) lp;
                    boolean changed = false;
                    if (mlp.topMargin != top) {
                        mlp.topMargin = top;
                        changed = true;
                        LOG.d(TAG, "Applied window inset top=" + top);
                    }
                    if (changed) {
                        v.setLayoutParams(mlp);
                    }
                    // Apply bottom padding to keep content above navigation bar (only for Android > 14)
                    if (Build.VERSION.SDK_INT > 34) {
                        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
                        LOG.d(TAG, "Applied padding bottom inset=" + bottom);
                    } else {
                        LOG.d(TAG, "Skipping padding apply from insets listener on SDK " + Build.VERSION.SDK_INT + " (<=34)");
                    }
                } else {
                    if (Build.VERSION.SDK_INT > 34) {
                        v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), bottom);
                        LOG.d(TAG, "Applied padding insets top=" + top + " bottom=" + bottom + "; sysUi=" + v.getSystemUiVisibility() + " windowFlags=" + window.getAttributes().flags + " navColor=#" + Integer.toHexString(window.getNavigationBarColor()));
                    } else {
                        LOG.d(TAG, "Skipping non-margin padding from insets on SDK " + Build.VERSION.SDK_INT + " (<=34)");
                    }
                }
            } catch (Exception e) {
                LOG.e(TAG, "Error applying window insets", e);
            }
            return insets;
        });
        // Request insets once to immediately apply
        webViewView.requestApplyInsets();
    }
    
    private int getStatusBarHeight() {
        int resourceId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return activity.getResources().getDimensionPixelSize(resourceId);
        }
        // Fallback to default status bar height (24dp converted to pixels)
        return (int) (24 * activity.getResources().getDisplayMetrics().density);
    }

    private int getNavigationBarHeight() {
        int resourceId = activity.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return activity.getResources().getDimensionPixelSize(resourceId);
        }
        return 0;
    }

    private int dpToPx(int dp) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void ensureRootFitsSystemWindows() {
        try {
            View root = activity.findViewById(android.R.id.content);
            if (root != null) {
                // Request that the root view fit system windows so insets get dispatched
                root.setFitsSystemWindows(true);
                ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                    try {
                        Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                        LOG.d(TAG, "Root insets: top=" + sys.top + " bottom=" + sys.bottom + " sysUi=" + v.getSystemUiVisibility());
                        // If bottom inset is zero but navigation bar appears visible, apply a safe bottom padding
                        if (sys.bottom == 0) {
                            try {
                                View decor = activity.getWindow().getDecorView();
                                int sysUi = decor.getSystemUiVisibility();
                                boolean navHidden = (sysUi & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0;
                                if (!navHidden) {
                                    // Only apply fallback root padding on Android > 14 (API 34)
                                    if (Build.VERSION.SDK_INT > 34) {
                                        int fallback = dpToPx(48);
                                        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), fallback);
                                        LOG.d(TAG, "Applied fallback root bottom padding=" + fallback + " because sys bottom inset was 0 and nav appears visible");
                                    } else {
                                        LOG.d(TAG, "Skipping fallback root bottom padding on SDK " + Build.VERSION.SDK_INT + " (<=34)");
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception e) {
                        LOG.d(TAG, "Root insets read failed: " + e.getMessage());
                    }
                    return insets;
                });
                root.requestApplyInsets();
            }
        } catch (Exception e) {
            LOG.w(TAG, "ensureRootFitsSystemWindows failed", e);
        }
    }

    private void setParentBottomMargin(View v, int bottomPx) {
        try {
            android.view.ViewParent parent = v.getParent();
            if (parent instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) parent;
                android.view.ViewGroup.LayoutParams lp = vg.getLayoutParams();
                if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams mlp = (android.view.ViewGroup.MarginLayoutParams) lp;
                    if (mlp.bottomMargin != bottomPx) {
                        mlp.bottomMargin = bottomPx;
                        vg.setLayoutParams(mlp);
                        LOG.d(TAG, "Set parent bottom margin=" + bottomPx);
                    }
                }
            }
        } catch (Exception e) {
            LOG.w(TAG, "Failed to set parent bottom margin", e);
        }
    }

    private void clearParentBottomMargin(View v) {
        try {
            android.view.ViewParent parent = v.getParent();
            if (parent instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) parent;
                android.view.ViewGroup.LayoutParams lp = vg.getLayoutParams();
                if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams mlp = (android.view.ViewGroup.MarginLayoutParams) lp;
                    if (mlp.bottomMargin != 0) {
                        mlp.bottomMargin = 0;
                        vg.setLayoutParams(mlp);
                        LOG.d(TAG, "Cleared parent bottom margin");
                    }
                }
            }
        } catch (Exception e) {
            LOG.w(TAG, "Failed to clear parent bottom margin", e);
        }
    }

    private void setStatusBarStyle(final String style) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !style.isEmpty()) {
            View decorView = window.getDecorView();
            WindowInsetsControllerCompat windowInsetsControllerCompat = WindowCompat.getInsetsController(window, decorView);

            if (style.equals(STYLE_DEFAULT)) {
                windowInsetsControllerCompat.setAppearanceLightStatusBars(true);
            } else if (style.equals(STYLE_LIGHT_CONTENT)) {
                windowInsetsControllerCompat.setAppearanceLightStatusBars(false);
            } else {
                LOG.e(TAG, "Invalid style, must be either 'default' or 'lightcontent'");
            }
        }
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        // Re-apply status and navigation bar colors and ensure insets on resume — helps OEMs that reset these.
        activity.runOnUiThread(() -> {
            ensureRootFitsSystemWindows();
            adjustWebViewForStatusBar();
        });
    }
}
