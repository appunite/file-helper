package com.appunite.intenthelper;


import android.app.Application;
import android.util.Log;

import com.appunite.intenthelperlibrary.IntentHelperComponent;
import com.appunite.intenthelperlibrary.IntentHelperProvider;

public class MainApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        final IntentHelperComponent component = IntentHelperProvider.build(IntentHelperProvider.Settings.builder()
                .setContext(this)
                .setFileProvider("file provider registered in manifest")
                .setSubdirectoryName("example")
                .build());
    }
}
