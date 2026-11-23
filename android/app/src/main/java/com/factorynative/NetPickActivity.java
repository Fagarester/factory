package com.factorynative;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Picks an image (a QR photo or screenshot) for the network page. GameActivity is singleInstance: what it starts
 * for a result runs in another task and comes back cancelled at once, so this small activity asks instead and hands
 * the picture over in the same process.
 */
public class NetPickActivity extends Activity {
    interface Picked {
        void picked(Uri uri);  // null: nothing chosen
    }

    static Picked callback;
    private static final int REQ = 1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) return;  // recreated while the picker is open: its result still comes
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, Tr.t("Фото с QR-кодом")), REQ);
        } catch (Exception e) {
            deliver(null);
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        deliver(res == RESULT_OK && data != null ? data.getData() : null);
    }

    private void deliver(Uri uri) {
        Picked c = callback;
        callback = null;
        if (c != null) c.picked(uri);
        finish();
    }
}
