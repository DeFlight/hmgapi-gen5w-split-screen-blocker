package com.deflight.splitscreenblocker.hooks;

import hmgapi.hook.Hooks;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;

/**
 * Runs in the car's split-screen app (com.mobis.splitscreen.right). Its base class
 * (SplitScreenBase, the class SplitScreenHome is built on) does everything through these methods:
 *   setSplitSeenShow(boolean)        opens or closes the panel
 *   show()                            makes the panel's view visible
 *   setButtonShow(boolean, boolean)  shows or hides the slide-out tab (window SS_Button_Window)
 *   canShowSSOsd(boolean, boolean, boolean)  may a pop-up show in the split-screen area
 * show() and canShowSSOsd() keep their names. The other three are scrambled differently in each
 * software build, so Finder looks them up in the app's own code by the log text only they hold.
 * Each hook is set only if its method is found exactly once; the rest of the app is untouched.
 * On top of that, its two windows are kept hidden by name, the same in every build: the tab
 * (SS_Button_Window) and the pop-ups such as Now Playing (SS_OSD_Window). Hidden like the
 * stock hide, a GONE root view, so the windows take no touches.
 */
public final class Entry extends ModEntry {
    static final String PKG = "com.mobis.splitscreen.right";
    static final String TAB_WINDOW = "SS_Button_Window";
    static final String OSD_WINDOW = "SS_OSD_Window";
    static final int GONE = 8;

    @Override
    public void appLoaded(final ModContext mod, LoadedApp app) throws Throwable {
        if (!PKG.equals(app.packageName) || !PKG.equals(app.processName)) return;
        String apk = app.context != null ? app.context.getApplicationInfo().sourceDir : apkOf(app.classLoader);
        byte[] dex = apk == null ? null : Finder.load(apk);
        if (dex == null) {
            mod.log("couldn't read the split-screen app's code: nothing changed");
            return;
        }
        install(mod, app.classLoader, Finder.find(dex));
    }

    static void install(final ModContext mod, ClassLoader cl, Finder.Found f) throws Throwable {
        if (f.base == null) {
            mod.log("split-screen app not recognised (" + f.why + "): nothing changed");
            return;
        }
        Class<?> c = Class.forName(f.base, false, cl);
        StringBuilder off = new StringBuilder(), missed = new StringBuilder();

        // the panel never opens; closing still runs as stock
        if (has(c, f.open, void.class, boolean.class)) {
            Hooks.findAndHookMethod(f.base, cl, f.open, boolean.class, new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    if (mod.enabled() && Boolean.TRUE.equals(p.args[0])) p.setResult(null);
                }
            });
            off.append("panel");
        } else missed.append(" panel");
        if (has(c, "show", void.class)) {
            Hooks.findAndHookMethod(f.base, cl, "show", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    if (mod.enabled()) p.setResult(null);
                }
            });
        }

        // every "show the tab" becomes the stock "hide the tab"
        if (has(c, f.tab, boolean.class, boolean.class, boolean.class)) {
            Hooks.findAndHookMethod(f.base, cl, f.tab, boolean.class, boolean.class, new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    if (mod.enabled()) p.args[0] = Boolean.FALSE;
                }
            });
            off.append(off.length() > 0 ? ", " : "").append("tab");
        } else missed.append(" tab");
        // the tab and pop-up windows: hidden as they're added, and kept hidden
        Hooks.findAndHookMethod("android.view.WindowManagerImpl", cl, "addView",
                android.view.View.class, android.view.ViewGroup.LayoutParams.class, new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (mod.enabled() && ours(p.args[1])) ((android.view.View) p.args[0]).setVisibility(GONE);
            }
        });
        Hooks.findAndHookMethod("android.view.View", cl, "setVisibility", int.class, new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (mod.enabled() && !Integer.valueOf(GONE).equals(p.args[0])
                        && ours(((android.view.View) p.thisObject).getLayoutParams())) p.args[0] = GONE;
            }
        });

        // the split screen's pop-ups (the side panel over CarPlay and Android Auto)
        if (has(c, "canShowSSOsd", boolean.class, boolean.class, boolean.class, boolean.class)) {
            Hooks.findAndHookMethod(f.base, cl, "canShowSSOsd", boolean.class, boolean.class, boolean.class, new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    if (mod.enabled()) p.setResult(Boolean.FALSE);
                }
            });
            off.append(off.length() > 0 ? ", " : "").append("pop-ups");
        } else missed.append(" pop-ups");

        mod.log((off.length() > 0 ? off + " off" : "nothing changed")
                + (missed.length() > 0 ? "; not found on this car:" + missed : ""));
    }

    /** does class c declare name(params) returning ret; the first type is the return type */
    static boolean has(Class<?> c, String name, Class<?> ret, Class<?>... params) {
        if (name == null) return false;
        try {
            return c.getDeclaredMethod(name, params).getReturnType() == ret;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** is this the tab's or the pop-ups' window */
    static boolean ours(Object lp) {
        if (!(lp instanceof android.view.WindowManager.LayoutParams)) return false;
        CharSequence t = ((android.view.WindowManager.LayoutParams) lp).getTitle();
        return t != null && (TAB_WINDOW.contentEquals(t) || OSD_WINDOW.contentEquals(t));
    }

    /** the apk the split-screen app was loaded from */
    static String apkOf(ClassLoader cl) {
        String s = String.valueOf(cl);
        int i = s.indexOf("/system/");
        if (i >= 0) {
            int j = s.indexOf(".apk", i);
            if (j > 0) return s.substring(i, j + 4);
        }
        java.io.File f = new java.io.File("/system/app/RightScreenHome.apk");
        return f.isFile() ? f.getPath() : null;
    }
}
