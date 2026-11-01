/*
 * Copyright (C) 2026 The WarpShare Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mokee.warpshare;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;

public class StorageUtil {

    private static final String TAG = "StorageUtil";
    public static final String FOLDER_NAME = "WarpShare";

    public static File getTargetDirectory(Context context) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        if (!dir.canWrite()) {
            File fallback = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (fallback != null) {
                dir = new File(fallback, FOLDER_NAME);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
            }
        }
        return dir;
    }

    public static File getTargetFile(Context context, String fileName) {
        String cleanName = sanitizeFileName(fileName);
        return new File(getTargetDirectory(context), cleanName);
    }

    public static File getUniqueTargetFile(Context context, String fileName) {
        String cleanName = sanitizeFileName(fileName);
        File dir = getTargetDirectory(context);
        File file = new File(dir, cleanName);
        if (!file.exists()) {
            return file;
        }

        String baseName = cleanName;
        String extension = "";
        int dotIndex = cleanName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = cleanName.substring(0, dotIndex);
            extension = cleanName.substring(dotIndex);
        }

        int count = 1;
        while (file.exists()) {
            file = new File(dir, baseName + " (" + count + ")" + extension);
            count++;
        }
        return file;
    }

    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "file_" + System.currentTimeMillis();
        }
        // Extract plain filename without path traversal
        String name = new File(fileName).getName();
        // Remove illegal filesystem characters on Android/FAT32 while strictly preserving '+', spaces, and unicode
        name = name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
        if (name.isEmpty()) {
            return "file_" + System.currentTimeMillis();
        }
        return name;
    }

    public static String inferExtension(String contentType, byte[] sample) {
        if (contentType != null) {
            String mime = contentType.split(";")[0].trim().toLowerCase();
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null && !ext.isEmpty()) {
                return "." + ext;
            }
        }
        if (sample != null && sample.length >= 4) {
            if (sample[0] == (byte) 0x89 && sample[1] == (byte) 0x50 && sample[2] == (byte) 0x4E && sample[3] == (byte) 0x47) {
                return ".png";
            }
            if (sample[0] == (byte) 0xFF && sample[1] == (byte) 0xD8 && sample[2] == (byte) 0xFF) {
                return ".jpg";
            }
            if (sample[0] == (byte) 0x47 && sample[1] == (byte) 0x49 && sample[2] == (byte) 0x46) {
                return ".gif";
            }
            if (sample[0] == (byte) 0x25 && sample[1] == (byte) 0x50 && sample[2] == (byte) 0x44 && sample[3] == (byte) 0x46) {
                return ".pdf";
            }
            if (sample[0] == (byte) 0x49 && sample[1] == (byte) 0x44 && sample[2] == (byte) 0x33) {
                return ".mp3";
            }
            if (sample[0] == (byte) 0xFF && (sample[1] == (byte) 0xFB || sample[1] == (byte) 0xF3)) {
                return ".mp3";
            }
        }
        return "";
    }

    public static void indexFile(Context context, File file) {
        if (file == null || !file.exists()) return;

        // 1. Trigger MediaScannerConnection for universal scanner
        MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, (path, uri) -> {
            Log.d(TAG, "MediaScanner scanned: " + path + " -> " + uri);
        });

        // 2. On Android 10+ (Q+), insert into MediaStore if possible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                String extension = MimeTypeMap.getFileExtensionFromUrl(file.getAbsolutePath());
                String mimeType = null;
                if (extension != null) {
                    mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
                }
                if (mimeType == null) {
                    mimeType = "application/octet-stream";
                }

                ContentResolver resolver = context.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.SIZE, file.length());

                Uri collectionUri;
                if (mimeType.startsWith("image/")) {
                    collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER_NAME);
                } else if (mimeType.startsWith("audio/")) {
                    collectionUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER_NAME);
                } else if (mimeType.startsWith("video/")) {
                    collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER_NAME);
                } else {
                    collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER_NAME);
                }

                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            } catch (Exception e) {
                Log.w(TAG, "MediaStore indexing notice", e);
            }
        }
    }
}
