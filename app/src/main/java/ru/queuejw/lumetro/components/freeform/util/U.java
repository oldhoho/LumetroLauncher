package ru.queuejw.lumetro.components.freeform.util;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import ru.queuejw.lumetro.R;

public class U {

    private U() {}

    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    public static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    public static boolean canEnableFreeform(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    public static boolean hasFreeformSupport(Context context) {
        return canEnableFreeform(context)
                && (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
                || Settings.Global.getInt(context.getContentResolver(), "enable_freeform_support", 0) != 0
                || (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1
                && Settings.Global.getInt(context.getContentResolver(), "force_resizable_activities", 0) != 0));
    }

    public static boolean isFreeformModeEnabled(Context context) {
        SharedPreferences pref = getSharedPreferences(context);
        return pref.getBoolean("freeform_enabled", false);
    }

    public static void startFreeformHack(Context context) {
        startFreeformHack(context, false);
    }

    public static void startFreeformHack(Context context, boolean checkMultiWindow) {
        try {
            Intent freeformHackIntent = new Intent(context, Class.forName("ru.queuejw.lumetro.components.freeform.activity.InvisibleActivityFreeform"));
            freeformHackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);

            if (checkMultiWindow)
                freeformHackIntent.putExtra("check_multiwindow", true);

            if (canDrawOverlays(context))
                startActivityLowerRight(context, freeformHackIntent);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    public static void stopFreeformHack(Context context) {
        Intent intent = new Intent("ru.queuejw.lumetro.FINISH_FREEFORM_ACTIVITY");
        context.sendBroadcast(intent);
    }

    public static void startActivityLowerRight(Context context, Intent intent) {
        DisplayInfo display = getDisplayInfo(context);
        try {
            context.startActivity(intent,
                    getActivityOptionsBundle(context, ApplicationType.FREEFORM_HACK, null,
                            display.width,
                            display.height,
                            display.width + 1,
                            display.height + 1
                    ));
        } catch (IllegalArgumentException | SecurityException ignored) {}
    }

    public static Bundle getActivityOptionsBundle(Context context, ApplicationType type, View view) {
        return getActivityOptionsBundle(context, type, "standard", view);
    }

    private static Bundle getActivityOptionsBundle(Context context, ApplicationType type, String windowSize, View view) {
        if (!canEnableFreeform(context) || !isFreeformModeEnabled(context))
            return getActivityOptions(view).toBundle();

        return getActivityOptions(context, type, view).toBundle();
    }

    public static Bundle getActivityOptionsBundle(Context context,
                                                   ApplicationType applicationType,
                                                   View view,
                                                   int left,
                                                   int top,
                                                   int right,
                                                   int bottom) {
        ActivityOptions options = getActivityOptions(context, applicationType, view);
        if (options == null) return null;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
            return options.toBundle();

        options.setLaunchBounds(new Rect(left, top, right, bottom));

        return options.toBundle();
    }

    private static ActivityOptions getActivityOptions(Context context, ApplicationType applicationType, View view) {
        ActivityOptions options;
        if (view != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                options = ActivityOptions.makeClipRevealAnimation(view, 0, 0, view.getWidth(), view.getHeight());
            else
                options = ActivityOptions.makeScaleUpAnimation(view, 0, 0, view.getWidth(), view.getHeight());
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            options = ActivityOptions.makeBasic();
        else {
            try {
                Constructor<ActivityOptions> constructor = ActivityOptions.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                options = constructor.newInstance();
            } catch (Exception e) {
                return null;
            }
        }

        if (applicationType == null)
            return options;

        // 使用自由窗口模式（带标题栏是系统行为）
        try {
            HiddenApiBypass.addHiddenApiExemptions("");
            Method method = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
            method.invoke(options, WINDOWING_MODE_FREEFORM);
        } catch (Exception ignored) {}

        return options;
    }

    private static ActivityOptions getActivityOptions(View view) {
        return getActivityOptions(null, null, view);
    }

    public static DisplayInfo getDisplayInfo(Context context) {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);

        if (display == null) {
            return new DisplayInfo(0, 0, 0, 0, false);
        }

        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        display.getMetrics(metrics);

        return new DisplayInfo(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, 0, false);
    }

    public static float getCurrentApiVersion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return Float.parseFloat(Build.VERSION.SDK_INT + "." + Build.VERSION.PREVIEW_SDK_INT);
        else
            return (float) Build.VERSION.SDK_INT;
    }

    public static boolean needsInvisibleActivityHacks() {
        return getCurrentApiVersion() < 32.0f;
    }

    public static boolean isOverridingFreeformHack(Context context) {
        return isFreeformModeEnabled(context);
    }

    public static boolean hasBrokenSetLaunchBoundsApi() {
        return false;
    }

    public static void allowReflection() {
        try {
            HiddenApiBypass.addHiddenApiExemptions("");
        } catch (Exception ignored) {}
    }
}
