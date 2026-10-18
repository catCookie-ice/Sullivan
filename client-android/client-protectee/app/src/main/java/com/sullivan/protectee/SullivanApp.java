package com.sullivan.protectee;

import android.app.Application;

/**
 * Sullivan Android Application
 * 作为入口 Application 类，Hilt 注入点会由 @HiltAndroidApp 注解生成，
 * 当前骨架阶段仅为占位。
 */
public class SullivanApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
    }
}
