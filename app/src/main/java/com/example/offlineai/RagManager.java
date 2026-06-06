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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class RagManager {
    private static final String TAG = "RagManager";
    private static final String EMBEDDING_MODEL_FILE = "universal_sentence_encoder.tflite";
    private static final int TARGET_CHUNK_WORDS = 140;
    private static final int CHUNK_OVERLAP_UNITS = 1;
    private static final int LONG_UNIT_MAX_WORDS = 120;

    private TextEmbedder textEmbedder;
    private RagDatabaseHelper dbHelper;
    private boolean isInitialized = false;

    public static class DocumentChunk {
        public long docId;
        public String docName;
        public String text;
        public float[] embedding;
        public String normalizedText;

        public DocumentChunk(long docId, String docName, String text, float[] embedding) {
            this.docId = docId;
            this.docName = docName;
            this.text = text;
            this.embedding = embedding;
            this.normalizedText = normalizeForComparison(text);
        }
    }

    private static class QuestionAnswerMatch {
        String question;
        String answer;
        String docName;
        double score;

        QuestionAnswerMatch(String question, String answer, String docName, double score) {
            this.question = question;
            this.answer = answer;
            this.docName = docName;
            this.score = score;
        }
    }

    public interface DocumentProcessingListener {
        void onCompleted(boolean success, String fileName, String message);
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

    private void ensureDb(Context context) {
        if (dbHelper == null) {
            dbHelper = new RagDatabaseHelper(context.getApplicationContext());
        }
    }

    public void processDocument(Context context, Uri uri) {
        processDocument(context, uri, null);
    }

    public void processDocument(Context context, Uri uri, DocumentProcessingListener listener) {
        new Thread(() -> {
            String fileName = "document";
            try {
                ensureDb(context);
                fileName = getFileName(context, uri);
                Log.i(TAG, "Processing document: " + fileName);
                
                if (!isReady()) {
                    init(context);
                }
                if (!isReady()) {
                    if (listener != null) {
                        listener.onCompleted(false, fileName, "Embedding model is not ready.");
                    }
                    return;
                }

                String fullText = extractText(context, uri);
                if (fullText == null || fullText.isEmpty()) {
                    Log.w(TAG, "Extracted text is empty for: " + fileName);
                    if (listener != null) {
                        listener.onCompleted(false, fileName, "Selected document is empty.");
                    }
                    return;
                }

                // Clean text
                fullText = cleanText(fullText);
                
                // Replace previously imported copy of the same file to avoid duplicate retrievals.
                dbHelper.deleteDocumentByName(fileName);

                // Chunk text into smaller semantic units for higher precision retrieval.
                List<String> textChunks = chunkTextByStructure(fullText, TARGET_CHUNK_WORDS, CHUNK_OVERLAP_UNITS);
                Log.i(TAG, "Created " + textChunks.size() + " chunks for " + fileName);

                long docId = dbHelper.insertDocument(fileName);

                for (String chunk : textChunks) {
                    if (chunk.trim().isEmpty()) continue;
                    TextEmbedderResult result = textEmbedder.embed(chunk);
                    float[] embedding = result.embeddingResult().embeddings().get(0).floatEmbedding();
                    dbHelper.insertChunk(docId, chunk, embedding);
                }
                
                Log.i(TAG, "Document processing complete: " + fileName);
                if (listener != null) {
                    listener.onCompleted(true, fileName, "Uploaded");
                }

            } catch (Exception e) {
                Log.e(TAG, "Error processing document", e);
                if (listener != null) {
                    listener.onCompleted(false, fileName, "Upload failed");
                }
            }
        }).start();
    }

    public List<String> getStoredDocumentNames(Context context) {
        ensureDb(context);
        return dbHelper.getAllDocumentNames();
    }

    public void removeDocumentByName(Context context, String documentName) {
        ensureDb(context);
        dbHelper.deleteDocumentByName(documentName);
    }

    private String extractText(Context context, Uri uri) throws Exception {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            }
        }
    }

    private String cleanText(String text) {
        if (text == null) return "";

        String normalized = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]", " ");

        String[] lines = normalized.split("\\n");
        StringBuilder cleaned = new StringBuilder();
        boolean previousBlank = false;
        for (String rawLine : lines) {
            String line = rawLine.replaceAll("\\s+", " ").trim();
            if (line.isEmpty()) {
                if (!previousBlank && cleaned.length() > 0) {
                    cleaned.append("\n\n");
                }
                previousBlank = true;
                continue;
            }

            if (cleaned.length() > 0 && !previousBlank) {
                cleaned.append("\n");
            }
            cleaned.append(line);
            previousBlank = false;
        }

        return cleaned.toString().trim();
    }

    private List<String> chunkTextByStructure(String text, int targetWordsPerChunk, int overlapUnits) {
        List<String> units = splitIntoSemanticUnits(text);
        List<String> chunks = new ArrayList<>();
        if (units.isEmpty()) {
            return chunks;
        }

        int start = 0;
        while (start < units.size()) {
            StringBuilder chunk = new StringBuilder();
            int wordCount = 0;
            int end = start;

            while (end < units.size()) {
                String unit = units.get(end);
                int unitWords = countWords(unit);
                if (chunk.length() > 0 && wordCount + unitWords > targetWordsPerChunk) {
                    break;
                }

                if (chunk.length() > 0) {
                    chunk.append("\n\n");
                }
                chunk.append(unit);
                wordCount += unitWords;
                end++;
            }

            String chunkText = chunk.toString().trim();
            if (!chunkText.isEmpty()) {
                chunks.add(chunkText);
            }

            if (end >= units.size()) {
                break;
            }
            start = Math.max(start + 1, end - overlapUnits);
        }

        return chunks;
    }

    // Minimum composite score for a chunk to be considered relevant.
    // USE gives lower cosine for question-vs-statement form; keyword overlap compensates.
    // Completely unrelated queries score ~0.10–0.20; in-scope factual queries ~0.30–0.45.
    private static final double RELEVANCE_THRESHOLD = 0.28;

    public String retrieve(String query, int topK) {
        if (!isReady()) return "";

        try {
            TextEmbedderResult queryResult = textEmbedder.embed(query);
            float[] queryEmbedding = queryResult.embeddingResult().embeddings().get(0).floatEmbedding();

            List<DocumentChunk> allChunks = dbHelper.getAllChunks();
            if (allChunks.isEmpty()) return "";

            Set<String> queryTerms = extractQueryTerms(query);
            List<ScoredChunk> scoredChunks = new ArrayList<>();
            for (DocumentChunk chunk : allChunks) {
                double similarity = cosineSimilarity(queryEmbedding, chunk.embedding);
                double keywordScore = keywordOverlapScore(chunk.normalizedText, queryTerms);
                double exactMatchBoost = containsImportantPhrase(chunk.normalizedText, queryTerms) ? 0.08 : 0.0;
                double finalScore = similarity * 0.82 + keywordScore * 0.18 + exactMatchBoost;

                scoredChunks.add(new ScoredChunk(chunk, finalScore));
            }

            scoredChunks.sort((a, b) -> Double.compare(b.score, a.score));

            double bestScore = scoredChunks.isEmpty() ? 0.0 : scoredChunks.get(0).score;
            Log.i(TAG, "Best retrieval score: " + String.format("%.3f", bestScore) +
                    " (threshold=" + RELEVANCE_THRESHOLD + ")");

            // If the best chunk doesn't clear the relevance bar, the query is out-of-scope.
            if (scoredChunks.isEmpty() || bestScore < RELEVANCE_THRESHOLD) {
                Log.i(TAG, "No relevant chunks found — query is out of scope for the documents");
                return "";
            }

            // Only keep chunks that are above the threshold.
            List<ScoredChunk> relevant = new ArrayList<>();
            for (ScoredChunk sc : scoredChunks) {
                if (sc.score >= RELEVANCE_THRESHOLD) relevant.add(sc);
            }

            List<ScoredChunk> selectedChunks = selectDiverseChunks(relevant, topK);
            StringBuilder context = new StringBuilder();
            for (ScoredChunk scoredChunk : selectedChunks) {
                if (context.length() > 0) {
                    context.append("\n\n");
                }
                context.append("Source: ")
                        .append(scoredChunk.chunk.docName)
                        .append("\n")
                        .append(scoredChunk.chunk.text);
            }

            return context.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "Error during retrieval", e);
            return "";
        }
    }

    public String answerFromDocuments(String query) {
        if (!isReady() || query == null || query.trim().isEmpty()) {
            return "";
        }

        try {
            List<DocumentChunk> allChunks = dbHelper.getAllChunks();
            if (allChunks.isEmpty()) return "";

            Set<String> queryTerms = extractQueryTerms(query);
            QuestionAnswerMatch bestMatch = null;

            for (DocumentChunk chunk : allChunks) {
                for (QuestionAnswerMatch candidate : extractQuestionAnswerPairs(chunk)) {
                    double score = scoreQuestionMatch(candidate.question, query, queryTerms);
                    if (score < 0.72) {
                        continue;
                    }

                    if (bestMatch == null || score > bestMatch.score) {
                        bestMatch = new QuestionAnswerMatch(candidate.question, candidate.answer, candidate.docName, score);
                    }
                }
            }

            if (bestMatch == null || bestMatch.answer == null || bestMatch.answer.trim().isEmpty()) {
                return "";
            }

            Log.i(TAG, "Direct QA match from " + bestMatch.docName + " score=" + bestMatch.score);
            return formatDirectAnswer(bestMatch.answer);
        } catch (Exception e) {
            Log.e(TAG, "Error during direct document lookup", e);
            return "";
        }
    }

    private List<String> splitIntoSemanticUnits(String text) {
        List<String> units = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return units;
        }

        String[] blocks = text.split("\\n\\s*\\n+");
        for (String block : blocks) {
            String trimmedBlock = block.trim();
            if (trimmedBlock.isEmpty()) {
                continue;
            }

            List<String> blockUnits = splitQuestionAnswerBlock(trimmedBlock);
            if (blockUnits.isEmpty()) {
                blockUnits.add(trimmedBlock);
            }

            for (String unit : blockUnits) {
                if (countWords(unit) > 180) {
                    units.addAll(splitLongUnit(unit, LONG_UNIT_MAX_WORDS));
                } else {
                    units.add(unit.trim());
                }
            }
        }

        return units;
    }

    private List<String> splitQuestionAnswerBlock(String block) {
        List<String> parts = new ArrayList<>();
        String normalized = block.replace('\n', ' ').trim();
        if (!normalized.toLowerCase(Locale.US).contains("question:")) {
            return parts;
        }

        String[] candidates = Pattern.compile("(?i)(?=question:)").split(normalized);
        for (String candidate : candidates) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    private List<QuestionAnswerMatch> extractQuestionAnswerPairs(DocumentChunk chunk) {
        List<QuestionAnswerMatch> matches = new ArrayList<>();
        String normalized = chunk.text.replace('\n', ' ').trim();
        if (!normalized.toLowerCase(Locale.US).contains("question:")) {
            return matches;
        }

        String[] candidates = Pattern.compile("(?i)(?=question:)").split(normalized);
        for (String candidate : candidates) {
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) continue;

            int answerIndex = indexOfIgnoreCase(trimmed, "answer:");
            if (answerIndex < 0) continue;

            String question = trimmed.substring("Question:".length(), answerIndex).trim();
            String answer = trimmed.substring(answerIndex + "Answer:".length()).trim();
            if (!question.isEmpty() && !answer.isEmpty()) {
                matches.add(new QuestionAnswerMatch(question, answer, chunk.docName, 0.0));
            }
        }
        return matches;
    }

    private List<String> splitLongUnit(String unit, int maxWords) {
        List<String> pieces = new ArrayList<>();
        String[] sentences = unit.split("(?<=[.!?])\\s+");
        if (sentences.length <= 1) {
            String[] words = unit.split("\\s+");
            for (int i = 0; i < words.length; i += maxWords) {
                int end = Math.min(i + maxWords, words.length);
                StringBuilder builder = new StringBuilder();
                for (int j = i; j < end; j++) {
                    if (builder.length() > 0) builder.append(' ');
                    builder.append(words[j]);
                }
                pieces.add(builder.toString().trim());
            }
            return pieces;
        }

        StringBuilder current = new StringBuilder();
        int currentWords = 0;
        for (String sentence : sentences) {
            int sentenceWords = countWords(sentence);
            if (current.length() > 0 && currentWords + sentenceWords > maxWords) {
                pieces.add(current.toString().trim());
                current = new StringBuilder();
                currentWords = 0;
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(sentence.trim());
            currentWords += sentenceWords;
        }
        if (current.length() > 0) {
            pieces.add(current.toString().trim());
        }
        return pieces;
    }

    private int countWords(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return text.trim().split("\\s+").length;
    }

    private static String normalizeForComparison(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Set<String> extractQueryTerms(String query) {
        Set<String> terms = new HashSet<>();
        String normalized = normalizeForComparison(query);
        if (normalized.isEmpty()) {
            return terms;
        }

        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 3) {
                terms.add(token);
            }
        }
        return terms;
    }

    private double scoreQuestionMatch(String candidateQuestion, String query, Set<String> queryTerms) {
        String normalizedCandidate = normalizeForComparison(candidateQuestion);
        String normalizedQuery = normalizeForComparison(query);
        if (normalizedCandidate.isEmpty() || normalizedQuery.isEmpty()) {
            return 0.0;
        }

        if (normalizedCandidate.equals(normalizedQuery)) {
            return 1.0;
        }

        double overlap = keywordOverlapScore(normalizedCandidate, queryTerms);
        double jaccard = textOverlap(normalizedCandidate, normalizedQuery);
        double containsBoost = normalizedCandidate.contains(normalizedQuery)
                || normalizedQuery.contains(normalizedCandidate) ? 0.15 : 0.0;
        return Math.min(1.0, overlap * 0.55 + jaccard * 0.45 + containsBoost);
    }

    private String formatDirectAnswer(String answer) {
        String cleaned = answer.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) return "";
        return cleaned.matches(".*[.!?]$") ? cleaned : cleaned + ".";
    }

    private double keywordOverlapScore(String normalizedChunk, Set<String> queryTerms) {
        if (normalizedChunk == null || normalizedChunk.isEmpty() || queryTerms.isEmpty()) {
            return 0.0;
        }

        int matches = 0;
        for (String term : queryTerms) {
            if (normalizedChunk.contains(term)) {
                matches++;
            }
        }
        return (double) matches / queryTerms.size();
    }

    private boolean containsImportantPhrase(String normalizedChunk, Set<String> queryTerms) {
        if (queryTerms.isEmpty()) return false;

        int matches = 0;
        for (String term : queryTerms) {
            if (normalizedChunk.contains(term)) {
                matches++;
            }
        }
        return matches >= Math.min(3, queryTerms.size());
    }

    private List<ScoredChunk> selectDiverseChunks(List<ScoredChunk> scoredChunks, int topK) {
        List<ScoredChunk> selected = new ArrayList<>();
        Map<Long, Integer> perDocumentCounts = new HashMap<>();

        for (ScoredChunk candidate : scoredChunks) {
            if (selected.size() >= topK) {
                break;
            }

            Integer sameDocumentCountValue = perDocumentCounts.get(candidate.chunk.docId);
            int sameDocumentCount = sameDocumentCountValue != null ? sameDocumentCountValue : 0;
            if (sameDocumentCount >= 3) {
                continue;
            }

            boolean nearDuplicate = false;
            for (ScoredChunk existing : selected) {
                if (textOverlap(existing.chunk.normalizedText, candidate.chunk.normalizedText) > 0.8) {
                    nearDuplicate = true;
                    break;
                }
            }
            if (nearDuplicate) {
                continue;
            }

            selected.add(candidate);
            perDocumentCounts.put(candidate.chunk.docId, sameDocumentCount + 1);
        }

        if (selected.size() < topK) {
            for (ScoredChunk candidate : scoredChunks) {
                if (selected.size() >= topK) {
                    break;
                }
                if (!selected.contains(candidate)) {
                    selected.add(candidate);
                }
            }
        }

        return selected;
    }

    private double textOverlap(String first, String second) {
        if (first == null || second == null || first.isEmpty() || second.isEmpty()) {
            return 0.0;
        }

        Set<String> firstTerms = new HashSet<>();
        Collections.addAll(firstTerms, first.split("\\s+"));
        Set<String> secondTerms = new HashSet<>();
        Collections.addAll(secondTerms, second.split("\\s+"));

        if (firstTerms.isEmpty() || secondTerms.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection = new HashSet<>(firstTerms);
        intersection.retainAll(secondTerms);

        Set<String> union = new HashSet<>(firstTerms);
        union.addAll(secondTerms);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
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
        String scheme = uri.getScheme();
        if ("content".equals(scheme)) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result == null) {
                return "document";
            }
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }

    private int indexOfIgnoreCase(String source, String target) {
        return source.toLowerCase(Locale.US).indexOf(target.toLowerCase(Locale.US));
    }

    public void clear() {
        if (dbHelper != null) dbHelper.clearAll();
    }
}
