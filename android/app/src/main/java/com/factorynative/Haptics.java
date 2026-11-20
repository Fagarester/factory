package com.factorynative;

import android.content.Context;
import android.content.SharedPreferences;

import org.libsdl.app.SDLControllerManager;

/**
 * The game's rumble (gear menu, two switches). Factorio rumbles a gamepad only:
 * - gamepad: the on-screen gamepad's rumble goes to the phone's vibrator, a plugged-in one's to its own motor;
 * - keyboard and mouse (touch / cursor): the loader lets the game rumble without a gamepad (loader/vibrate.c),
 *   libfnsdl gives it to an unseen gamepad plugged in for that alone, played on the phone's vibrator.
 */
final class Haptics {
    private Haptics() {}

    static void init(Context c) {
        SharedPreferences prefs = c.getSharedPreferences("game", Context.MODE_PRIVATE);
        SDLControllerManager.fnPadRumbleOn = prefs.getBoolean("vibrateGame", true);
        SDLControllerManager.fnKeysRumbleOn = prefs.getBoolean("vibrateWithKeys", false);
    }

    static boolean pad() {
        return SDLControllerManager.fnPadRumbleOn;
    }

    static void setPad(Context c, boolean on) {
        SDLControllerManager.fnPadRumbleOn = on;
        c.getSharedPreferences("game", Context.MODE_PRIVATE).edit().putBoolean("vibrateGame", on).apply();
    }

    /** The rumble with keyboard and mouse: also plugs the unseen gamepad in (GameActivity.updateGamepad). */
    static boolean keys() {
        return SDLControllerManager.fnKeysRumbleOn;
    }

    static void setKeys(Context c, boolean on) {
        SDLControllerManager.fnKeysRumbleOn = on;
        c.getSharedPreferences("game", Context.MODE_PRIVATE).edit().putBoolean("vibrateWithKeys", on).apply();
    }

    /** The on-screen gamepad is the controls now. */
    static void setPadMode(boolean on) {
        SDLControllerManager.fnPadMode = on;
    }
}
