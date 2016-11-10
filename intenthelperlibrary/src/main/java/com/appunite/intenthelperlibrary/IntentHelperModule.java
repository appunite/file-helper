package com.appunite.intenthelperlibrary;


import android.content.Context;

import com.appunite.keyvalue.KeyValue;
import com.appunite.keyvalue.driver.level.KeyValueLevel;
import com.appunite.leveldb.LevelDB;
import com.appunite.leveldb.LevelDBException;
import com.appunite.rx.dagger.NetworkScheduler;
import com.moczul.ok2curl.CurlInterceptor;
import com.moczul.ok2curl.logger.Loggable;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;
import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import rx.Scheduler;
import rx.schedulers.Schedulers;

@Module ( includes = IntentHelperAndroidModule.class )
class IntentHelperModule {

    @Nonnull
    @Provides
    @Singleton
    @Named("loggingInterceptor")
    Interceptor loggingInterceptor(@Named("inDebug") boolean inDebug) {
        if (inDebug) {
            return new CurlInterceptor(new CurlLoggerLoggable());
        } else {
            return new Interceptor() {
                @Override
                public Response intercept(Chain chain) throws IOException {
                    return chain.proceed(chain.request());
                }
            };
        }
    }

    @Nonnull
    @Provides
    @Singleton
    public OkHttpClient provideOkHttpClient(@Nonnull @Named("userAgent") final String userAgent,
                                            @Nonnull @Named("loggingInterceptor") Interceptor loggingInterceptor) {
        return new OkHttpClient.Builder()
                .addInterceptor(new Interceptor() {
                    @Override
                    public Response intercept(Chain chain) throws IOException {
                        return chain.proceed(chain.request().newBuilder()
                                .addHeader("User-Agent", userAgent)
                                .build());
                    }
                })
                .addInterceptor(loggingInterceptor)
                .build();
    }

    @Nonnull
    @Provides
    @Singleton
    KeyValueLevel provideKeyValueSnappy(@Nonnull Context context) {
        final File gistrDatabasePath = context.getDatabasePath("managed-files");

        try {
            return KeyValueLevel.create(gistrDatabasePath);
        } catch (IOException | LevelDBException e) {
            try {
                LevelDB.destroy(gistrDatabasePath.getPath());
                return KeyValueLevel.create(gistrDatabasePath);
            } catch (LevelDBException | IOException e1) {
                throw new RuntimeException(e1);
            }
        }
    }

    @Nonnull
    @Provides
    KeyValue provideKeyValue(@Nonnull KeyValueLevel impl) {
        return impl;
    }

    @Nonnull
    @Provides
    @NetworkScheduler
    Scheduler networkScheduler() {
        return Schedulers.io();
    }

    private static class CurlLoggerLoggable implements Loggable {
        private static final Logger LOGGER = Logger.getLogger("Curl");

        @Override
        public void log(String s) {
            LOGGER.log(Level.FINE, s);
        }
    }

}
