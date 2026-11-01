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

package org.mokee.warpshare.webdrop;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.koushikdutta.async.AsyncNetworkSocket;
import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.http.body.AsyncHttpRequestBody;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;
import com.koushikdutta.async.http.server.HttpServerRequestCallback;
import com.koushikdutta.async.http.server.UnknownRequestBody;

import org.mokee.warpshare.ConfigManager;
import org.mokee.warpshare.StorageUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class WebDropServer {

    private static final String TAG = "WebDropServer";
    public static final int DEFAULT_PORT = 8888;

    private final Context mContext;
    private final ConfigManager mConfigManager;
    private AsyncHttpServer mServer;
    private boolean mRunning = false;
    private FileReceiveListener mListener;

    public interface FileReceiveListener {
        default void onReceiveStart(String fileName, long totalBytes, String remoteIp) {}
        default void onReceiveProgress(String fileName, int percent, long bytesReceived, long totalBytes) {}
        void onFileReceived(File file, String remoteIp);
        void onReceiveError(String error);
    }

    public WebDropServer(Context context, FileReceiveListener listener) {
        mContext = context.getApplicationContext();
        mConfigManager = new ConfigManager(mContext);
        mListener = listener;
    }

    public synchronized void start(int port) {
        if (mRunning) return;

        mServer = new AsyncHttpServer();

        // 1. GET / -> Serve HTML
        mServer.get("/", new HttpServerRequestCallback() {
            @Override
            public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
                try {
                    InputStream is = mContext.getAssets().open("webdrop/index.html");
                    int size = is.available();
                    byte[] buffer = new byte[size];
                    int read = 0;
                    while (read < size) {
                        int r = is.read(buffer, read, size - read);
                        if (r == -1) break;
                        read += r;
                    }
                    is.close();
                    String html = new String(buffer, StandardCharsets.UTF_8);
                    response.setContentType("text/html; charset=utf-8");
                    response.send(html);
                } catch (IOException e) {
                    Log.e(TAG, "Error loading webdrop HTML asset", e);
                    response.code(500).send("Error loading WebDrop Portal");
                }
            }
        });

        // 2. GET /api/info -> Return device name and info
        mServer.get("/api/info", new HttpServerRequestCallback() {
            @Override
            public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
                JsonObject json = new JsonObject();
                json.addProperty("name", mConfigManager.getName());
                json.addProperty("model", Build.MODEL);
                json.addProperty("manufacturer", Build.MANUFACTURER);
                json.addProperty("version", "WarpShare Tier-1 Apple WebDrop");
                response.setContentType("application/json");
                response.send(json.toString());
            }
        });

        // 3. GET /WarpShare.shortcut -> Serve signed Apple Shortcut
        mServer.get("/WarpShare.shortcut", new HttpServerRequestCallback() {
            @Override
            public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
                try {
                    InputStream is = mContext.getAssets().open("webdrop/WarpShare.shortcut");
                    int size = is.available();
                    byte[] buffer = new byte[size];
                    int read = 0;
                    while (read < size) {
                        int r = is.read(buffer, read, size - read);
                        if (r == -1) break;
                        read += r;
                    }
                    is.close();
                    response.setContentType("application/x-apple-shortcut");
                    response.getHeaders().set("Content-Disposition", "attachment; filename=\"WarpShare.shortcut\"");
                    response.send("application/x-apple-shortcut", buffer);
                } catch (IOException e) {
                    Log.e(TAG, "Error loading WarpShare.shortcut asset", e);
                    response.code(404).send("Shortcut not found");
                }
            }
        });

        // 4. GET /api/files -> Return list of received files
        mServer.get("/api/files", new HttpServerRequestCallback() {
            @Override
            public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
                File dir = StorageUtil.getTargetDirectory(mContext);
                JsonArray array = new JsonArray();
                if (dir.exists()) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile()) {
                                JsonObject obj = new JsonObject();
                                obj.addProperty("name", f.getName());
                                obj.addProperty("size", f.length());
                                obj.addProperty("lastModified", f.lastModified());
                                array.add(obj);
                            }
                        }
                    }
                }
                response.setContentType("application/json; charset=utf-8");
                response.send(array.toString());
            }
        });

        // 5. GET /api/download/.* -> Stream file back to Mac/iOS
        mServer.get("/api/download/.*", new HttpServerRequestCallback() {
            @Override
            public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
                String path = request.getPath();
                String name = path.replace("/api/download/", "");
                try {
                    name = URLDecoder.decode(name.replace("+", "%2B"), "UTF-8");
                } catch (Exception ignored) {}
                File file = StorageUtil.getTargetFile(mContext, name);
                if (file.exists() && file.isFile()) {
                    response.sendFile(file);
                } else {
                    response.code(404).send("File not found");
                }
            }
        });

        // 6. POST /api/upload -> Direct streaming file receiver with live progress
        mServer.post("/api/upload", new HttpServerRequestCallback() {
            @Override
            public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
                String fileName = "received_file_" + System.currentTimeMillis();
                try {
                    String nameParam = request.getQuery().getString("name");
                    if (nameParam != null && !nameParam.trim().isEmpty()) {
                        fileName = URLDecoder.decode(nameParam.replace("+", "%2B"), "UTF-8");
                    }
                } catch (Exception ignored) {
                }

                // Sanitize file name
                fileName = new File(fileName).getName();

                final File outputFile = StorageUtil.getTargetFile(mContext, fileName);
                final String finalFileName = fileName;

                String remoteIp = "Apple Device";
                try {
                    if (request.getSocket() instanceof AsyncNetworkSocket) {
                        remoteIp = ((AsyncNetworkSocket) request.getSocket()).getRemoteAddress().getAddress().getHostAddress();
                    }
                } catch (Exception ignored) {}
                final String finalRemoteIp = remoteIp;

                long totalBytes = -1;
                try {
                    String cl = request.getHeaders().get("Content-Length");
                    if (cl != null) {
                        totalBytes = Long.parseLong(cl.trim());
                    }
                } catch (Exception ignored) {}
                final long finalTotalBytes = totalBytes;

                if (mListener != null) {
                    mListener.onReceiveStart(finalFileName, finalTotalBytes, finalRemoteIp);
                }

                try {
                    final FileOutputStream fos = new FileOutputStream(outputFile);
                    com.koushikdutta.async.http.body.AsyncHttpRequestBody<?> body = request.getBody();
                    DataEmitter emitter = null;
                    if (body instanceof UnknownRequestBody) {
                        emitter = ((UnknownRequestBody) body).getEmitter();
                    } else if (body instanceof DataEmitter) {
                        emitter = (DataEmitter) body;
                    } else if (request instanceof DataEmitter) {
                        emitter = (DataEmitter) request;
                    }

                    final long[] bytesReceived = {0};
                    final long[] lastProgressUpdate = {0};

                    if (emitter != null) {
                        emitter.setDataCallback((emitter1, bb) -> {
                            try {
                                byte[] bytes = bb.getAllByteArray();
                                fos.write(bytes);
                                bytesReceived[0] += bytes.length;

                                long now = System.currentTimeMillis();
                                if (now - lastProgressUpdate[0] > 300 || (finalTotalBytes > 0 && bytesReceived[0] == finalTotalBytes)) {
                                    lastProgressUpdate[0] = now;
                                    int percent = finalTotalBytes > 0 ? (int)((bytesReceived[0] * 100) / finalTotalBytes) : 0;
                                    if (mListener != null) {
                                        mListener.onReceiveProgress(finalFileName, percent, bytesReceived[0], finalTotalBytes);
                                    }
                                }
                            } catch (IOException e) {
                                Log.e(TAG, "Error writing uploaded chunk", e);
                            }
                        });
                    }

                    request.setEndCallback(ex -> {
                        try {
                            fos.flush();
                            fos.close();
                        } catch (IOException ignored) {
                        }

                        if (ex != null) {
                            Log.e(TAG, "Upload failed for " + finalFileName, ex);
                            outputFile.delete();
                            response.code(500).send("Upload failed: " + ex.getMessage());
                            if (mListener != null) mListener.onReceiveError(ex.getMessage());
                            return;
                        }

                        Log.d(TAG, "File received successfully: " + outputFile.getAbsolutePath());
                        // Index file into Android system MediaStore & Downloads
                        StorageUtil.indexFile(mContext, outputFile);

                        JsonObject ok = new JsonObject();
                        ok.addProperty("status", "ok");
                        ok.addProperty("filename", finalFileName);
                        ok.addProperty("size", outputFile.length());
                        response.setContentType("application/json");
                        response.send(ok.toString());

                        if (mListener != null) {
                            mListener.onFileReceived(outputFile, finalRemoteIp);
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Error creating output file for upload", e);
                    response.code(500).send("Internal Server Error: " + e.getMessage());
                    if (mListener != null) mListener.onReceiveError(e.getMessage());
                }
            }
        });

        try {
            mServer.listen(AsyncServer.getDefault(), port);
            mRunning = true;
            Log.d(TAG, "WebDropServer started listening on port " + port);
        } catch (Exception e) {
            Log.e(TAG, "Failed starting WebDropServer on port " + port, e);
        }
    }

    public synchronized void stop() {
        if (mServer != null) {
            try {
                mServer.stop();
            } catch (Exception ignored) {
            }
            mServer = null;
        }
        mRunning = false;
        Log.d(TAG, "WebDropServer stopped");
    }

    public boolean isRunning() {
        return mRunning;
    }
}
