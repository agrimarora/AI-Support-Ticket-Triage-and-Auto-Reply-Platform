package com.ticket.domain.ai;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

@Component
public class EmbeddingService {

    public static final int DIMENSION = 1536;

    /**
     * Generates a deterministic normalized 1536-dimensional vector for text.
     * Ready for plug-in OpenAI / Spring AI embedding client in production,
     * while enabling hermetic pgvector cosine distance operations locally and in tests.
     */
    public float[] generateEmbedding(String text) {
        float[] vector = new float[DIMENSION];
        if (text == null || text.isBlank()) {
            return vector;
        }

        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

            byte[] md5Hash = md5.digest(text.getBytes(StandardCharsets.UTF_8));
            byte[] shaHash = sha256.digest(text.getBytes(StandardCharsets.UTF_8));

            // Seed deterministic pseudo-random float vector based on text hashes and word counts
            String[] words = text.toLowerCase().split("\\s+");
            for (int i = 0; i < DIMENSION; i++) {
                int hashByte = (md5Hash[i % md5Hash.length] & 0xFF) ^ (shaHash[i % shaHash.length] & 0xFF);
                int wordHash = words[(i % words.length)].hashCode();
                vector[i] = (float) Math.sin((hashByte + wordHash) * 0.1);
            }

            // Normalize vector to unit length for cosine similarity
            double norm = 0.0;
            for (float v : vector) {
                norm += v * v;
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < DIMENSION; i++) {
                    vector[i] /= (float) norm;
                }
            }

        } catch (NoSuchAlgorithmException e) {
            Arrays.fill(vector, 0.0f);
        }

        return vector;
    }

    public String toVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
