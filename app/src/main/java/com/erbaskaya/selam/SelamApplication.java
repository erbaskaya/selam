package com.erbaskaya.selam;
/** Initializes Firebase on a cold background start as well as on a visible launch. */
public final class SelamApplication extends android.app.Application {
    @Override public void onCreate(){super.onCreate();PushRegistration.initialize(this);}
}
