package com.factorynative;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** The back camera's picture in a box; every free moment a frame is searched for a QR code (NetQr). */
final class NetCamera extends FrameLayout {
    interface Found {
        void found(String text);
    }

    interface Failed {
        void failed(String why);
    }

    private final TextureView view;
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Found found;
    private Failed failed;
    private volatile boolean busy, open;
    private Size preview;
    private byte[] luma;

    NetCamera(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        view = new TextureView(c);
        addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    boolean running() {
        return open;
    }

    /** Opens the camera (the CAMERA permission is already granted). */
    void start(Found f, Failed fail) {
        found = f;
        failed = fail;
        if (open) return;
        open = true;
        thread = new HandlerThread("fnet-camera");
        thread.start();
        bg = new Handler(thread.getLooper());
        if (view.isAvailable()) openCamera();
        else view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                if (open) openCamera();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
                transform();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });
    }

    void stop() {
        open = false;
        try {
            if (session != null) session.close();
            if (device != null) device.close();
            if (reader != null) reader.close();
        } catch (Exception ignored) {
            // closing
        }
        session = null;
        device = null;
        reader = null;
        if (thread != null) thread.quitSafely();
        thread = null;
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        try {
            CameraManager cm = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (id == null || facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && !isBack(cm, id)) id = c;
            }
            if (id == null) {
                fail("камеры нет");
                return;
            }
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            preview = pick(map.getOutputSizes(SurfaceTexture.class));
            Size yuv = pick(map.getOutputSizes(ImageFormat.YUV_420_888));
            reader = ImageReader.newInstance(yuv.getWidth(), yuv.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::frame, bg);
            post(this::transform);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    if (!open) {
                        d.close();
                        return;
                    }
                    device = d;
                    startSession();
                }
                @Override public void onDisconnected(CameraDevice d) {
                    d.close();
                }
                @Override public void onError(CameraDevice d, int e) {
                    d.close();
                    fail("ошибка камеры " + e);
                }
            }, bg);
        } catch (Exception e) {
            fail("камера: " + e.getMessage());
        }
    }

    private static boolean isBack(CameraManager cm, String id) throws Exception {
        Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
        return f != null && f == CameraCharacteristics.LENS_FACING_BACK;
    }

    @SuppressWarnings("deprecation")  // createCaptureSession(List...) is the simple form, fine on API 29+
    private void startSession() {
        try {
            SurfaceTexture st = view.getSurfaceTexture();
            st.setDefaultBufferSize(preview.getWidth(), preview.getHeight());
            Surface s = new Surface(st);
            CaptureRequest.Builder rb = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(s);
            rb.addTarget(reader.getSurface());
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            device.createCaptureSession(Arrays.asList(s, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession cs) {
                    if (!open) return;
                    session = cs;
                    try {
                        cs.setRepeatingRequest(rb.build(), null, bg);
                    } catch (Exception e) {
                        fail("камера: " + e.getMessage());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs) {
                    fail("камера не настроилась");
                }
            }, bg);
        } catch (Exception e) {
            fail("камера: " + e.getMessage());
        }
    }

    private void frame(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            if (busy || !open) return;
            busy = true;
            int w = img.getWidth(), h = img.getHeight();
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int stride = p.getRowStride();
            if (luma == null || luma.length != w * h) luma = new byte[w * h];
            for (int y = 0; y < h; y++) {
                buf.position(y * stride);
                buf.get(luma, y * w, w);
            }
            String text = NetQr.read(luma, w, h);
            if (text != null && NetCodes.kind(text) != 0) {
                Found f = found;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!open) return;
                    stop();
                    if (f != null) f.found(text);
                });
            }
        } catch (Exception ignored) {
            // the next frame
        } finally {
            busy = false;
            img.close();
        }
    }

    /** About 1280×720: enough for a QR on another phone's screen, still quick to search. */
    private static Size pick(Size[] sizes) {
        Size best = sizes[0];
        long bestD = Long.MAX_VALUE;
        for (Size s : sizes) {
            long d = Math.abs((long) s.getWidth() * s.getHeight() - 1280L * 720) + (s.getWidth() * 9 == s.getHeight() * 16 ? 0 : 200000);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /**
     * Upright and unsquashed (as Google's Camera2Basic): the camera already turns its buffer to the phone's natural
     * (portrait) orientation, so it is preview height × width; in landscape it is turned by the display's rotation
     * and scaled to fill the box (the edges are cut, nothing is stretched).
     */
    private void transform() {
        if (preview == null || view.getWidth() == 0) return;
        int rot = ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        float vw = view.getWidth(), vh = view.getHeight(), cx = vw / 2, cy = vh / 2;
        Matrix m = new Matrix();
        android.graphics.RectF viewRect = new android.graphics.RectF(0, 0, vw, vh);
        android.graphics.RectF buf = new android.graphics.RectF(0, 0, preview.getHeight(), preview.getWidth());
        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            buf.offset(cx - buf.centerX(), cy - buf.centerY());
            m.setRectToRect(viewRect, buf, Matrix.ScaleToFit.FILL);
            float scale = Math.max(vh / preview.getHeight(), vw / preview.getWidth());
            m.postScale(scale, scale, cx, cy);
            m.postRotate(90 * (rot - 2), cx, cy);
        } else {
            // portrait: the buffer (h × w) stretched to the view; undo the stretch and fill
            float scale = Math.max(vw / preview.getHeight(), vh / preview.getWidth());
            m.setScale(preview.getHeight() * scale / vw, preview.getWidth() * scale / vh, cx, cy);
            if (rot == Surface.ROTATION_180) m.postRotate(180, cx, cy);
        }
        view.setTransform(m);
    }

    private void fail(String why) {
        open = false;
        Failed f = failed;
        new Handler(Looper.getMainLooper()).post(() -> {
            stop();
            if (f != null) f.failed(why);
        });
    }
}
