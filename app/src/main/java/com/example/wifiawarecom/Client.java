package com.example.wifiawarecom;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.WifiAwareNetworkInfo;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;


public class Client implements Runnable{

    private static final String TAG = "Client";
    private static final int BUFFER_SIZE = 8192;

    private final ConnectivityManager mConnManager;
    private Network mCurrentNet;
    private NetworkCapabilities mCurrentNetCapabitities;
    private SocketChannel mMainSocketChannel;
    private ByteBuffer mWriteBuffer = ByteBuffer.allocate(BUFFER_SIZE);

    private Thread mClientThread;
    private boolean mEnabled = false;

    public Client(ConnectivityManager manager, DiscoverySession subscribeSession, PeerHandle handle){
        mCurrentNetCapabitities = null;
        mCurrentNet = null;
        mMainSocketChannel = null;
        mConnManager = manager;
        NetworkSpecifier networkSpecifier = new WifiAwareNetworkSpecifier.Builder(subscribeSession, handle)
                .setPskPassphrase("1234")
                .build();
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(networkSpecifier)
                .build();
        mConnManager.requestNetwork(networkRequest, new WifiAwareNetworkCallback());
    }

    public void start(){
        if(!mEnabled && mCurrentNetCapabitities != null) {
            mEnabled = true;
            mClientThread = new Thread(this);
            mClientThread.start();
        }
    }

    public void stop(){
        if(mEnabled){
            mEnabled = false;
            mClientThread.interrupt();
        }
    }

    @Override
    public void run() {
        try {
            if(!mConnManager.bindProcessToNetwork(mCurrentNet)) return;
            WifiAwareNetworkInfo peerAwareInfo = (WifiAwareNetworkInfo) mCurrentNetCapabitities.getTransportInfo();
            InetAddress peerIpv6 = peerAwareInfo.getPeerIpv6Addr();
            int peerPort = peerAwareInfo.getPort();
            mMainSocketChannel = SocketChannel.open();
            mMainSocketChannel.connect(new InetSocketAddress(peerIpv6, peerPort));
            int i = 0;
            while(mEnabled){
                mWriteBuffer.clear();
                mWriteBuffer = ByteBuffer.wrap(String.valueOf(i).getBytes());
                mMainSocketChannel.write(mWriteBuffer);
                ++i;
                i %= 11;
            }
            mMainSocketChannel.close();
        } catch (IOException ex){
            Log.d(TAG, "Client run: " + ex.toString());
        }
        finally {
            mEnabled = false;
            mClientThread = null;
            mMainSocketChannel = null;
            mCurrentNetCapabitities = null;
            mCurrentNet = null;
        }
    }


    private class WifiAwareNetworkCallback extends ConnectivityManager.NetworkCallback{

        @Override
        public void onAvailable(@NonNull Network network) {
            mCurrentNet = network;
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            if(mCurrentNet.equals(network) && mCurrentNetCapabitities == null){
                mCurrentNetCapabitities = networkCapabilities;
                start();
            }
            else Log.d(TAG, "onCapabilitiesChanged: Red distinta o nueva capability");
        }

        @Override
        public void onLost(@NonNull Network network) {
            mConnManager.unregisterNetworkCallback(this);
            stop();
        }
    }
}