package com.erbaskaya.selam;

import android.os.Handler;
import android.os.Looper;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** In-process delivery hints. Only the authenticated RPCs supply actual user data. */
final class SyncEvents {
    interface Listener { void onChange(String kind); }
    private static final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    static void add(Listener listener) { listeners.add(listener); }
    static void remove(Listener listener) { listeners.remove(listener); }
    static void dispatch(String kind) { main.post(() -> { for (Listener l : listeners) l.onChange(kind); }); }
}
