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

import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.mokee.warpshare.R;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class WebDropBottomSheet extends BottomSheetDialogFragment implements WebDropServer.FileReceiveListener {

    public static final String TAG = "WebDropBottomSheet";

    private ImageView mQrCodeImage;
    private TextView mPortalUrlText;
    private TextView mStatusText;
    private Button mBtnCopyUrl;
    private Button mBtnOpenDownloads;

    private WebDropServer mServer;
    private String mPortalUrl = "";
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_webdrop, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mQrCodeImage = view.findViewById(R.id.qr_code_image);
        mPortalUrlText = view.findViewById(R.id.portal_url_text);
        mStatusText = view.findViewById(R.id.webdrop_status);
        mBtnCopyUrl = view.findViewById(R.id.btn_copy_url);
        mBtnOpenDownloads = view.findViewById(R.id.btn_open_downloads);

        final String ip = getLocalIpAddress();
        mPortalUrl = "http://" + ip + ":" + WebDropServer.DEFAULT_PORT;
        mPortalUrlText.setText(mPortalUrl);

        // Generate and display QR code
        final Bitmap qrBitmap = QRCodeUtil.generateQRCode(mPortalUrl, 440, 440);
        if (qrBitmap != null) {
            mQrCodeImage.setImageBitmap(qrBitmap);
        }

        mBtnCopyUrl.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("WebDrop URL", mPortalUrl));
                Toast.makeText(getContext(), "Tautan WebDrop disalin ke papan klip", Toast.LENGTH_SHORT).show();
            }
        });

        mPortalUrlText.setOnClickListener(v -> mBtnCopyUrl.performClick());

        mBtnOpenDownloads.setOnClickListener(v -> {
            try {
                startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
            } catch (Exception e) {
                Toast.makeText(getContext(), "Buka file manager untuk melihat folder Downloads/WarpShare", Toast.LENGTH_LONG).show();
            }
        });

        // Start server if not running
        try {
            mServer = new WebDropServer(requireContext(), this);
            mServer.start(WebDropServer.DEFAULT_PORT);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Do not kill the server on view destroy so transfers can finish in background
    }

    @Override
    public void onFileReceived(File file, String remoteIp) {
        mMainHandler.post(() -> {
            if (mStatusText != null) {
                mStatusText.setText("✓ Berhasil menerima: " + file.getName() + " dari " + remoteIp);
                mStatusText.setTextColor(0xFF34C759); // iOS green
            }
            if (getContext() != null) {
                Toast.makeText(getContext(), "File diterima: " + file.getName(), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onReceiveError(String error) {
        mMainHandler.post(() -> {
            if (mStatusText != null) {
                mStatusText.setText("✕ Gagal menerima: " + error);
                mStatusText.setTextColor(0xFFFF3B30); // iOS red
            }
        });
    }

    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "192.168.1.1";
    }
}
