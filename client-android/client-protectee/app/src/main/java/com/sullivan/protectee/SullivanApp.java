package com.sullivan.protectee;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Sullivan Android Application。
 *
 * <p><b>为什么要追踪当前 Activity</b>：来电弹窗由常驻服务（IncomingCallService）触发，
 * 它没有界面上下文；要把「接听 / 拒绝」对话框弹在用户正看着的那个页面上，
 * 就得知道当前 resumed 的是哪个 Activity。应用退到后台时这里是 null，
 * 此时来电走通知路径（通知上有「接听」按钮）。
 */
public class SullivanApp extends Application {

    @Nullable
    private static volatile Activity currentActivity;

    @Nullable
    private static volatile Context appContext;

    /** 当前 resumed 的 Activity；应用在后台或尚未有任何页面时返回 {@code null}。 */
    @Nullable
    public static Activity currentActivity() {
        return currentActivity;
    }

    /** 进程级应用上下文。任何地方（包括没有界面的服务）都能安全拿到。 */
    @NonNull
    public static Context appContext() {
        Context context = appContext;
        if (context == null) {
            throw new IllegalStateException("应用进程尚未初始化");
        }
        return context;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = this;
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                currentActivity = activity;
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                if (currentActivity == activity) {
                    // 置 null 而不是保留：保留一个已经 paused 的 Activity，
                    // 往它上面弹对话框可能弹在一个正在离开的页面上。
                    currentActivity = null;
                }
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity,
                                          @Nullable Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity,
                                                    @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }
}
