/*
 * Copyright (C) 2019 The MoKee Open Source Project
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

package org.mokee.warpshare.airdrop;

import android.util.Log;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

class AirDropWlanController {

    private static final String TAG = "AirDropWlanController";

    private static final String[] INTERFACES = new String[]{"wlan0", "wlan1", "wlan", "swlan0", "p2p-wlan0-0"};

    private final Object mLock = new Object();

    private NetworkInterface mInterface;
    private InetAddress mLocalAddress;

    private void getLocalAddressInternal() {
        NetworkInterface iface = null;
        InetAddress address = null;

        for (String name : INTERFACES) {
            try {
                iface = NetworkInterface.getByName(name);
            } catch (SocketException e) {
                Log.w(TAG, "Failed getting interface " + name, e);
                continue;
            }
            if (iface == null) {
                continue;
            }

            address = extractValidAddress(iface);
            if (address != null) {
                break;
            }
        }

        // Fallback: enumerate all network interfaces if preferred ones are not available
        if (iface == null || address == null) {
            try {
                final Enumeration<NetworkInterface> allInterfaces = NetworkInterface.getNetworkInterfaces();
                while (allInterfaces != null && allInterfaces.hasMoreElements()) {
                    final NetworkInterface candidate = allInterfaces.nextElement();
                    if (!candidate.isUp() || candidate.isLoopback()) {
                        continue;
                    }
                    final InetAddress candidateAddr = extractValidAddress(candidate);
                    if (candidateAddr != null) {
                        iface = candidate;
                        address = candidateAddr;
                        break;
                    }
                }
            } catch (SocketException e) {
                Log.w(TAG, "Failed enumerating network interfaces", e);
            }
        }

        if (iface == null) {
            Log.e(TAG, "No available interface found");
            mInterface = null;
            mLocalAddress = null;
        } else if (address == null) {
            Log.e(TAG, "No address available for interface " + iface.getName());
            mInterface = null;
            mLocalAddress = null;
        } else {
            Log.d(TAG, "Found available interface " + iface.getName() + ", " + address.getHostAddress());
            mInterface = iface;
            mLocalAddress = address;
        }
    }

    private InetAddress extractValidAddress(NetworkInterface iface) {
        final Enumeration<InetAddress> addresses = iface.getInetAddresses();
        Inet6Address address6 = null;
        Inet4Address address4 = null;
        while (addresses.hasMoreElements()) {
            final InetAddress addr = addresses.nextElement();
            if (address6 == null && addr instanceof Inet6Address && !addr.isLoopbackAddress()) {
                try {
                    address6 = (Inet6Address) Inet6Address.getByAddress(null, addr.getAddress());
                } catch (UnknownHostException ignored) {
                }
            } else if (address4 == null && addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                address4 = (Inet4Address) addr;
            }
        }
        return address4 != null ? address4 : address6;
    }

    boolean ready() {
        synchronized (mLock) {
            getLocalAddressInternal();
            return mInterface != null && mLocalAddress != null;
        }
    }

    NetworkInterface getInterface() {
        synchronized (mLock) {
            getLocalAddressInternal();
            if (mInterface == null) {
                return null;
            }
        }

        return mInterface;
    }

    InetAddress getLocalAddress() {
        synchronized (mLock) {
            getLocalAddressInternal();
            if (mLocalAddress == null) {
                return null;
            }
        }

        return mLocalAddress;
    }

}
