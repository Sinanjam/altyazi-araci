package com.sinanjams.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Range;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

public class ExportService extends Service {
    public static final String ACTION_START_EXPORT = "com.sinanjams.app.START_EXPORT";
    public static final String ACTION_CANCEL_EXPORT = "com.sinanjams.app.CANCEL_EXPORT";
    public static final String EXTRA_VIDEO_URI = "video_uri";
    public static final String EXTRA_LOGO_URI = "logo_uri";
    public static final String EXTRA_SETTINGS_JSON = "settings_json";

    private static final String CHANNEL_ID = "export_progress";
    private static final int NOTIFICATION_ID = 2003;

    private volatile boolean cancelled = false;
    private Thread workerThread;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock exportWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_CANCEL_EXPORT.equals(action)) {
            cancelled = true;
            Thread currentWorker = workerThread;
            if (currentWorker != null) {
                try { currentWorker.interrupt(); } catch (Exception ignored) {}
                updateNotification(0, "MP4 iptal ediliyor...");
            } else {
                updateNotification(0, "MP4 iptal edildi", true);
                detachForegroundNotification();
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }

        if (!ACTION_START_EXPORT.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (workerThread != null && workerThread.isAlive()) {
            updateNotification(0, "Zaten MP4 hazırlanıyor...");
            return START_STICKY;
        }

        String videoUriString = intent.getStringExtra(EXTRA_VIDEO_URI);
        String logoUriString = intent.getStringExtra(EXTRA_LOGO_URI);
        String settingsJson = intent.getStringExtra(EXTRA_SETTINGS_JSON);
        if (videoUriString == null || videoUriString.trim().isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        cancelled = false;
        startForegroundCompat(0, "MP4 hazırlanıyor...");

        workerThread = new Thread(() -> {
            try {
                try { Process.setThreadPriority(-10); } catch (Exception ignored) {}
                try { Thread.currentThread().setPriority(Thread.MAX_PRIORITY); } catch (Exception ignored) {}
                acquireExportWakeLock();
                Uri videoUri = Uri.parse(videoUriString);
                Uri logoUri = logoUriString == null ? null : Uri.parse(logoUriString);
                ExportSettings settings = ExportSettings.fromJson(settingsJson);
                exportVideo(videoUri, logoUri, settings);
                updateNotification(100, "MP4 kaydedildi", true);
            } catch (InterruptedException e) {
                updateNotification(0, "MP4 iptal edildi", true);
            } catch (Exception e) {
                updateNotification(0, "MP4 kaydedilemedi: " + safeMessage(e), true);
            } finally {
                releaseExportWakeLock();
                detachForegroundNotification();
                workerThread = null;
                stopSelfResult(startId);
            }
        }, "10KoliklerExportThread");
        workerThread.start();
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        cancelled = true;
        Thread currentWorker = workerThread;
        if (currentWorker != null) {
            try { currentWorker.interrupt(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void exportVideo(Uri sourceVideoUri, Uri logoUri, ExportSettings settings) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap logoBitmap = null;
        Bitmap frameBitmap = null;
        NativeMp4Writer writer = null;
        try {
            retriever.setDataSource(this, sourceVideoUri);

            int srcWidth = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 720);
            int srcHeight = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 1280);
            int rotation = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            long durationMs = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 1L);
            if (rotation == 90 || rotation == 270) {
                int tmp = srcWidth;
                srcWidth = srcHeight;
                srcHeight = tmp;
            }

            int[] outputSize = calculateOutputSize(srcWidth, srcHeight, settings.maxLongSide);
            int width = outputSize[0];
            int height = outputSize[1];
            int fps = Math.max(15, Math.min(60, settings.fps));
            int frameCount = Math.max(1, (int) Math.ceil((durationMs / 1000.0) * fps));
            long durationUs = durationMs * 1000L;

            logoBitmap = loadBitmap(logoUri);

            try {
                FastSurfaceMp4Writer fastWriter = new FastSurfaceMp4Writer(
                        this,
                        sourceVideoUri,
                        logoBitmap,
                        settings,
                        width,
                        height,
                        fps,
                        durationUs,
                        rotation,
                        makeFileName(),
                        new ProgressCallback() {
                            int lastProgress = -1;
                            @Override
                            public boolean isCancelled() {
                                return cancelled || Thread.currentThread().isInterrupted();
                            }
                            @Override
                            public void onProgress(int progress) {
                                int safe = Math.max(0, Math.min(99, progress));
                                if (safe == 0 || safe >= lastProgress + 1) {
                                    lastProgress = safe;
                                    updateNotification(safe, "MP4 hazırlanıyor... %" + safe);
                                }
                            }
                        }
                );
                fastWriter.export();
                return;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception ignoredFastEngineFailure) {
                if (cancelled) throw new InterruptedException("Dışa aktarım iptal edildi");
                updateNotification(0, "MP4 hazırlanıyor...");
            }

            writer = new NativeMp4Writer(this, sourceVideoUri, width, height, fps, durationUs, makeFileName());
            writer.start();

            frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(frameBitmap);
            Paint videoPaint = new Paint(Paint.DITHER_FLAG);
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            Paint floatingPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);

            float floatX = width / 2f;
            float floatY = height / 2f;
            float speedX = 2.4f * (width / 1080f);
            float speedY = 2.4f * (height / 1920f);

            updateNotification(0, "MP4 hazırlanıyor...");
            int lastNotifiedProgress = -1;
            int subtitleIndex = 0;

            for (int i = 0; i < frameCount; i++) {
                if (cancelled) throw new InterruptedException("Dışa aktarım iptal edildi");
                long presentationTimeUs = (long) ((i * 1_000_000.0) / fps);
                if (presentationTimeUs > durationUs) presentationTimeUs = durationUs;

                Bitmap sourceFrame = getFrameFast(retriever, presentationTimeUs, width, height);
                canvas.drawColor(Color.BLACK);
                if (sourceFrame != null) {
                    try {
                        if (sourceFrame.getWidth() == width && sourceFrame.getHeight() == height) {
                            canvas.drawBitmap(sourceFrame, 0, 0, videoPaint);
                        } else {
                            drawBitmapCover(canvas, sourceFrame, new RectF(0, 0, width, height), videoPaint);
                        }
                    } finally {
                        sourceFrame.recycle();
                    }
                }

                drawCenterLogo(canvas, logoBitmap, settings, width, height, logoPaint);

                float seconds = presentationTimeUs / 1_000_000f;
                if (settings.floatingText.length() > 0 && !"none".equals(settings.floatingAnimType)) {
                    floatingPaint.setStyle(Paint.Style.FILL);
                    floatingPaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
                    floatingPaint.setTextAlign(Paint.Align.CENTER);
                    floatingPaint.setTextSize(clampFloat(settings.floatingSize * (width / 720f), 12f, width * 0.055f));
                    floatingPaint.setColor(settings.floatingColor);
                    floatingPaint.setAlpha(percentToAlpha(settings.floatingOpacity));
                    floatingPaint.setShadowLayer(5f, 2f, 2f, Color.argb(220, 0, 0, 0));

                    float textWidth = floatingPaint.measureText(settings.floatingText);
                    float textHeight = Math.max(20f, floatingPaint.getTextSize());
                    if ("marquee".equals(settings.floatingAnimType)) {
                        float travel = width + textWidth;
                        float x = width + textWidth / 2f - ((seconds * width * 0.28f) % (travel + textWidth));
                        float y = height * 0.15f;
                        canvas.drawText(settings.floatingText, x, y, floatingPaint);
                    } else {
                        floatX += speedX;
                        floatY += speedY;
                        if (floatX + textWidth / 2f > width || floatX - textWidth / 2f < 0) {
                            speedX = -speedX;
                            floatX = clampFloat(floatX, textWidth / 2f, width - textWidth / 2f);
                        }
                        if (floatY + textHeight / 2f > height || floatY - textHeight / 2f < 0) {
                            speedY = -speedY;
                            floatY = clampFloat(floatY, textHeight / 2f, height - textHeight / 2f);
                        }
                        canvas.drawText(settings.floatingText, floatX, floatY, floatingPaint);
                    }
                    floatingPaint.clearShadowLayer();
                    floatingPaint.setAlpha(255);
                }

                long presentationTimeMs = presentationTimeUs / 1000L;
                while (subtitleIndex < settings.subtitles.size() && presentationTimeMs > settings.subtitles.get(subtitleIndex).endMs) {
                    subtitleIndex++;
                }
                if (subtitleIndex < settings.subtitles.size()) {
                    Subtitle active = settings.subtitles.get(subtitleIndex);
                    if (presentationTimeMs >= active.startMs && presentationTimeMs <= active.endMs) {
                        drawSubtitle(canvas, active.text, settings, width, height, textPaint, strokePaint);
                    }
                }

                writer.writeFrame(frameBitmap, presentationTimeUs);
                int progress = Math.min(99, Math.round(((i + 1) * 100f) / frameCount));
                if (i == 0 || progress >= lastNotifiedProgress + 1) {
                    lastNotifiedProgress = progress;
                    updateNotification(progress, "MP4 hazırlanıyor... %" + progress);
                }
            }

            writer.finish();
            writer = null;
        } finally {
            if (writer != null) writer.abort();
            if (frameBitmap != null) frameBitmap.recycle();
            if (logoBitmap != null) logoBitmap.recycle();
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private void acquireExportWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) return;
            if (exportWakeLock != null && exportWakeLock.isHeld()) return;
            exportWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "10Kolikler:ExportWakeLock");
            exportWakeLock.setReferenceCounted(false);
            exportWakeLock.acquire(2 * 60 * 60 * 1000L);
        } catch (Exception ignored) {}
    }

    private void releaseExportWakeLock() {
        try {
            if (exportWakeLock != null && exportWakeLock.isHeld()) exportWakeLock.release();
        } catch (Exception ignored) {
        } finally {
            exportWakeLock = null;
        }
    }

    private void detachForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(false);
            }
        } catch (Exception ignored) {
        }
    }

    private void startForegroundCompat(int progress, String text) {
        Notification notification = buildNotification(progress, text, false);
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (Build.VERSION.SDK_INT >= 35) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING;
            }
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(int progress, String text) {
        updateNotification(progress, text, false);
    }

    private void updateNotification(int progress, String text, boolean done) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(progress, text, done));
        }
    }

    private Notification buildNotification(int progress, String text, boolean done) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, flags);

        Intent cancelIntent = new Intent(this, ExportService.class);
        cancelIntent.setAction(ACTION_CANCEL_EXPORT);
        PendingIntent cancelPendingIntent = PendingIntent.getService(this, 2004, cancelIntent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_export)
                .setContentTitle("Altyazı Aracı")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(!done)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        if (!done) {
            builder.setProgress(100, Math.max(0, Math.min(100, progress)), false);
            builder.addAction(R.drawable.ic_stat_export, "İptal", cancelPendingIntent);
        } else {
            builder.setProgress(0, 0, false);
            builder.setAutoCancel(false);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            builder.setColor(Color.rgb(230, 57, 70));
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26 && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "MP4 Dışa Aktarma", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Video dışa aktarma ilerlemesi");
            notificationManager.createNotificationChannel(channel);
        }
    }

    private static Bitmap getFrameFast(MediaMetadataRetriever retriever, long timeUs, int width, int height) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                Bitmap scaled = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, width, height);
                if (scaled != null) return scaled;
            } catch (Exception ignored) {}
        }
        try {
            Bitmap exact = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (exact != null) return exact;
        } catch (Exception ignored) {}
        try {
            return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int[] calculateOutputSize(int srcWidth, int srcHeight, int maxLongSide) {
        int safeMax = Math.max(720, Math.min(1920, maxLongSide));
        float scale = Math.min(1f, safeMax / (float) Math.max(srcWidth, srcHeight));
        int w = Math.max(2, Math.round(srcWidth * scale));
        int h = Math.max(2, Math.round(srcHeight * scale));
        if ((w & 1) == 1) w--;
        if ((h & 1) == 1) h--;
        return new int[]{Math.max(2, w), Math.max(2, h)};
    }

    private Bitmap loadBitmap(Uri uri) {
        if (uri == null) return null;
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeStream(inputStream, null, options);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void drawBitmapCover(Canvas canvas, Bitmap bitmap, RectF dest, Paint paint) {
        float scale = Math.max(dest.width() / bitmap.getWidth(), dest.height() / bitmap.getHeight());
        float scaledW = bitmap.getWidth() * scale;
        float scaledH = bitmap.getHeight() * scale;
        float left = dest.left + (dest.width() - scaledW) / 2f;
        float top = dest.top + (dest.height() - scaledH) / 2f;
        RectF target = new RectF(left, top, left + scaledW, top + scaledH);
        canvas.drawBitmap(bitmap, null, target, paint);
    }

    private static void drawCenterLogo(Canvas canvas, Bitmap logo, ExportSettings settings, int width, int height, Paint paint) {
        if (logo == null) return;
        float logoWidth = width * (settings.logoSize / 100f);
        float ratio = logoWidth / Math.max(1, logo.getWidth());
        float logoHeight = logo.getHeight() * ratio;
        float centerX = width * clampFloat(settings.logoX, 0f, 1f);
        float centerY = height * clampFloat(settings.logoY, 0f, 1f);
        float left = centerX - logoWidth / 2f;
        float top = centerY - logoHeight / 2f;
        left = clampFloat(left, -logoWidth * 0.45f, width - logoWidth * 0.55f);
        top = clampFloat(top, -logoHeight * 0.45f, height - logoHeight * 0.55f);
        paint.setAlpha(percentToAlpha(settings.logoOpacity));
        canvas.drawBitmap(logo, null, new RectF(left, top, left + logoWidth, top + logoHeight), paint);
        paint.setAlpha(255);
    }

    private static void drawSubtitle(Canvas canvas, String text, ExportSettings settings, int width, int height, Paint fill, Paint stroke) {
        float scaledSize = settings.fontSize * (width / 720f);
        scaledSize = Math.max(16f, Math.min(width * 0.075f, scaledSize));
        float x = width / 2f;
        float y = height * (settings.textYPos / 100f);
        float maxWidth = width * 0.86f;
        float lineHeight = scaledSize * 1.16f;

        fill.setStyle(Paint.Style.FILL);
        fill.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        fill.setTextAlign(Paint.Align.CENTER);
        fill.setTextSize(scaledSize);
        fill.setColor(settings.fontColor);
        fill.setShadowLayer(scaledSize * 0.12f, 2f, 2f, Color.argb(180, 0, 0, 0));

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setTypeface(fill.getTypeface());
        stroke.setTextAlign(Paint.Align.CENTER);
        stroke.setTextSize(scaledSize);
        stroke.setStrokeWidth(Math.max(3f, scaledSize * 0.14f));
        stroke.setColor(Color.BLACK);
        stroke.setStrokeJoin(Paint.Join.ROUND);

        List<String> lines = wrapLines(text, fill, maxWidth);
        float startY = y - ((lines.size() - 1) * lineHeight) / 2f;
        for (int i = 0; i < lines.size(); i++) {
            float lineY = startY + i * lineHeight;
            canvas.drawText(lines.get(i), x, lineY, stroke);
            canvas.drawText(lines.get(i), x, lineY, fill);
        }
        fill.clearShadowLayer();
    }

    private static List<String> wrapLines(String text, Paint paint, float maxWidth) {
        List<String> out = new ArrayList<>();
        String[] hardLines = text.split("\\n");
        for (String hard : hardLines) {
            String[] words = hard.trim().split("\\s+");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                if (word.length() == 0) continue;
                String test = line.length() == 0 ? word : line + " " + word;
                if (paint.measureText(test) > maxWidth && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(test);
                }
            }
            if (line.length() > 0) out.add(line.toString());
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    private static Subtitle findSubtitle(List<Subtitle> subtitles, long timeMs) {
        for (Subtitle subtitle : subtitles) {
            if (timeMs >= subtitle.startMs && timeMs <= subtitle.endMs) return subtitle;
        }
        return null;
    }

    private static String makeFileName() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "altyazi-araci-" + stamp + ".mp4";
    }

    private static int parseColorSafe(String value, int fallback) {
        try { return Color.parseColor(value); } catch (Exception e) { return fallback; }
    }

    private static int parseInt(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); } catch (Exception e) { return fallback; }
    }

    private static long parseLong(String value, long fallback) {
        try { return value == null ? fallback : Long.parseLong(value); } catch (Exception e) { return fallback; }
    }

    private static int percentToAlpha(float percent) {
        return Math.max(0, Math.min(255, Math.round(percent * 2.55f)));
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safeMessage(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.trim().isEmpty() ? e.getClass().getSimpleName() : msg;
    }

    private static final class Subtitle {
        final long startMs;
        final long endMs;
        final String text;
        Subtitle(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private static final class ExportSettings {
        final List<Subtitle> subtitles;
        final float fontSize;
        final int fontColor;
        final float textYPos;
        final float logoSize;
        final float logoOpacity;
        final float logoX;
        final float logoY;
        final String floatingText;
        final String floatingAnimType;
        final float floatingSize;
        final float floatingOpacity;
        final int floatingColor;
        final int fps;
        final int maxLongSide;

        private ExportSettings(List<Subtitle> subtitles, float fontSize, int fontColor, float textYPos,
                               float logoSize, float logoOpacity, float logoX, float logoY, String floatingText, String floatingAnimType,
                               float floatingSize, float floatingOpacity, int floatingColor, int fps, int maxLongSide) {
            this.subtitles = subtitles;
            this.fontSize = fontSize;
            this.fontColor = fontColor;
            this.textYPos = textYPos;
            this.logoSize = logoSize;
            this.logoOpacity = logoOpacity;
            this.logoX = logoX;
            this.logoY = logoY;
            this.floatingText = floatingText == null ? "" : floatingText;
            this.floatingAnimType = floatingAnimType == null ? "none" : floatingAnimType;
            this.floatingSize = floatingSize;
            this.floatingOpacity = floatingOpacity;
            this.floatingColor = floatingColor;
            this.fps = fps;
            this.maxLongSide = maxLongSide;
        }

        static ExportSettings fromJson(String json) throws Exception {
            JSONObject obj = new JSONObject(json == null || json.trim().isEmpty() ? "{}" : json);
            List<Subtitle> subs = new ArrayList<>();
            JSONArray arr = obj.optJSONArray("subtitles");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item == null) continue;
                    long start = item.optLong("start", 0);
                    long end = item.optLong("end", 0);
                    String text = item.optString("text", "");
                    if (end >= start && text.trim().length() > 0) subs.add(new Subtitle(start, end, text));
                }
            }
            return new ExportSettings(
                    subs,
                    (float) obj.optDouble("fontSize", 32),
                    parseColorSafe(obj.optString("fontColor", "#ffffff"), Color.WHITE),
                    (float) obj.optDouble("textYPos", 80),
                    (float) obj.optDouble("logoSize", 25),
                    (float) obj.optDouble("logoOpacity", 80),
                    (float) obj.optDouble("logoX", 0.5),
                    (float) obj.optDouble("logoY", 0.5),
                    obj.optString("floatingText", ""),
                    obj.optString("floatingAnimType", "none"),
                    (float) obj.optDouble("floatingSize", 28),
                    (float) obj.optDouble("floatingOpacity", 50),
                    parseColorSafe(obj.optString("floatingColor", "#ffffff"), Color.WHITE),
                    obj.optInt("fps", 60),
                    obj.optInt("maxLongSide", 1920)
            );
        }
    }


    private interface ProgressCallback {
        boolean isCancelled();
        void onProgress(int progress);
    }

    private static final class FastSurfaceMp4Writer {
        private static final String MIME_VIDEO = "video/avc";
        private static final int TIMEOUT_US = 10_000;
        private final Context context;
        private final Uri sourceVideoUri;
        private final Bitmap logoBitmap;
        private final ExportSettings settings;
        private final int width;
        private final int height;
        private final int fps;
        private final long durationUs;
        private final int rotation;
        private final String fileName;
        private final ProgressCallback progressCallback;

        private MediaExtractor videoExtractor;
        private MediaCodec decoder;
        private MediaCodec encoder;
        private MediaMuxer muxer;
        private MediaCodec.BufferInfo decoderInfo;
        private MediaCodec.BufferInfo encoderInfo;
        private int videoTrackIndex = -1;
        private int audioTrackIndex = -1;
        private boolean muxerStarted = false;
        private Uri outputUri;
        private File outputFile;
        private ParcelFileDescriptor outputPfd;
        private Surface encoderInputSurface;
        private CodecInputSurface eglSurface;
        private SurfaceTexture decoderSurfaceTexture;
        private Surface decoderSurface;
        private SurfaceTextureFrameWaiter frameWaiter;
        private FastTextureRenderer renderer;
        private Bitmap overlayBitmap;
        private Canvas overlayCanvas;
        private OverlayDrawer overlayDrawer;

        FastSurfaceMp4Writer(Context context, Uri sourceVideoUri, Bitmap logoBitmap, ExportSettings settings,
                             int width, int height, int fps, long durationUs, int rotation, String fileName,
                             ProgressCallback progressCallback) {
            this.context = context.getApplicationContext();
            this.sourceVideoUri = sourceVideoUri;
            this.logoBitmap = logoBitmap;
            this.settings = settings;
            this.width = width;
            this.height = height;
            this.fps = Math.max(15, Math.min(60, fps));
            this.durationUs = Math.max(1L, durationUs);
            this.rotation = ((rotation % 360) + 360) % 360;
            this.fileName = fileName;
            this.progressCallback = progressCallback;
        }

        void export() throws Exception {
            boolean success = false;
            try {
                ensureNotCancelled();
                createOutputTarget();
                muxer = createMuxer();
                addAudioTrackIfAvailable();

                MediaFormat inputFormat = selectVideoTrack();
                String mime = inputFormat.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) throw new IllegalStateException("Video parçası bulunamadı");

                int bitRate = Math.max(55_000_000, Math.min(95_000_000, width * height * fps * 7 / 10));
                MediaFormat outputFormat = MediaFormat.createVideoFormat(MIME_VIDEO, width, height);
                outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
                outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    outputFormat.setInteger(MediaFormat.KEY_PRIORITY, 0);
                    outputFormat.setInteger(MediaFormat.KEY_OPERATING_RATE, Math.max(fps * 2, 120));
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    outputFormat.setInteger(MediaFormat.KEY_LATENCY, 0);
                }

                encoder = MediaCodec.createEncoderByType(MIME_VIDEO);
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoderInputSurface = encoder.createInputSurface();
                encoder.start();
                encoderInfo = new MediaCodec.BufferInfo();

                eglSurface = new CodecInputSurface(encoderInputSurface);
                eglSurface.makeCurrent();
                renderer = new FastTextureRenderer(rotation);
                renderer.surfaceCreated();

                frameWaiter = new SurfaceTextureFrameWaiter();
                decoderSurfaceTexture = new SurfaceTexture(renderer.getVideoTextureId());
                decoderSurfaceTexture.setOnFrameAvailableListener(frameWaiter);
                decoderSurface = new Surface(decoderSurfaceTexture);

                decoder = MediaCodec.createDecoderByType(mime);
                decoder.configure(inputFormat, decoderSurface, null, 0);
                decoder.start();
                decoderInfo = new MediaCodec.BufferInfo();

                overlayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                overlayCanvas = new Canvas(overlayBitmap);
                overlayDrawer = new OverlayDrawer(settings, logoBitmap, width, height);

                runDecodeEncodeLoop();
                copyAudioSamplesIfAvailable();
                releaseMuxerSuccess();
                success = true;
            } finally {
                releaseCodecObjects();
                if (!success) abortOutput();
            }
        }

        private void runDecodeEncodeLoop() throws Exception {
            boolean inputDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;
            long lastPresentationUs = -1L;
            long frameIntervalUs = Math.max(1L, 1_000_000L / Math.max(1, fps));
            int sourceFps = detectInputFrameRate(30);
            int repeatCount = sourceFps > 0 && sourceFps < fps ? Math.max(1, Math.min(4, Math.round(fps / (float) sourceFps))) : 1;

            while (!encoderDone) {
                ensureNotCancelled();
                if (!inputDone) {
                    int inputBufferId = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inputBufferId >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                        if (inputBuffer != null) inputBuffer.clear();
                        int sampleSize = inputBuffer == null ? -1 : videoExtractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long sampleTime = Math.max(0L, videoExtractor.getSampleTime());
                            decoder.queueInputBuffer(inputBufferId, 0, sampleSize, sampleTime, 0);
                            videoExtractor.advance();
                        }
                    }
                }

                drainEncoder(false);

                if (!decoderDone) {
                    int decoderStatus = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US);
                    if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // no output yet
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Surface output format change is expected on some devices.
                    } else if (decoderStatus < 0) {
                        // ignore other informational statuses
                    } else {
                        boolean doRender = decoderInfo.size != 0;
                        long ptsUs = Math.max(0L, decoderInfo.presentationTimeUs);
                        decoder.releaseOutputBuffer(decoderStatus, doRender);
                        if (doRender) {
                            frameWaiter.awaitFrame();
                            decoderSurfaceTexture.updateTexImage();
                            float[] stMatrix = new float[16];
                            decoderSurfaceTexture.getTransformMatrix(stMatrix);

                            int framesToWrite = repeatCount;
                            for (int r = 0; r < framesToWrite; r++) {
                                ensureNotCancelled();
                                long outPtsUs = ptsUs + (r * frameIntervalUs);
                                if (outPtsUs <= lastPresentationUs) outPtsUs = lastPresentationUs + 1L;
                                if (outPtsUs > durationUs + frameIntervalUs) break;
                                drawAndSubmitFrame(stMatrix, outPtsUs);
                                lastPresentationUs = outPtsUs;
                            }
                            int progress = Math.min(99, Math.round((Math.min(ptsUs, durationUs) * 100f) / Math.max(1L, durationUs)));
                            progressCallback.onProgress(progress);
                        }
                        if ((decoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderDone = true;
                            encoder.signalEndOfInputStream();
                        }
                    }
                }

                if (decoderDone) {
                    encoderDone = drainEncoder(true);
                }
            }
            progressCallback.onProgress(99);
        }

        private void drawAndSubmitFrame(float[] stMatrix, long presentationTimeUs) throws Exception {
            eglSurface.makeCurrent();
            GLES20.glViewport(0, 0, width, height);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            renderer.drawVideoFrame(stMatrix);

            overlayCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            overlayDrawer.draw(overlayCanvas, presentationTimeUs);
            renderer.drawOverlay(overlayBitmap);

            eglSurface.setPresentationTime(presentationTimeUs * 1000L);
            eglSurface.swapBuffers();
            drainEncoder(false);
        }

        private MediaFormat selectVideoTrack() throws Exception {
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(context, sourceVideoUri, new HashMap<String, String>());
            for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                MediaFormat format = videoExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoExtractor.selectTrack(i);
                    return format;
                }
            }
            throw new IllegalStateException("Video parçası bulunamadı");
        }

        private int detectInputFrameRate(int fallback) {
            try {
                if (videoExtractor == null) return fallback;
                for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                    MediaFormat format = videoExtractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        int value = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                        if (value >= 10 && value <= 120) return value;
                    }
                }
            } catch (Exception ignored) {}
            return fallback;
        }

        private boolean drainEncoder(boolean endOfStream) throws Exception {
            boolean done = false;
            while (true) {
                int encoderStatus = encoder.dequeueOutputBuffer(encoderInfo, endOfStream ? TIMEOUT_US : 0);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IllegalStateException("Video formatı iki kez değişti");
                    MediaFormat newFormat = encoder.getOutputFormat();
                    videoTrackIndex = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted = true;
                } else if (encoderStatus < 0) {
                    // ignore
                } else {
                    ByteBuffer encodedData = encoder.getOutputBuffer(encoderStatus);
                    if (encodedData == null) throw new IllegalStateException("Encoder output boş");
                    if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) encoderInfo.size = 0;
                    if (encoderInfo.size != 0) {
                        if (!muxerStarted) throw new IllegalStateException("Muxer başlamadı");
                        encodedData.position(encoderInfo.offset);
                        encodedData.limit(encoderInfo.offset + encoderInfo.size);
                        muxer.writeSampleData(videoTrackIndex, encodedData, encoderInfo);
                    }
                    if ((encoderInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                    encoder.releaseOutputBuffer(encoderStatus, false);
                    if (done) break;
                }
            }
            return done;
        }

        private void ensureNotCancelled() throws InterruptedException {
            if (progressCallback != null && progressCallback.isCancelled()) throw new InterruptedException("Dışa aktarım iptal edildi");
        }

        private void createOutputTarget() throws Exception {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AltyaziAraci");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                outputUri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (outputUri == null) throw new IllegalStateException("Video dosyası oluşturulamadı");
                outputPfd = context.getContentResolver().openFileDescriptor(outputUri, "w");
                if (outputPfd == null) throw new IllegalStateException("Video dosyası açılamadı");
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AltyaziAraci");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Klasör oluşturulamadı");
                outputFile = new File(dir, fileName);
            }
        }

        private MediaMuxer createMuxer() throws Exception {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && outputPfd != null) {
                return new MediaMuxer(outputPfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }
            if (outputFile == null) throw new IllegalStateException("Çıktı yolu yok");
            return new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        private void addAudioTrackIfAvailable() {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(context, sourceVideoUri, new HashMap<String, String>());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrackIndex = muxer.addTrack(format);
                        break;
                    }
                }
            } catch (Exception ignored) {
                audioTrackIndex = -1;
            } finally {
                extractor.release();
            }
        }

        private void copyAudioSamplesIfAvailable() {
            if (audioTrackIndex < 0 || muxer == null || !muxerStarted) return;
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(context, sourceVideoUri, new HashMap<String, String>());
                int audioTrack = -1;
                int maxInputSize = 256 * 1024;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrack = i;
                        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            maxInputSize = Math.max(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                        }
                        break;
                    }
                }
                if (audioTrack < 0) return;
                extractor.selectTrack(audioTrack);
                ByteBuffer buffer = ByteBuffer.allocateDirect(maxInputSize);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (true) {
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;
                    long sampleTime = extractor.getSampleTime();
                    if (sampleTime < 0 || sampleTime > durationUs + 50_000L) break;
                    info.set(0, sampleSize, Math.max(0, sampleTime), extractor.getSampleFlags());
                    muxer.writeSampleData(audioTrackIndex, buffer, info);
                    extractor.advance();
                }
            } catch (Exception ignored) {
            } finally {
                extractor.release();
            }
        }

        private void releaseMuxerSuccess() throws Exception {
            try { if (muxer != null) muxer.stop(); } catch (Exception ignored) {}
            try { if (muxer != null) muxer.release(); } finally { muxer = null; }
            if (outputPfd != null) {
                try { outputPfd.close(); } catch (Exception ignored) {}
                outputPfd = null;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                context.getContentResolver().update(outputUri, values, null, null);
            } else if (outputFile != null) {
                MediaScannerConnection.scanFile(context, new String[]{outputFile.getAbsolutePath()}, new String[]{"video/mp4"}, null);
            }
        }

        private void releaseCodecObjects() {
            try { if (decoder != null) decoder.stop(); } catch (Exception ignored) {}
            try { if (decoder != null) decoder.release(); } catch (Exception ignored) {}
            decoder = null;
            try { if (encoder != null) encoder.stop(); } catch (Exception ignored) {}
            try { if (encoder != null) encoder.release(); } catch (Exception ignored) {}
            encoder = null;
            try { if (videoExtractor != null) videoExtractor.release(); } catch (Exception ignored) {}
            videoExtractor = null;
            try { if (decoderSurface != null) decoderSurface.release(); } catch (Exception ignored) {}
            decoderSurface = null;
            try { if (decoderSurfaceTexture != null) decoderSurfaceTexture.release(); } catch (Exception ignored) {}
            decoderSurfaceTexture = null;
            try { if (eglSurface != null) eglSurface.release(); } catch (Exception ignored) {}
            eglSurface = null;
            try { if (encoderInputSurface != null) encoderInputSurface.release(); } catch (Exception ignored) {}
            encoderInputSurface = null;
            if (overlayBitmap != null) {
                try { overlayBitmap.recycle(); } catch (Exception ignored) {}
                overlayBitmap = null;
            }
        }

        private void abortOutput() {
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            muxer = null;
            try { if (outputPfd != null) outputPfd.close(); } catch (Exception ignored) {}
            outputPfd = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                    context.getContentResolver().delete(outputUri, null, null);
                } else if (outputFile != null && outputFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    outputFile.delete();
                }
            } catch (Exception ignored) {}
        }
    }

    private static final class OverlayDrawer {
        private final ExportSettings settings;
        private final Bitmap logoBitmap;
        private final int width;
        private final int height;
        private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint floatingPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private float floatX;
        private float floatY;
        private float speedX;
        private float speedY;
        private int subtitleIndex = 0;

        OverlayDrawer(ExportSettings settings, Bitmap logoBitmap, int width, int height) {
            this.settings = settings;
            this.logoBitmap = logoBitmap;
            this.width = width;
            this.height = height;
            this.floatX = width / 2f;
            this.floatY = height / 2f;
            this.speedX = 2.4f * (width / 1080f);
            this.speedY = 2.4f * (height / 1920f);
        }

        void draw(Canvas canvas, long presentationTimeUs) {
            drawCenterLogo(canvas, logoBitmap, settings, width, height, logoPaint);

            float seconds = presentationTimeUs / 1_000_000f;
            if (settings.floatingText.length() > 0 && !"none".equals(settings.floatingAnimType)) {
                floatingPaint.setStyle(Paint.Style.FILL);
                floatingPaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
                floatingPaint.setTextAlign(Paint.Align.CENTER);
                floatingPaint.setTextSize(clampFloat(settings.floatingSize * (width / 720f), 12f, width * 0.055f));
                floatingPaint.setColor(settings.floatingColor);
                floatingPaint.setAlpha(percentToAlpha(settings.floatingOpacity));
                floatingPaint.setShadowLayer(5f, 2f, 2f, Color.argb(220, 0, 0, 0));

                float textWidth = floatingPaint.measureText(settings.floatingText);
                float textHeight = Math.max(20f, floatingPaint.getTextSize());
                if ("marquee".equals(settings.floatingAnimType)) {
                    float travel = width + textWidth;
                    float x = width + textWidth / 2f - ((seconds * width * 0.28f) % (travel + textWidth));
                    float y = height * 0.15f;
                    canvas.drawText(settings.floatingText, x, y, floatingPaint);
                } else {
                    floatX += speedX;
                    floatY += speedY;
                    if (floatX + textWidth / 2f > width || floatX - textWidth / 2f < 0) {
                        speedX = -speedX;
                        floatX = clampFloat(floatX, textWidth / 2f, width - textWidth / 2f);
                    }
                    if (floatY + textHeight / 2f > height || floatY - textHeight / 2f < 0) {
                        speedY = -speedY;
                        floatY = clampFloat(floatY, textHeight / 2f, height - textHeight / 2f);
                    }
                    canvas.drawText(settings.floatingText, floatX, floatY, floatingPaint);
                }
                floatingPaint.clearShadowLayer();
                floatingPaint.setAlpha(255);
            }

            long presentationTimeMs = presentationTimeUs / 1000L;
            while (subtitleIndex < settings.subtitles.size() && presentationTimeMs > settings.subtitles.get(subtitleIndex).endMs) {
                subtitleIndex++;
            }
            if (subtitleIndex < settings.subtitles.size()) {
                Subtitle active = settings.subtitles.get(subtitleIndex);
                if (presentationTimeMs >= active.startMs && presentationTimeMs <= active.endMs) {
                    drawSubtitle(canvas, active.text, settings, width, height, textPaint, strokePaint);
                }
            }
        }
    }

    private static final class SurfaceTextureFrameWaiter implements SurfaceTexture.OnFrameAvailableListener {
        private final Object frameSyncObject = new Object();
        private boolean frameAvailable;

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            synchronized (frameSyncObject) {
                frameAvailable = true;
                frameSyncObject.notifyAll();
            }
        }

        void awaitFrame() throws InterruptedException {
            synchronized (frameSyncObject) {
                long deadline = System.currentTimeMillis() + 2500L;
                while (!frameAvailable) {
                    long waitMs = deadline - System.currentTimeMillis();
                    if (waitMs <= 0L) throw new RuntimeException("Video karesi beklenirken zaman aşımı");
                    frameSyncObject.wait(waitMs);
                }
                frameAvailable = false;
            }
        }
    }

    private static final class CodecInputSurface {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;
        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        private final Surface surface;

        CodecInputSurface(Surface surface) {
            this.surface = surface;
            eglSetup();
        }

        private void eglSetup() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("EGL display alınamadı");
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw new RuntimeException("EGL initialize başarısız");
            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0)) {
                throw new RuntimeException("EGL config bulunamadı");
            }
            int[] attrib_list = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, attrib_list, 0);
            checkEglError("eglCreateContext");
            int[] surfaceAttribs = {EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0);
            checkEglError("eglCreateWindowSurface");
        }

        void makeCurrent() {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) throw new RuntimeException("eglMakeCurrent başarısız");
        }

        void swapBuffers() {
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) throw new RuntimeException("eglSwapBuffers başarısız");
        }

        void setPresentationTime(long nsecs) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs);
        }

        void release() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(eglDisplay);
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;
        }

        private void checkEglError(String msg) {
            int error = EGL14.eglGetError();
            if (error != EGL14.EGL_SUCCESS) throw new RuntimeException(msg + ": EGL error 0x" + Integer.toHexString(error));
        }
    }

    private static final class FastTextureRenderer {
        private static final float[] FULL_RECTANGLE_COORDS = {
                -1.0f, -1.0f,
                 1.0f, -1.0f,
                -1.0f,  1.0f,
                 1.0f,  1.0f
        };
        private static final float[] OVERLAY_TEX_COORDS = {
                0.0f, 1.0f,
                1.0f, 1.0f,
                0.0f, 0.0f,
                1.0f, 0.0f
        };
        private static final String VERTEX_SHADER =
                "uniform mat4 uMVPMatrix;\n" +
                "uniform mat4 uSTMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "  gl_Position = uMVPMatrix * aPosition;\n" +
                "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                "}\n";
        private static final String FRAGMENT_EXTERNAL =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                "}\n";
        private static final String FRAGMENT_2D =
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform sampler2D sTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                "}\n";

        private final java.nio.FloatBuffer vertexBuffer = createFloatBuffer(FULL_RECTANGLE_COORDS);
        private final java.nio.FloatBuffer overlayTexBuffer = createFloatBuffer(OVERLAY_TEX_COORDS);
        private final java.nio.FloatBuffer videoTexBuffer;
        private final float[] identityMatrix = new float[16];
        private int videoTextureId = -1;
        private int overlayTextureId = -1;
        private int externalProgram;
        private int overlayProgram;
        private boolean overlayTextureInitialized = false;

        FastTextureRenderer(int rotation) {
            videoTexBuffer = createFloatBuffer(videoTexCoordsForRotation(rotation));
            android.opengl.Matrix.setIdentityM(identityMatrix, 0);
        }

        int getVideoTextureId() {
            return videoTextureId;
        }

        void surfaceCreated() {
            externalProgram = createProgram(VERTEX_SHADER, FRAGMENT_EXTERNAL);
            overlayProgram = createProgram(VERTEX_SHADER, FRAGMENT_2D);
            int[] textures = new int[2];
            GLES20.glGenTextures(2, textures, 0);
            videoTextureId = textures[0];
            overlayTextureId = textures[1];

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        void drawVideoFrame(float[] stMatrix) {
            GLES20.glUseProgram(externalProgram);
            int maPositionHandle = GLES20.glGetAttribLocation(externalProgram, "aPosition");
            int maTextureHandle = GLES20.glGetAttribLocation(externalProgram, "aTextureCoord");
            int muMVPMatrixHandle = GLES20.glGetUniformLocation(externalProgram, "uMVPMatrix");
            int muSTMatrixHandle = GLES20.glGetUniformLocation(externalProgram, "uSTMatrix");

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId);
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, identityMatrix, 0);
            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, stMatrix, 0);

            GLES20.glEnableVertexAttribArray(maPositionHandle);
            GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(maTextureHandle);
            GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 0, videoTexBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(maPositionHandle);
            GLES20.glDisableVertexAttribArray(maTextureHandle);
        }

        void drawOverlay(Bitmap overlay) {
            GLES20.glUseProgram(overlayProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
            if (!overlayTextureInitialized) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay, 0);
                overlayTextureInitialized = true;
            } else {
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, overlay);
            }

            int maPositionHandle = GLES20.glGetAttribLocation(overlayProgram, "aPosition");
            int maTextureHandle = GLES20.glGetAttribLocation(overlayProgram, "aTextureCoord");
            int muMVPMatrixHandle = GLES20.glGetUniformLocation(overlayProgram, "uMVPMatrix");
            int muSTMatrixHandle = GLES20.glGetUniformLocation(overlayProgram, "uSTMatrix");

            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, identityMatrix, 0);
            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, identityMatrix, 0);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glEnableVertexAttribArray(maPositionHandle);
            GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(maTextureHandle);
            GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 0, overlayTexBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(maPositionHandle);
            GLES20.glDisableVertexAttribArray(maTextureHandle);
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        private static float[] videoTexCoordsForRotation(int rotation) {
            int r = ((rotation % 360) + 360) % 360;
            if (r == 90) {
                return new float[]{0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f};
            } else if (r == 180) {
                return new float[]{1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f};
            } else if (r == 270) {
                return new float[]{1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f};
            }
            return new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
        }

        private static java.nio.FloatBuffer createFloatBuffer(float[] coords) {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(coords.length * 4);
            bb.order(java.nio.ByteOrder.nativeOrder());
            java.nio.FloatBuffer fb = bb.asFloatBuffer();
            fb.put(coords);
            fb.position(0);
            return fb;
        }

        private static int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int program = GLES20.glCreateProgram();
            if (program == 0) throw new RuntimeException("GL program oluşturulamadı");
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, pixelShader);
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                String log = GLES20.glGetProgramInfoLog(program);
                GLES20.glDeleteProgram(program);
                throw new RuntimeException("GL program link hatası: " + log);
            }
            return program;
        }

        private static int loadShader(int shaderType, String source) {
            int shader = GLES20.glCreateShader(shaderType);
            if (shader == 0) throw new RuntimeException("GL shader oluşturulamadı");
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new RuntimeException("GL shader compile hatası: " + log);
            }
            return shader;
        }
    }

    private static final class NativeMp4Writer {
        private static final String MIME_VIDEO = "video/avc";
        private final Context context;
        private final Uri sourceVideoUri;
        private final int displayWidth;
        private final int displayHeight;
        private final int encodeWidth;
        private final int encodeHeight;
        private final int fps;
        private final long durationUs;
        private final String fileName;
        private final EncoderChoice encoderChoice;
        private final int orientationHint;
        private final boolean rotatePortraitForEncoder;
        private MediaCodec encoder;
        private MediaMuxer muxer;
        private MediaCodec.BufferInfo bufferInfo;
        private int videoTrackIndex = -1;
        private int audioTrackIndex = -1;
        private boolean muxerStarted = false;
        private Uri outputUri;
        private File outputFile;
        private ParcelFileDescriptor outputPfd;
        private byte[] yuvBuffer;
        private int[] argbBuffer;
        private ExecutorService conversionExecutor;
        private int conversionThreads;
        private Bitmap rotatedBitmap;
        private Canvas rotatedCanvas;
        private final Paint rotatePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

        NativeMp4Writer(Context context, Uri sourceVideoUri, int width, int height, int fps, long durationUs, String fileName) throws Exception {
            this.context = context.getApplicationContext();
            this.sourceVideoUri = sourceVideoUri;
            this.displayWidth = width;
            this.displayHeight = height;
            this.fps = fps;
            this.durationUs = durationUs;
            this.fileName = fileName;

            EncoderChoice normal = chooseEncoder(width, height, fps, true);
            if (normal != null) {
                this.encodeWidth = width;
                this.encodeHeight = height;
                this.orientationHint = 0;
                this.rotatePortraitForEncoder = false;
                this.encoderChoice = normal;
            } else if (height > width) {
                int safeTransposedWidth = height;
                int safeTransposedHeight = alignUp(width, 16);
                EncoderChoice transposed = chooseEncoder(safeTransposedWidth, safeTransposedHeight, fps, true);
                if (transposed == null) transposed = chooseEncoder(safeTransposedWidth, safeTransposedHeight, fps, false);
                if (transposed == null) throw new IllegalStateException("H.264 60 FPS encoder bulunamadı");
                this.encodeWidth = safeTransposedWidth;
                this.encodeHeight = safeTransposedHeight;
                this.orientationHint = 90;
                this.rotatePortraitForEncoder = true;
                this.encoderChoice = transposed;
            } else {
                int safeWidth = alignUp(width, 16);
                int safeHeight = alignUp(height, 16);
                EncoderChoice fallback = chooseEncoder(safeWidth, safeHeight, fps, true);
                if (fallback == null) fallback = chooseEncoder(safeWidth, safeHeight, fps, false);
                if (fallback == null) throw new IllegalStateException("H.264 encoder bulunamadı");
                this.encodeWidth = safeWidth;
                this.encodeHeight = safeHeight;
                this.orientationHint = 0;
                this.rotatePortraitForEncoder = false;
                this.encoderChoice = fallback;
            }
        }

        void start() throws Exception {
            createOutputTarget();
            muxer = createMuxer();
            if (orientationHint != 0) {
                try { muxer.setOrientationHint(orientationHint); } catch (Exception ignored) {}
            }
            addAudioTrackIfAvailable();

            int bitRate = Math.max(45_000_000, Math.min(80_000_000, encodeWidth * encodeHeight * fps * 3 / 5));
            MediaFormat format = MediaFormat.createVideoFormat(MIME_VIDEO, encodeWidth, encodeHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, encoderChoice.colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, Math.max(fps, fps * 2));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                format.setInteger(MediaFormat.KEY_LATENCY, 0);
            }

            encoder = MediaCodec.createByCodecName(encoderChoice.codecName);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            bufferInfo = new MediaCodec.BufferInfo();
            yuvBuffer = new byte[encodeWidth * encodeHeight * 3 / 2];
            argbBuffer = new int[encodeWidth * encodeHeight];
            conversionThreads = Math.max(6, Math.min(24, Runtime.getRuntime().availableProcessors() * 2));
            conversionExecutor = Executors.newFixedThreadPool(conversionThreads, new ThreadFactory() {
                private int index = 1;
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "10KoliklerYuv" + (index++));
                    thread.setPriority(Thread.MAX_PRIORITY);
                    return thread;
                }
            });
        }

        void writeFrame(Bitmap bitmap, long presentationTimeUs) throws Exception {
            Bitmap encodeBitmap = bitmap;
            if (rotatePortraitForEncoder) {
                if (rotatedBitmap == null) {
                    rotatedBitmap = Bitmap.createBitmap(encodeWidth, encodeHeight, Bitmap.Config.ARGB_8888);
                    rotatedCanvas = new Canvas(rotatedBitmap);
                }
                rotatedCanvas.drawColor(Color.BLACK);
                rotatedCanvas.save();
                rotatedCanvas.translate(encodeWidth, 0);
                rotatedCanvas.rotate(90);
                rotatedCanvas.drawBitmap(bitmap, 0, 0, rotatePaint);
                rotatedCanvas.restore();
                encodeBitmap = rotatedBitmap;
            } else if (encodeWidth != bitmap.getWidth() || encodeHeight != bitmap.getHeight()) {
                if (rotatedBitmap == null) {
                    rotatedBitmap = Bitmap.createBitmap(encodeWidth, encodeHeight, Bitmap.Config.ARGB_8888);
                    rotatedCanvas = new Canvas(rotatedBitmap);
                }
                rotatedCanvas.drawColor(Color.BLACK);
                rotatedCanvas.drawBitmap(bitmap, 0, 0, rotatePaint);
                encodeBitmap = rotatedBitmap;
            }

            bitmapToYuv420(encodeBitmap, yuvBuffer, argbBuffer, encodeWidth, encodeHeight, encoderChoice.colorFormat, conversionExecutor, conversionThreads);
            int inputIndex = encoder.dequeueInputBuffer(-1);
            if (inputIndex < 0) throw new IllegalStateException("Encoder input alınamadı");
            ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
            if (inputBuffer == null) throw new IllegalStateException("Encoder input boş");
            if (inputBuffer.capacity() < yuvBuffer.length) {
                throw new IllegalStateException("Encoder buffer küçük: " + inputBuffer.capacity() + "/" + yuvBuffer.length);
            }
            inputBuffer.clear();
            inputBuffer.put(yuvBuffer);
            encoder.queueInputBuffer(inputIndex, 0, yuvBuffer.length, Math.max(0, presentationTimeUs), 0);
            drainEncoder(false);
        }

        void finish() throws Exception {
            if (encoder != null) {
                int inputIndex = encoder.dequeueInputBuffer(-1);
                if (inputIndex >= 0) encoder.queueInputBuffer(inputIndex, 0, 0, durationUs + 1_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                drainEncoder(true);
            }
            copyAudioSamplesIfAvailable();
            releaseEncoder();
            releaseMuxerSuccess();
        }

        void abort() {
            releaseEncoder();
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) {}
            muxer = null;
            try { if (outputPfd != null) outputPfd.close(); } catch (Exception ignored) {}
            outputPfd = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                    context.getContentResolver().delete(outputUri, null, null);
                } else if (outputFile != null && outputFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    outputFile.delete();
                }
            } catch (Exception ignored) {}
        }

        private void drainEncoder(boolean endOfStream) throws Exception {
            while (true) {
                int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, endOfStream ? 10_000 : 0);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) break;
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IllegalStateException("Video formatı iki kez değişti");
                    MediaFormat newFormat = encoder.getOutputFormat();
                    videoTrackIndex = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted = true;
                } else if (outputIndex >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(outputIndex);
                    if (encodedData == null) throw new IllegalStateException("Encoder output boş");
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufferInfo.size = 0;
                    if (bufferInfo.size != 0) {
                        if (!muxerStarted) throw new IllegalStateException("Muxer başlamadı");
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                    }
                    boolean sawEos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(outputIndex, false);
                    if (sawEos) break;
                }
            }
        }

        private void createOutputTarget() throws Exception {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AltyaziAraci");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                outputUri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (outputUri == null) throw new IllegalStateException("Video dosyası oluşturulamadı");
                outputPfd = context.getContentResolver().openFileDescriptor(outputUri, "w");
                if (outputPfd == null) throw new IllegalStateException("Video dosyası açılamadı");
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AltyaziAraci");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Klasör oluşturulamadı");
                outputFile = new File(dir, fileName);
            }
        }

        private MediaMuxer createMuxer() throws Exception {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && outputPfd != null) {
                return new MediaMuxer(outputPfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }
            if (outputFile == null) throw new IllegalStateException("Çıktı yolu yok");
            return new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        private void addAudioTrackIfAvailable() {
            if (sourceVideoUri == null || muxer == null) return;
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(context, sourceVideoUri, new HashMap<String, String>());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrackIndex = muxer.addTrack(format);
                        break;
                    }
                }
            } catch (Exception ignored) {
                audioTrackIndex = -1;
            } finally {
                extractor.release();
            }
        }

        private void copyAudioSamplesIfAvailable() {
            if (sourceVideoUri == null || muxer == null || audioTrackIndex < 0 || !muxerStarted) return;
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(context, sourceVideoUri, new HashMap<String, String>());
                int audioTrack = -1;
                int maxInputSize = 256 * 1024;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        audioTrack = i;
                        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            maxInputSize = Math.max(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                        }
                        break;
                    }
                }
                if (audioTrack < 0) return;
                extractor.selectTrack(audioTrack);
                ByteBuffer buffer = ByteBuffer.allocateDirect(maxInputSize);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (true) {
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) break;
                    long sampleTime = extractor.getSampleTime();
                    if (sampleTime < 0 || sampleTime > durationUs + 50_000L) break;
                    info.set(0, sampleSize, Math.max(0, sampleTime), extractor.getSampleFlags());
                    muxer.writeSampleData(audioTrackIndex, buffer, info);
                    extractor.advance();
                }
            } catch (Exception ignored) {
                // Video-only output is still valid if audio copy fails.
            } finally {
                extractor.release();
            }
        }

        private void releaseEncoder() {
            try { if (conversionExecutor != null) conversionExecutor.shutdownNow(); } catch (Exception ignored) {}
            conversionExecutor = null;
            if (rotatedBitmap != null) {
                try { rotatedBitmap.recycle(); } catch (Exception ignored) {}
                rotatedBitmap = null;
                rotatedCanvas = null;
            }
            try { if (encoder != null) encoder.stop(); } catch (Exception ignored) {}
            try { if (encoder != null) encoder.release(); } catch (Exception ignored) {}
            encoder = null;
        }

        private void releaseMuxerSuccess() throws Exception {
            try { if (muxer != null) muxer.stop(); } catch (Exception ignored) {}
            try { if (muxer != null) muxer.release(); } finally { muxer = null; }
            if (outputPfd != null) {
                try { outputPfd.close(); } catch (Exception ignored) {}
                outputPfd = null;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                context.getContentResolver().update(outputUri, values, null, null);
            } else if (outputFile != null) {
                MediaScannerConnection.scanFile(context, new String[]{outputFile.getAbsolutePath()}, new String[]{"video/mp4"}, null);
            }
        }

        private static void bitmapToYuv420(Bitmap bitmap, byte[] out, int[] argb, int width, int height, int colorFormat, ExecutorService executor, int threads) throws Exception {
            bitmap.getPixels(argb, 0, width, 0, 0, width, height);
            if (executor == null || threads <= 1 || height < 360) {
                convertRowsToYuv(argb, out, width, height, colorFormat, 0, height);
                return;
            }

            int workerCount = Math.max(2, Math.min(threads, height / 2));
            CountDownLatch latch = new CountDownLatch(workerCount);
            AtomicReference<Exception> error = new AtomicReference<>();

            for (int part = 0; part < workerCount; part++) {
                int startRow = (part * height) / workerCount;
                int endRow = ((part + 1) * height) / workerCount;
                startRow &= ~1;
                if (part < workerCount - 1) endRow &= ~1;
                final int from = Math.max(0, Math.min(height, startRow));
                final int to = Math.max(from, Math.min(height, endRow));
                executor.execute(() -> {
                    try {
                        convertRowsToYuv(argb, out, width, height, colorFormat, from, to);
                    } catch (Exception e) {
                        error.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            if (error.get() != null) throw error.get();
        }

        private static void convertRowsToYuv(int[] argb, byte[] out, int width, int height, int colorFormat, int startRow, int endRow) {
            int frameSize = width * height;
            boolean planar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
            int halfWidth = width / 2;

            for (int j = startRow; j < endRow; j++) {
                int rowOffset = j * width;
                int yIndex = rowOffset;
                boolean evenRow = (j & 1) == 0;
                int uvBase = frameSize + (j / 2) * width;
                int uBase = frameSize + (j / 2) * halfWidth;
                int vBase = frameSize + frameSize / 4 + (j / 2) * halfWidth;

                for (int i = 0; i < width; i++) {
                    int c = argb[rowOffset + i];
                    int r = (c >> 16) & 0xff;
                    int g = (c >> 8) & 0xff;
                    int b = c & 0xff;
                    int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                    out[yIndex++] = (byte) clamp(y);

                    if (evenRow && ((i & 1) == 0)) {
                        int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                        int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                        if (planar) {
                            int chromaIndex = i / 2;
                            out[uBase + chromaIndex] = (byte) clamp(u);
                            out[vBase + chromaIndex] = (byte) clamp(v);
                        } else {
                            int chromaIndex = uvBase + i;
                            out[chromaIndex] = (byte) clamp(u);
                            out[chromaIndex + 1] = (byte) clamp(v);
                        }
                    }
                }
            }
        }

        private static EncoderChoice chooseEncoder(int width, int height, int fps, boolean requireSizeRate) {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (int pass = 0; pass < 2; pass++) {
                boolean hardwareOnly = pass == 0;
                for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
                    if (!codecInfo.isEncoder()) continue;
                    if (hardwareOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && codecInfo.isSoftwareOnly()) continue;
                    try {
                        boolean supportsMime = false;
                        for (String type : codecInfo.getSupportedTypes()) {
                            if (MIME_VIDEO.equalsIgnoreCase(type)) {
                                supportsMime = true;
                                break;
                            }
                        }
                        if (!supportsMime) continue;
                        MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(MIME_VIDEO);
                        if (requireSizeRate && !supportsSizeRate(caps, width, height, fps)) continue;
                        int colorFormat = pickColorFormat(caps);
                        if (colorFormat > 0) return new EncoderChoice(codecInfo.getName(), colorFormat);
                    } catch (Exception ignored) {}
                }
            }
            return null;
        }

        private static int pickColorFormat(MediaCodecInfo.CodecCapabilities caps) {
            int fallback = -1;
            for (int color : caps.colorFormats) {
                if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return color;
                if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) fallback = color;
            }
            return fallback;
        }

        private static boolean supportsSizeRate(MediaCodecInfo.CodecCapabilities caps, int width, int height, int fps) {
            try {
                MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                int widthAlignment = Math.max(1, videoCaps.getWidthAlignment());
                int heightAlignment = Math.max(1, videoCaps.getHeightAlignment());
                if ((width % widthAlignment) != 0 || (height % heightAlignment) != 0) return false;
                Range<Integer> widths = videoCaps.getSupportedWidths();
                Range<Integer> heights = videoCaps.getSupportedHeights();
                if (!widths.contains(width) || !heights.contains(height)) return false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    return videoCaps.areSizeAndRateSupported(width, height, Math.max(1, fps));
                }
                return true;
            } catch (Exception ignored) {
                return true;
            }
        }

        private static int alignTo(int value, int alignment) {
            if (alignment <= 1) return value;
            return value;
        }

        private static int alignUp(int value, int alignment) {
            if (alignment <= 1) return value;
            return ((value + alignment - 1) / alignment) * alignment;
        }

        private static int clamp(int value) {
            return value < 0 ? 0 : Math.min(value, 255);
        }

        private static final class EncoderChoice {
            final String codecName;
            final int colorFormat;
            EncoderChoice(String codecName, int colorFormat) {
                this.codecName = codecName;
                this.colorFormat = colorFormat;
            }
        }
    }

}