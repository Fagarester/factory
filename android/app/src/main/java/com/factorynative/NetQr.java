package com.factorynative;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** QR codes of the network invites (ZXing core): a bitmap from a text, a text from a photo or a camera frame. */
final class NetQr {
    private NetQr() {}

    /** Black on white with a 4-module quiet zone, size×size pixels. */
    static Bitmap make(String text, int size) {
        try {
            Map<EncodeHintType, Object> h = new EnumMap<>(EncodeHintType.class);
            h.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            h.put(EncodeHintType.MARGIN, 4);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, h);
            int w = m.getWidth(), ht = m.getHeight();
            int[] px = new int[w * ht];
            for (int y = 0; y < ht; y++)
                for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            return Bitmap.createBitmap(px, w, ht, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    /** The QR text in a photo or screenshot, or null. */
    static String read(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        return read(new RGBLuminanceSource(w, h, px));
    }

    /** The QR text in a camera frame (its Y plane, packed), or null. */
    static String read(byte[] y, int w, int h) {
        return read(new PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false));
    }

    private static String read(LuminanceSource src) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        QRCodeReader r = new QRCodeReader();
        for (int pass = 0; pass < 3; pass++) {
            try {
                LuminanceSource s = pass == 2 ? src.invert() : src;  // also light-on-dark screens
                BinaryBitmap bb = new BinaryBitmap(pass == 1 ? new GlobalHistogramBinarizer(s) : new HybridBinarizer(s));
                return r.decode(bb, hints).getText();
            } catch (Exception e) {
                r.reset();
            }
        }
        return null;
    }
}
