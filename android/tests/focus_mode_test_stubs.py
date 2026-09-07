#!/usr/bin/env python3
"""Generate minimal Android API adapters for JVM tests of the REAL FocusMode.

Only Android-facing dependencies are substituted. FocusMode, FocusSessionCore,
TodoState, TodoStateCore and AppPrefs are compiled from production sources.
This is not Android instrumentation and does not claim real disk/process testing.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1])
sources = {
'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {
    String getString(String key, String def);
    int getInt(String key, int def);
    boolean contains(String key);
    Editor edit();
    interface Editor {
        Editor putString(String key, String value);
        Editor putInt(String key, int value);
        Editor remove(String key);
        Editor clear();
        boolean commit();
        void apply();
    }
}
''',
'android/content/Context.java': '''package android.content;
import android.content.pm.PackageManager;
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
    public Context getApplicationContext() { return this; }
    public PackageManager getPackageManager() { return new PackageManager(); }
    public void startActivity(Intent intent) {
        if (Thread.holdsLock(dev.linjian.peek.FocusMode.class))
            throw new AssertionError("Activity launch under Focus state lock");
    }
}
''',
'android/content/Intent.java': '''package android.content;
public class Intent {
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
    public static final int FLAG_ACTIVITY_REORDER_TO_FRONT = 0x00020000;
    public static final int FLAG_ACTIVITY_NO_ANIMATION = 0x00010000;
    public Intent(Context context, Class<?> target) { }
    public Intent addFlags(int flags) { return this; }
}
''',
'android/content/pm/ApplicationInfo.java': '''package android.content.pm;
public class ApplicationInfo { }
''',
'android/content/pm/PackageManager.java': '''package android.content.pm;
public class PackageManager {
    public ApplicationInfo getApplicationInfo(String pkg, int flags) { return new ApplicationInfo(); }
    public CharSequence getApplicationLabel(ApplicationInfo info) { return "Test application"; }
}
''',
'android/os/Looper.java': '''package android.os;
public class Looper {
    public static Looper getMainLooper() { return new Looper(); }
}
''',
'android/os/Handler.java': '''package android.os;
public class Handler {
    public Handler(Looper looper) { }
    public boolean post(Runnable action) { return true; }
    public boolean postDelayed(Runnable action, long delay) { return true; }
}
''',
'dev/linjian/peek/FocusLockActivity.java': '''package dev.linjian.peek;
public class FocusLockActivity { }
''',
'dev/linjian/peek/DebugState.java': '''package dev.linjian.peek;
import android.content.Context;
public class DebugState {
    public static void append(Context ctx, String text) { }
}
''',
'dev/linjian/peek/ScreenshotService.java': '''package dev.linjian.peek;
public class ScreenshotService {
    private static final ScreenshotService INSTANCE = new ScreenshotService();
    public static ScreenshotService getInstance() { return INSTANCE; }
    public static String currentPackage() { return ""; }
    public static String shortMsg(Exception e) { return e.getMessage() == null ? e.toString() : e.getMessage(); }
    public boolean doLockScreen() {
        if (Thread.holdsLock(FocusMode.class)) throw new AssertionError("screen-off under Focus state lock");
        return true;
    }
    public boolean doHome() {
        if (Thread.holdsLock(FocusMode.class)) throw new AssertionError("HOME under Focus state lock");
        return true;
    }
}
'''
}
for name, content in sources.items():
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')
print(f'Generated {len(sources)} Android boundary adapters in {root}')
