package com.factorynative;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The game's rumble on the phone. Factorio rumbles a gamepad only (game events, its controller settings); the
 * on-screen gamepad passes it to the phone's vibrator (SDLControllerManager.fnRumble). With the keys (touch /
 * cursor) an unseen gamepad with no input may be plugged in for the rumble alone (a trial: does the game rumble
 * a gamepad it does not take input from?).
 */
final class Haptics {
    /** Plug the unseen gamepad in with the keys. */
    static boolean withKeys;

    private Haptics() {}

    static void init(Context c) {
        SharedPreferences prefs = c.getSharedPreferences("game", Context.MODE_PRIVATE);
        org.libsdl.app.SDLControllerManager.fnRumbleOn = prefs.getBoolean("vibrateGame", true);
        withKeys = prefs.getBoolean("vibrateWithKeys", false);
    }

    static boolean game() {
        return org.libsdl.app.SDLControllerManager.fnRumbleOn;
    }

    static void setGame(Context c, boolean on) {
        org.libsdl.app.SDLControllerManager.fnRumbleOn = on;
        c.getSharedPreferences("game", Context.MODE_PRIVATE).edit().putBoolean("vibrateGame", on).apply();
    }

    static void setWithKeys(Context c, boolean on) {
        withKeys = on;
        c.getSharedPreferences("game", Context.MODE_PRIVATE).edit().putBoolean("vibrateWithKeys", on).apply();
    }
}
