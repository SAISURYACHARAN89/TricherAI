package com.example.offlineai;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

public class RagDatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "rag_store.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_DOCUMENTS = "documents";
    public static final String COLUMN_DOC_ID = "id";
    public static final String COLUMN_DOC_NAME = "name";

    public static final String TABLE_CHUNKS = "chunks";
    public static final String COLUMN_CHUNK_ID = "id";
    public static final String COLUMN_CHUNK_DOC_ID = "document_id";
    public static final String COLUMN_CHUNK_TEXT = "chunk_text";
    public static final String COLUMN_CHUNK_EMBEDDING = "embedding";

    public RagDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createDocsTable = "CREATE TABLE " + TABLE_DOCUMENTS + " (" +
                COLUMN_DOC_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COLUMN_DOC_NAME + " TEXT)";
        db.execSQL(createDocsTable);

        String createChunksTable = "CREATE TABLE " + TABLE_CHUNKS + " (" +
                COLUMN_CHUNK_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COLUMN_CHUNK_DOC_ID + " INTEGER, " +
                COLUMN_CHUNK_TEXT + " TEXT, " +
                COLUMN_CHUNK_EMBEDDING + " BLOB, " +
                "FOREIGN KEY(" + COLUMN_CHUNK_DOC_ID + ") REFERENCES " + TABLE_DOCUMENTS + "(" + COLUMN_DOC_ID + "))";
        db.execSQL(createChunksTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CHUNKS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_DOCUMENTS);
        onCreate(db);
    }

    public long insertDocument(String name) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_DOC_NAME, name);
        return db.insert(TABLE_DOCUMENTS, null, values);
    }

    public void deleteDocumentByName(String name) {
        SQLiteDatabase db = this.getWritableDatabase();
        Cursor cursor = db.query(
                TABLE_DOCUMENTS,
                new String[]{COLUMN_DOC_ID},
                COLUMN_DOC_NAME + " = ?",
                new String[]{name},
                null,
                null,
                null
        );

        try {
            while (cursor.moveToNext()) {
                long docId = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_DOC_ID));
                db.delete(TABLE_CHUNKS, COLUMN_CHUNK_DOC_ID + " = ?", new String[]{String.valueOf(docId)});
            }
        } finally {
            cursor.close();
        }

        db.delete(TABLE_DOCUMENTS, COLUMN_DOC_NAME + " = ?", new String[]{name});
    }

    public void insertChunk(long docId, String text, float[] embedding) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_CHUNK_DOC_ID, docId);
        values.put(COLUMN_CHUNK_TEXT, text);
        values.put(COLUMN_CHUNK_EMBEDDING, toByteArray(embedding));
        db.insert(TABLE_CHUNKS, null, values);
    }

    public List<String> getAllDocumentNames() {
        List<String> names = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(
                TABLE_DOCUMENTS,
                new String[]{COLUMN_DOC_NAME},
                null,
                null,
                null,
                null,
                COLUMN_DOC_ID + " DESC"
        );

        try {
            while (cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DOC_NAME));
                if (name != null && !name.trim().isEmpty()) {
                    names.add(name);
                }
            }
        } finally {
            cursor.close();
        }

        return names;
    }

    public List<RagManager.DocumentChunk> getAllChunks() {
        List<RagManager.DocumentChunk> chunks = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        String sql = "SELECT c." + COLUMN_CHUNK_DOC_ID + ", d." + COLUMN_DOC_NAME + ", c." + COLUMN_CHUNK_TEXT + ", c." + COLUMN_CHUNK_EMBEDDING +
                " FROM " + TABLE_CHUNKS + " c INNER JOIN " + TABLE_DOCUMENTS + " d ON c." + COLUMN_CHUNK_DOC_ID + " = d." + COLUMN_DOC_ID;
        Cursor cursor = db.rawQuery(sql, null);

        if (cursor.moveToFirst()) {
            do {
                long docId = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_CHUNK_DOC_ID));
                String docName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DOC_NAME));
                String text = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CHUNK_TEXT));
                byte[] blob = cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_CHUNK_EMBEDDING));
                float[] embedding = toFloatArray(blob);
                chunks.add(new RagManager.DocumentChunk(docId, docName, text, embedding));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return chunks;
    }

    public void clearAll() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_CHUNKS, null, null);
        db.delete(TABLE_DOCUMENTS, null, null);
    }

    private byte[] toByteArray(float[] floats) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(floats.length * 4);
        FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();
        floatBuffer.put(floats);
        return byteBuffer.array();
    }

    private float[] toFloatArray(byte[] bytes) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();
        float[] floats = new float[bytes.length / 4];
        floatBuffer.get(floats);
        return floats;
    }
}
