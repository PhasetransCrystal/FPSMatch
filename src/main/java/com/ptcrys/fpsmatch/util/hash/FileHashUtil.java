package com.ptcrys.fpsmatch.util.hash;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class FileHashUtil {

    public static String getFileHash(File file, String algorithm) throws NoSuchAlgorithmException, IOException {
        MessageDigest digest = MessageDigest.getInstance(algorithm);

        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
                FileChannel channel = randomAccessFile.getChannel()) {

            long position = 0;
            long length = file.length();
            long chunkSize = 8 * 1024 * 1024; // 8MB chunks

            while (position < length) {
                long remaining = length - position;
                long size = Math.min(remaining, chunkSize);

                MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, position, size);
                digest.update(buffer);
                position += size;
            }
        }

        byte[] bytes = digest.digest();
        return bytesToHex(bytes);
    }

    public static String calculateHash(File file, HashAlgorithm algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm.getAlgorithm());
            return calculateFileHash(file, digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Unsupported algorithm: " + algorithm, e);
        }
    }

    private static String calculateFileHash(File file, MessageDigest digest) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] byteArray = new byte[8192];
            int bytesCount;

            while ((bytesCount = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesCount);
            }
        }

        byte[] bytes = digest.digest();
        return bytesToHex(bytes);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
