package com.sinanjams.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.provider.OpenableColumns;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class AiSrtService extends Service {
    public static final String ACTION_START_AI_SRT = "com.sinanjams.app.START_AI_SRT";
    public static final String ACTION_CANCEL_AI_SRT = "com.sinanjams.app.CANCEL_AI_SRT";
    public static final String ACTION_SRT_READY = "com.sinanjams.app.SRT_READY";
    public static final String EXTRA_VIDEO_URI = "video_uri";
    public static final String EXTRA_API_KEY = "api_key";
    public static final String EXTRA_SKIP_SIZE_LIMIT = "skip_size_limit";
    public static final String EXTRA_SRT_TEXT = "srt_text";

    private static final String CHANNEL_ID = "ai_srt_progress";
    private static final int NOTIFICATION_ID = 3007;
    private static final long MAX_AUDIO_BYTES = 25L * 1024L * 1024L;
    private static final String ACCURACY_MODEL = "whisper-large-v3";
    private static final String PREFS_NAME = "10kolikler_prefs";
    private static final String PREF_PENDING_SRT = "pending_ai_srt";

    private volatile boolean cancelled = false;
    private Thread workerThread;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private volatile HttpURLConnection activeConnection;

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
        if (ACTION_CANCEL_AI_SRT.equals(action)) {
            cancelled = true;
            HttpURLConnection conn = activeConnection;
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
            Thread t = workerThread;
            if (t != null) {
                try { t.interrupt(); } catch (Exception ignored) {}
            }
            updateNotification(0, "AI SRT iptal ediliyor...", false, true);
            return START_NOT_STICKY;
        }

        if (!ACTION_START_AI_SRT.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (workerThread != null && workerThread.isAlive()) {
            updateNotification(0, "AI SRT hazırlanıyor...", false, false);
            return START_STICKY;
        }

        String videoUriString = intent.getStringExtra(EXTRA_VIDEO_URI);
        String apiKey = intent.getStringExtra(EXTRA_API_KEY);
        boolean skipSizeLimit = intent.getBooleanExtra(EXTRA_SKIP_SIZE_LIMIT, false);
        if (videoUriString == null || videoUriString.trim().isEmpty()
                || apiKey == null || apiKey.trim().isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        cancelled = false;
        startForegroundCompat(0, "AI SRT hazırlanıyor...");

        workerThread = new Thread(() -> {
            File audioFile = null;
            try {
                try { Process.setThreadPriority(-10); } catch (Exception ignored) {}
                acquireWakeLock();
                Uri videoUri = Uri.parse(videoUriString);

                updateNotification(5, "Ses hazırlanıyor...", false, false);
                audioFile = extractAudioToM4a(videoUri);
                checkCancelled();

                long audioSize = audioFile.length();
                if (audioSize <= 0) {
                    throw new Exception("Ses çıkarılamadı");
                }
                if (!skipSizeLimit && audioSize >= MAX_AUDIO_BYTES) {
                    throw new Exception("Ses 25 MB altında olmalı. Premium/özel API varsa 25 MB kontrolünü kapatabilirsin");
                }

                String[] scanJsons = new String[3];
                int[] progressStarts = {12, 38, 64};
                int[] progressRanges = {22, 22, 18};
                for (int scan = 0; scan < 3; scan++) {
                    checkCancelled();
                    updateNotification(progressStarts[scan], "AI doğruluk taraması " + (scan + 1) + "/3", false, false);
                    scanJsons[scan] = uploadAudioToGroq(audioFile, apiKey.trim(), scan, progressStarts[scan], progressRanges[scan]);
                }

                updateNotification(88, "3 tarama karşılaştırılıyor...", false, false);
                String srt = buildTripleScanSrt(scanJsons);
                if (srt.trim().isEmpty()) {
                    throw new Exception("SRT oluşturulamadı");
                }

                saveAndBroadcastSrt(srt);
                updateNotification(100, "AI SRT hazır", true, false);
            } catch (InterruptedException e) {
                updateNotification(0, "AI SRT iptal edildi", true, false);
            } catch (Exception e) {
                updateNotification(0, "AI SRT hata: " + safeMessage(e), true, false);
            } finally {
                if (audioFile != null) {
                    try { audioFile.delete(); } catch (Exception ignored) {}
                }
                releaseWakeLock();
                activeConnection = null;
                detachForegroundNotification();
                workerThread = null;
                stopSelfResult(startId);
            }
        }, "10KoliklerAiSrtThread");
        workerThread.start();
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        cancelled = true;
        HttpURLConnection conn = activeConnection;
        if (conn != null) {
            try { conn.disconnect(); } catch (Exception ignored) {}
        }
        Thread t = workerThread;
        if (t != null) {
            try { t.interrupt(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private File extractAudioToM4a(Uri videoUri) throws Exception {
        File out = new File(getCacheDir(), "ai_srt_audio_" + System.currentTimeMillis() + ".m4a");
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        ParcelFileDescriptor pfd = null;
        boolean muxerStarted = false;
        try {
            pfd = getContentResolver().openFileDescriptor(videoUri, "r");
            if (pfd == null) throw new Exception("Video açılamadı");
            extractor.setDataSource(pfd.getFileDescriptor());

            int audioTrack = -1;
            MediaFormat audioFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.containsKey(MediaFormat.KEY_MIME) ? format.getString(MediaFormat.KEY_MIME) : "";
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    audioFormat = format;
                    break;
                }
            }
            if (audioTrack < 0 || audioFormat == null) throw new Exception("Videoda ses bulunamadı");

            extractor.selectTrack(audioTrack);
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outTrack = muxer.addTrack(audioFormat);
            muxer.start();
            muxerStarted = true;

            int maxInputSize = 1024 * 1024;
            if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                maxInputSize = Math.max(maxInputSize, audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect(maxInputSize);
            MediaCodecBufferInfoCompat info = new MediaCodecBufferInfoCompat();

            while (!cancelled) {
                buffer.clear();
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;
                info.offset = 0;
                info.size = sampleSize;
                info.presentationTimeUs = extractor.getSampleTime();
                int sampleFlags = extractor.getSampleFlags();
                info.flags = sampleFlags;
                muxer.writeSampleData(outTrack, buffer, info.toCodecBufferInfo());
                extractor.advance();
            }
            checkCancelled();
            return out;
        } catch (IllegalArgumentException muxerUnsupported) {
            try { out.delete(); } catch (Exception ignored) {}
            throw new Exception("Ses formatı desteklenmedi");
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
            if (muxer != null) {
                try {
                    if (muxerStarted) muxer.stop();
                } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
        }
    }

    private String uploadAudioToGroq(File audioFile, String apiKey, int scanIndex, int progressStart, int progressRange) throws Exception {
        String boundary = "----10Kolikler" + UUID.randomUUID().toString().replace("-", "");
        URL url = new URL("https://api.groq.com/openai/v1/audio/transcriptions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        activeConnection = conn;
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(180000);
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setChunkedStreamingMode(64 * 1024);

        try (OutputStream os = conn.getOutputStream()) {
            writeFormField(os, boundary, "model", ACCURACY_MODEL);
            writeFormField(os, boundary, "language", "tr");
            writeFormField(os, boundary, "response_format", "verbose_json");
            writeFormField(os, boundary, "temperature", "0");
            String prompt = buildPromptForScan(scanIndex);
            writeFormField(os, boundary, "prompt", prompt);
            writeFormField(os, boundary, "timestamp_granularities[]", "segment");
            writeFilePart(os, boundary, "file", audioFile.getName(), "audio/mp4", audioFile, progressStart, progressRange);
            writeString(os, "--" + boundary + "--\r\n");
            os.flush();
        }

        int code = conn.getResponseCode();
        InputStream input = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String response = readAll(input);
        if (code < 200 || code >= 300) {
            throw new Exception("API " + code + ": " + compactApiError(response));
        }
        return response;
    }

    private String buildPromptForScan(int scanIndex) {
        String base = "Bu bir Türkçe transkripsiyon görevidir. Ses konuşma, şarkı, slogan, kalabalık vokali, kısa ünlem, tekrar, mizah, tanıtım veya günlük diyalog içerebilir. Sadece futbol odaklı düşünme. Duyulan her Türkçe kelimeyi Türkçe imla kurallarına uygun yaz. Türkçe karakter kullan. Duyulmayan şeyi uydurma. Anlaşılmayan yerde duyulana en yakın kısa yazımı kullan. Sözsüz ama insan sesi olan bölümleri de yaz: laylay, la la la, ooo ooo, heey, hey, hop, aaaa gibi. Gereksiz açıklama ekleme.";
        if (scanIndex == 1) {
            return base + " İkinci taramada özellikle ilk dinleyişte atlanabilecek kısımlara odaklan: düşük sesli kelimeler, arka plan vokalleri, nakaratlar, tekrar eden sesler, hızlı söylenen Türkçe kelimeler ve kalabalık içinden gelen insan sesleri.";
        }
        if (scanIndex == 2) {
            return base + " Üçüncü taramada metni daha dikkatli dinle: Türkçe imla, kelime ayrımı, tekrarlar, ünlemler ve zamanlamaya dikkat et. Konuşma dışı insan vokallerini sessizlik sanma; duyuluyorsa yaz.";
        }
        return base + " İlk taramada ana konuşmayı ve belirgin vokalleri eksiksiz çıkar.";
    }

    private String buildTripleScanSrt(String[] jsons) throws Exception {
        List<List<SrtSegment>> scans = new ArrayList<>();
        for (String json : jsons) {
            scans.add(parseSegments(json));
        }

        int baseIndex = 0;
        int bestScore = -1;
        for (int i = 0; i < scans.size(); i++) {
            int score = scoreSegments(scans.get(i));
            if (score > bestScore) {
                bestScore = score;
                baseIndex = i;
            }
        }

        List<SrtSegment> base = scans.get(baseIndex);
        if (base.isEmpty()) {
            String fallback = "";
            for (String json : jsons) {
                String srt = jsonToSrt(json);
                if (scoreSrt(srt) > scoreSrt(fallback)) fallback = srt;
            }
            return fallback;
        }

        StringBuilder out = new StringBuilder();
        int srtIndex = 1;
        for (SrtSegment seg : base) {
            if (seg.text.trim().isEmpty()) continue;
            List<String> comparisonTexts = new ArrayList<>();
            for (int i = 0; i < scans.size(); i++) {
                if (i == baseIndex) continue;
                SrtSegment match = findBestMatch(seg, scans.get(i));
                if (match != null && !match.text.trim().isEmpty()) {
                    comparisonTexts.add(match.text);
                }
            }
            String markedText = markSuspiciousWords(seg.text, comparisonTexts);
            out.append(srtIndex++).append('\n');
            out.append(formatSrtTime(seg.start)).append(" --> ").append(formatSrtTime(seg.end)).append('\n');
            out.append(markedText).append("\n\n");
        }
        return out.toString().trim();
    }

    private List<SrtSegment> parseSegments(String json) throws Exception {
        List<SrtSegment> result = new ArrayList<>();
        JSONObject root = new JSONObject(json == null ? "{}" : json);
        JSONArray segments = root.optJSONArray("segments");
        if (segments != null && segments.length() > 0) {
            for (int i = 0; i < segments.length(); i++) {
                JSONObject seg = segments.getJSONObject(i);
                double start = seg.optDouble("start", 0.0);
                double end = seg.optDouble("end", start + 2.0);
                String text = cleanText(seg.optString("text", ""));
                if (!text.isEmpty()) result.add(new SrtSegment(start, end, text));
            }
        } else {
            String text = cleanText(root.optString("text", ""));
            if (!text.isEmpty()) result.add(new SrtSegment(0.0, 5.0, text));
        }
        return result;
    }

    private int scoreSegments(List<SrtSegment> segments) {
        int total = 0;
        if (segments == null) return 0;
        for (SrtSegment seg : segments) {
            total += scoreText(seg.text);
            if (seg.end > seg.start) total += 2;
        }
        return total;
    }

    private int scoreText(String text) {
        if (text == null) return 0;
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) letters++;
        }
        String lower = text.toLowerCase(new Locale("tr", "TR"));
        String[] vocalTokens = {"lay", "la la", "ooo", "hey", "heey", "hop", "aaa", "of", "ah"};
        for (String token : vocalTokens) {
            if (lower.contains(token)) letters += 10;
        }
        return letters;
    }

    private SrtSegment findBestMatch(SrtSegment target, List<SrtSegment> candidates) {
        if (target == null || candidates == null || candidates.isEmpty()) return null;
        SrtSegment best = null;
        double bestScore = -999999.0;
        double targetCenter = (target.start + target.end) / 2.0;
        double targetDuration = Math.max(0.4, target.end - target.start);
        for (SrtSegment c : candidates) {
            double overlap = Math.max(0.0, Math.min(target.end, c.end) - Math.max(target.start, c.start));
            double center = (c.start + c.end) / 2.0;
            double centerDiff = Math.abs(targetCenter - center);
            boolean acceptable = overlap >= Math.min(0.7, targetDuration * 0.25) || centerDiff <= Math.max(1.5, targetDuration * 0.55);
            if (!acceptable) continue;
            double score = overlap * 4.0 - centerDiff;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private String markSuspiciousWords(String baseText, List<String> comparisonTexts) {
        String text = cleanText(baseText);
        if (text.isEmpty()) return "";

        Set<String> evidence = new HashSet<>();
        for (String comparison : comparisonTexts) {
            for (String token : splitTokens(comparison)) {
                String norm = normalizeToken(token);
                if (!norm.isEmpty()) evidence.add(norm);
            }
        }

        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                out.append(ch);
                i++;
                continue;
            }
            int start = i;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
            String token = text.substring(start, i);
            String norm = normalizeToken(token);
            boolean suspicious = isSignificantToken(norm) && (evidence.isEmpty() || !hasEvidence(norm, evidence));
            if (suspicious) {
                out.append('⟦').append(token).append('⟧');
            } else {
                out.append(token);
            }
        }
        return out.toString();
    }

    private List<String> splitTokens(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) return tokens;
        int i = 0;
        while (i < text.length()) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            int start = i;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
            if (start < i) tokens.add(text.substring(start, i));
        }
        return tokens;
    }

    private boolean hasEvidence(String norm, Set<String> evidence) {
        if (evidence.contains(norm)) return true;
        for (String other : evidence) {
            if (norm.length() >= 5 && other.length() >= 5) {
                if (norm.startsWith(other) || other.startsWith(norm)) return true;
            }
            if (norm.length() >= 6 && other.length() >= 6) {
                int min = Math.min(norm.length(), other.length());
                int samePrefix = 0;
                while (samePrefix < min && norm.charAt(samePrefix) == other.charAt(samePrefix)) samePrefix++;
                if (samePrefix >= Math.max(4, min - 2)) return true;
            }
        }
        return false;
    }

    private String normalizeToken(String token) {
        if (token == null) return "";
        String lower = token.toLowerCase(new Locale("tr", "TR"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isLetterOrDigit(ch)) sb.append(ch);
        }
        return sb.toString();
    }

    private boolean isSignificantToken(String norm) {
        if (norm == null || norm.length() < 3) return false;
        String[] common = {"bir", "bu", "şu", "ile", "ama", "çok", "daha", "gibi", "olan", "için", "ben", "sen", "biz", "siz", "var", "yok", "evet", "hayır", "de", "da", "ki", "mi", "mı", "mu", "mü"};
        for (String c : common) {
            if (c.equals(norm)) return false;
        }
        return true;
    }

    private void writeFormField(OutputStream os, String boundary, String name, String value) throws Exception {
        checkCancelled();
        writeString(os, "--" + boundary + "\r\n");
        writeString(os, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        writeString(os, value + "\r\n");
    }

    private void writeFilePart(OutputStream os, String boundary, String name, String filename, String mime, File file, int progressStart, int progressRange) throws Exception {
        checkCancelled();
        writeString(os, "--" + boundary + "\r\n");
        writeString(os, "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n");
        writeString(os, "Content-Type: " + mime + "\r\n\r\n");
        byte[] buf = new byte[64 * 1024];
        long total = file.length();
        long sent = 0;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int read;
            int lastProgress = progressStart;
            while ((read = in.read(buf)) != -1) {
                checkCancelled();
                os.write(buf, 0, read);
                sent += read;
                if (total > 0) {
                    int progress = progressStart + (int) Math.min(progressRange, Math.round((sent * (double) progressRange) / total));
                    if (progress != lastProgress) {
                        updateNotification(progress, "AI doğruluk modu gönderiliyor...", false, false);
                        lastProgress = progress;
                    }
                }
            }
        }
        writeString(os, "\r\n");
    }

    private static void writeString(OutputStream os, String s) throws Exception {
        os.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private String jsonToSrt(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        StringBuilder sb = new StringBuilder();
        JSONArray segments = root.optJSONArray("segments");
        if (segments != null && segments.length() > 0) {
            for (int i = 0; i < segments.length(); i++) {
                JSONObject seg = segments.getJSONObject(i);
                double start = seg.optDouble("start", 0.0);
                double end = seg.optDouble("end", start + 2.0);
                String text = cleanText(seg.optString("text", ""));
                if (text.isEmpty()) continue;
                sb.append(i + 1).append('\n');
                sb.append(formatSrtTime(start)).append(" --> ").append(formatSrtTime(end)).append('\n');
                sb.append(text).append("\n\n");
            }
        }
        if (sb.length() == 0) {
            String text = cleanText(root.optString("text", ""));
            if (!text.isEmpty()) {
                sb.append("1\n00:00:00,000 --> 00:00:05,000\n").append(text).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    private static String cleanText(String text) {
        if (text == null) return "";
        return text.replace("\r", "").trim();
    }

    private static String formatSrtTime(double seconds) {
        long ms = Math.max(0, Math.round(seconds * 1000.0));
        long h = ms / 3600000L;
        ms %= 3600000L;
        long m = ms / 60000L;
        ms %= 60000L;
        long s = ms / 1000L;
        long milli = ms % 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli);
    }


    private boolean shouldRetryForAccuracy(String json, String srt) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray segments = root.optJSONArray("segments");
            int segmentCount = segments == null ? 0 : segments.length();
            int score = scoreSrt(srt);
            String lower = srt == null ? "" : srt.toLowerCase(Locale.ROOT);
            boolean hasOnlyTinyResult = score < 24 || segmentCount == 0;
            boolean looksLikePlaceholder = lower.contains("[inaudible]") || lower.contains("transcribe") || lower.contains("altyazı");
            return hasOnlyTinyResult || looksLikePlaceholder;
        } catch (Exception ignored) {
            return true;
        }
    }

    private int scoreSrt(String srt) {
        if (srt == null) return 0;
        int letters = 0;
        int vocalTokens = 0;
        String lower = srt.toLowerCase(Locale.ROOT);
        String[] tokens = {"lay", "ooo", "hey", "heey", "hop", "la la", "aaaa"};
        for (String token : tokens) {
            int idx = lower.indexOf(token);
            while (idx >= 0) {
                vocalTokens++;
                idx = lower.indexOf(token, idx + token.length());
            }
        }
        for (int i = 0; i < srt.length(); i++) {
            if (Character.isLetterOrDigit(srt.charAt(i))) letters++;
        }
        return letters + vocalTokens * 12;
    }

    private void saveAndBroadcastSrt(String srt) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(PREF_PENDING_SRT, srt).apply();
        Intent ready = new Intent(ACTION_SRT_READY);
        ready.setPackage(getPackageName());
        ready.putExtra(EXTRA_SRT_TEXT, srt);
        sendBroadcast(ready);
    }

    private void startForegroundCompat(int progress, String text) {
        Notification notification = buildNotification(progress, text, false, false);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(int progress, String text, boolean done, boolean cancelling) {
        Notification notification = buildNotification(progress, text, done, cancelling);
        try { notificationManager.notify(NOTIFICATION_ID, notification); } catch (Exception ignored) {}
    }

    private Notification buildNotification(int progress, String text, boolean done, boolean cancelling) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                4001,
                launchIntent == null ? new Intent() : launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent cancelIntent = new Intent(this, AiSrtService.class);
        cancelIntent.setAction(ACTION_CANCEL_AI_SRT);
        PendingIntent cancelPendingIntent = PendingIntent.getService(
                this,
                4002,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(getNotificationIcon())
                .setContentTitle("Altyazı Aracı AI SRT")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(!done)
                .setOnlyAlertOnce(true)
                .setAutoCancel(done)
                .setPriority(Notification.PRIORITY_HIGH);

        if (!done) {
            builder.setProgress(100, Math.max(0, Math.min(100, progress)), progress <= 0 || cancelling);
            builder.addAction(getNotificationIcon(), "İptal", cancelPendingIntent);
        } else {
            builder.setProgress(0, 0, false);
        }
        return builder.build();
    }

    private int getNotificationIcon() {
        return getResources().getIdentifier("ic_stat_export", "drawable", getPackageName());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AI SRT",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("AI altyazı ilerlemesi");
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void detachForegroundNotification() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "10Kolikler:AiSrtWakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(20 * 60 * 1000L);
            }
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        wakeLock = null;
    }

    private void checkCancelled() throws InterruptedException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("iptal");
        }
    }

    private static String safeMessage(Exception e) {
        String msg = e == null ? null : e.getMessage();
        if (msg == null || msg.trim().isEmpty()) return "işlem başarısız";
        return msg.length() > 90 ? msg.substring(0, 90) : msg;
    }

    private static String compactApiError(String response) {
        if (response == null || response.trim().isEmpty()) return "yanıt yok";
        try {
            JSONObject obj = new JSONObject(response);
            JSONObject error = obj.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "");
                if (!message.isEmpty()) return message.length() > 120 ? message.substring(0, 120) : message;
            }
        } catch (Exception ignored) {}
        String compact = response.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() > 120 ? compact.substring(0, 120) : compact;
    }

    private static class SrtSegment {
        final double start;
        final double end;
        final String text;

        SrtSegment(double start, double end, String text) {
            this.start = start;
            this.end = Math.max(start + 0.2, end);
            this.text = text == null ? "" : text;
        }
    }

    private static class MediaCodecBufferInfoCompat {
        int offset;
        int size;
        long presentationTimeUs;
        int flags;

        android.media.MediaCodec.BufferInfo toCodecBufferInfo() {
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            info.set(offset, size, presentationTimeUs, flags);
            return info;
        }
    }
}
