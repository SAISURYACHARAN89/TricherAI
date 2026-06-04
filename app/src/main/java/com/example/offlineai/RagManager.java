package com.example.offlineai;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder;
import com.google.mediapipe.tasks.text.textembedder.TextEmbedderResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RagManager {
    private static final String TAG = "RagManager";
    private static final String EMBEDDING_MODEL_FILE = "universal_sentence_encoder.tflite";
    
    private TextEmbedder textEmbedder;
    private RagDatabaseHelper dbHelper;
    private boolean isInitialized = false;

    public static class DocumentChunk {
        public String text;
        public float[] embedding;

        public DocumentChunk(String text, float[] embedding) {
            this.text = text;
            this.embedding = embedding;
        }
    }

    public void init(Context context) {
        if (isInitialized) return;
        
        try {
            dbHelper = new RagDatabaseHelper(context);
            
            File modelFile = new File(context.getExternalFilesDir(null), EMBEDDING_MODEL_FILE);
            if (!modelFile.exists()) {
                Log.w(TAG, "Embedding model not found: " + EMBEDDING_MODEL_FILE);
                return;
            }

            TextEmbedder.TextEmbedderOptions options = TextEmbedder.TextEmbedderOptions.builder()
                    .setBaseOptions(BaseOptions.builder()
                            .setModelAssetPath(modelFile.getAbsolutePath())
                            .build())
                    .build();

            textEmbedder = TextEmbedder.createFromOptions(context, options);
            isInitialized = true;
            Log.i(TAG, "RagManager initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize RagManager", e);
        }
    }

    public boolean isReady() {
        return isInitialized && textEmbedder != null;
    }

    public void processDocument(Context context, Uri uri) {
        new Thread(() -> {
            try {
                String fileName = getFileName(context, uri);
                Log.i(TAG, "Processing document: " + fileName);
                
                String fullText = extractText(context, uri, fileName);
                if (fullText == null || fullText.isEmpty()) {
                    Log.w(TAG, "Extracted text is empty for: " + fileName);
                    return;
                }

                // Clean text
                fullText = cleanText(fullText);
                
                // Chunk text: 400 words size, 100 words overlap
                List<String> textChunks = chunkTextByWords(fullText, 400, 100);
                Log.i(TAG, "Created " + textChunks.size() + " chunks for " + fileName);

                long docId = dbHelper.insertDocument(fileName);

                for (String chunk : textChunks) {
                    if (chunk.trim().isEmpty()) continue;
                    TextEmbedderResult result = textEmbedder.embed(chunk);
                    float[] embedding = result.embeddingResult().embeddings().get(0).floatEmbedding();
                    dbHelper.insertChunk(docId, chunk, embedding);
                }
                
                Log.i(TAG, "Document processing complete: " + fileName);
                
            } catch (Exception e) {
                Log.e(TAG, "Error processing document", e);
            }
        }).start();
    }

    private String extractText(Context context, Uri uri, String fileName) throws Exception {
        InputStream is = context.getContentResolver().openInputStream(uri);
        if (is == null) return null;

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    private String cleanText(String text) {
        // Basic cleaning: normalize whitespace, remove non-printable characters
        return text.replaceAll("\\s+", " ").trim();
    }

    private List<String> chunkTextByWords(String text, int chunkSize, int overlap) {
        List<String> result = new ArrayList<>();
        String[] words = text.split("\\s+");
        
        int start = 0;
        while (start < words.length) {
            int end = Math.min(start + chunkSize, words.length);
            StringBuilder chunk = new StringBuilder();
            for (int i = start; i < end; i++) {
                chunk.append(words[i]).append(" ");
            }
            result.add(chunk.toString().trim());
            
            if (end == words.length) break;
            start += (chunkSize - overlap);
        }
        return result;
    }

    public String retrieve(String query, int topK) {
        if (!isReady()) return "";

        try {
            TextEmbedderResult queryResult = textEmbedder.embed(query);
            float[] queryEmbedding = queryResult.embeddingResult().embeddings().get(0).floatEmbedding();

            List<DocumentChunk> allChunks = dbHelper.getAllChunks();
            if (allChunks.isEmpty()) return "";

            List<ScoredChunk> scoredChunks = new ArrayList<>();
            for (DocumentChunk chunk : allChunks) {
                double similarity = cosineSimilarity(queryEmbedding, chunk.embedding);
                // Log top similarities for debugging
                if (similarity > 0.6) {
                    Log.d(TAG, "Retrieval match: " + similarity + " - " + chunk.text.substring(0, Math.min(50, chunk.text.length())));
                }
                scoredChunks.add(new ScoredChunk(chunk, similarity));
            }

            Collections.sort(scoredChunks, (a, b) -> Double.compare(b.score, a.score));

            StringBuilder context = new StringBuilder();
            int count = Math.min(topK, scoredChunks.size());
            for (int i = 0; i < count; i++) {
                context.append(scoredChunks.get(i).chunk.text).append("\n\n");
            }

            return context.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "Error during retrieval", e);
            return "";
        }
    }

    private double cosineSimilarity(float[] vectorA, float[] vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static class ScoredChunk {
        DocumentChunk chunk;
        double score;

        ScoredChunk(DocumentChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }
    }

    private String getFileName(Context context, Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                }
            } finally {
                if (cursor != null) cursor.close();
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }
    
    public void clear() {
        if (dbHelper != null) dbHelper.clearAll();
    }
}
