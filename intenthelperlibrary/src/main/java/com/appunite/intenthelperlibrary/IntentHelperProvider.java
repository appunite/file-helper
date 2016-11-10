package com.appunite.intenthelperlibrary;


import android.content.Context;

import com.google.auto.value.AutoValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Named;

public class IntentHelperProvider {

    @AutoValue
    public static abstract class Settings {

        @Nonnull
        abstract Context context();

        @Nonnull
        @Named("fileProvider")
        abstract String fileProvider();

        @Nullable
        @Named("additionalSourceDataName")
        abstract String additionalSourceDataName();

        @Nonnull
        @Named("subdirectoryName")
        abstract String subdirectoryName();

        @Named("userAgent")
        @Nonnull
        abstract String userAgent();

        @Named("inDebug")
        abstract boolean inDebug();

        @Nonnull
        public static Builder builder() {
            return new AutoValue_IntentHelperProvider_Settings.Builder()
                    .setInDebug(true)
                    .setUserAgent("amazon-library");
        }

        @AutoValue.Builder
        public static abstract class Builder {

            /**
             * Set android context
             *
             * @param android context
             * @return builder
             */
            @Nonnull
            public abstract Builder setContext(@Nonnull Context context);

            @Nonnull
            public abstract Builder setFileProvider(@Nonnull String fileProvider);

            @Nonnull
            public abstract Builder setAdditionalSourceDataName(@Nullable String additionalSourceDataName);

            @Nonnull
            public abstract Builder setSubdirectoryName(@Nonnull String subdirectoryName);

            @Nonnull
            public abstract Builder setUserAgent(@Nonnull String userAgent);

            @Nonnull
            public abstract Builder setInDebug(boolean debug);

            @Nonnull
            public abstract Settings build();
        }
    }

    @Nonnull
    public static IntentHelperComponent build(@Nonnull Settings settings) {
        return DaggerIntentHelperComponent.builder()
                .settings(settings)
                .build();
    }
}
