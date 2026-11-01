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
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.google.gson.JsonObject;
import com.koushikdutta.async.AsyncNetworkSocket;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;
import com.koushikdutta.async.http.server.HttpServerRequestCallback;
import com.koushikdutta.async.http.server.UnknownRequestBody;

import org.mokee.warpshare.ConfigManager;

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
                    is.read(buffer);
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

        // 3. GET /WarpShare.shortcut -> Serve signed Apple Shortcut for iOS ShareSheet
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

        // 3. POST /api/upload -> Direct streaming file receiver
        mServer.post("/api/upload", new HttpServerRequestCallback() {
            @Override
            public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
                String fileName = "received_file_" + System.currentTimeMillis();
                try {
                    Uri uri = Uri.parse(request.getPath());
                    String nameParam = request.getQuery().getString("name");
                    if (nameParam != null && !nameParam.trim().isEmpty()) {
                        fileName = URLDecoder.decode(nameParam, "UTF-8");
                    }
                } catch (Exception ignored) {
                }

                // Sanitize file name
                fileName = new File(fileName).getName();

                File targetDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WarpShare");
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }
                if (!targetDir.canWrite()) {
                    targetDir = mContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (targetDir != null && !targetDir.exists()) {
                        targetDir.mkdirs();
                    }
                }

                final File outputFile = new File(targetDir, fileName);
                final String finalFileName = fileName;

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

                    if (emitter != null) {
                        emitter.setDataCallback((emitter1, bb) -> {
                            try {
                                byte[] bytes = bb.getAllByteArray();
                                fos.write(bytes);
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
                        // Trigger MediaScanner so it instantly appears in Android Gallery & Downloads
                        MediaScannerConnection.scanFile(mContext,
                                new String[]{outputFile.getAbsolutePath()}, null, null);

                        JsonObject ok = new JsonObject();
                        ok.addProperty("status", "ok");
                        ok.addProperty("filename", finalFileName);
                        ok.addProperty("size", outputFile.length());
                        response.setContentType("application/json");
                        response.send(ok.toString());

                        if (mListener != null) {
                            String remoteIp = "Apple Device";
                            try {
                                if (request.getSocket() instanceof AsyncNetworkSocket) {
                                    remoteIp = ((AsyncNetworkSocket) request.getSocket()).getRemoteAddress().getAddress().getHostAddress();
                                }
                            } catch (Exception ignored) {}
                            mListener.onFileReceived(outputFile, remoteIp);
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Error creating output file for upload", e);
                    response.code(500).send("Internal Server Error: " + e.getMessage());
                }
            }
        });

        try {
            mServer.listen(com.koushikdutta.async.AsyncServer.getDefault(), port);
            mRunning = true;
            Log.d(TAG, "WebDropServer started listening on port " + port);
        } catch (Exception e) {
            Log.w(TAG, "Port " + port + " error or already in use: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (!mRunning) return;
        if (mServer != null) {
            mServer.stop();
            mServer = null;
        }
        mRunning = false;
        Log.d(TAG, "WebDropServer stopped");
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }
}
