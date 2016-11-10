package com.appunite.intenthelperlibrary;

import javax.annotation.Nonnull;
import javax.inject.Singleton;

import dagger.Component;

@Singleton
@Component (
        modules = IntentHelperModule.class,
        dependencies = IntentHelperProvider.Settings.class
)
public interface IntentHelperComponent {

    @Nonnull
    IntentHelper intentHelper();

}
